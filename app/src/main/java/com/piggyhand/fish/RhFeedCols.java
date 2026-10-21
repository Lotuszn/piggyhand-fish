package com.piggyhand.fish;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 🖼 显示更多商品 · 首页瀑布流列数覆盖（v1.1.4）
 *
 * 为什么不是「缩放/密度」（v1.1.4 实测否决两次）：
 *   ① Flutter 视口 dpr 欺骗：闲鱼首页商品流是原生渲染，FlutterRenderer.setViewportMetrics 零命中；
 *   ② 按应用密度覆盖（Resources.updateConfiguration 压 densityDpi）：
 *      11Pro 实测卡片宽度从 700px 变 0px（列间隙仍在 x≈720），即**卡片是「屏幕宽度的一半」按像素算的，
 *      不吃 dp**——结果只有文字/内边距变小，商品卡尺寸一点没变，纯属负优化。
 *
 * 真正决定卡片大小的是列数：
 *   首页流 = Tangram 卡片 + vlayout 布局。瀑布流卡片（com.tmall.wireless.tangram3.structure.card
 *   .StaggeredCard）在 convertLayoutHelper 里调
 *   com.alibaba.android.vlayout.layout.StaggeredGridLayoutHelper.setLane(style.column)
 *   （默认 2）→ 每个 item 宽度 = 可用宽度 / 列数。
 *   所以把 setLane(2) 改成 setLane(3)，卡片宽度立刻变 1/3，一屏商品数明显增加。
 *
 * 挂载与生效：
 *   · hook 常驻；setLane 传 1（构造器初始化）不动，只改 >=2 的「真实列数」；
 *   · 回调内 3s 惰性重读 feedColsOn / feedCols（ConfigManager.load 走 override 文件，
 *     模块 App 改完即生效；已渲染的页面重建 Tab/重启闲鱼后按新列数排版）。
 *
 * 边界：列数夹 2~4（2=原样）；开关关：整段直通。
 */
public final class RhFeedCols {

    /** 瀑布流布局助手（vlayout）：setLane(int) 即列数 */
    private static final String CLS_STAGGERED_HELPER =
            "com.alibaba.android.vlayout.layout.StaggeredGridLayoutHelper";
    /** 栅格布局助手（vlayout）：Tangram2 的双列卡走这里，构造函数吃列数 */
    private static final String CLS_GRID_HELPER =
            "com.alibaba.android.vlayout.layout.GridLayoutHelper";
    private static final String M_SET_LANE = "setLane";

    private static volatile boolean on = false;
    private static volatile int cols = 2;
    private static volatile long lastCfgAt = 0L;
    private static volatile int lastLoggedCols = -1;
    /** 首次命中诊断（区分「挂上了但没调用」与「根本没挂上」） */
    private static volatile boolean firstHitLogged = false;
    /** TextView 原始字号（避免模板重 bind 时反复缩放） */
    private static final int TAG_TS = 0x5F1DC03;
    /** 卡片圆角半径（px，对应闲鱼卡片 24px 圆角） */
    private static final float CARD_RADIUS = 24f;
    /** 卡片原始高度（判断模板是否重 bind 复位过） */
    private static final int TAG_H = 0x5F1DC04;
    /** 文字收窄诊断前 4 次写盘 */
    private static volatile int adaptLogs = 0;
    /** adaptText 入参诊断前 4 次 */
    private static volatile int adaptDbg = 0;
    /** 高度缩放诊断前 4 次 */
    private static volatile int hLogs = 0;

    private RhFeedCols() {
    }

    public static void hook(ClassLoader cl) {
        int armed = 0;
        armed += hookStaggeredHelper(cl);
        armed += hookRecyclerStaggered(cl);
        if (armed == 0) {
            RhFileLog.log("RhFeedCols: 未找到任何瀑布流布局挂载点，本机不生效");
        }
    }

    /**
     * 实测（11Pro / 闲鱼 7.28.60）：首页商品瀑布流 = androidx.recyclerview.widget
     * .StaggeredGridLayoutManager，构造时 mSpanCount=2（vlayout 的 StaggeredGridLayoutHelper
     * 在本机首页路径上从未被调用）。改这里才真正改变每列宽度。
     */
    private static int hookRecyclerStaggered(ClassLoader cl) {
        Class<?> sglm = Reflect.findClassIfExists(
                "androidx.recyclerview.widget.StaggeredGridLayoutManager", cl);
        if (sglm == null) {
            RhFileLog.log("RhFeedCols: StaggeredGridLayoutManager 未找到");
            return 0;
        }
        int n = 0;
        // 1) 构造器：StaggeredGridLayoutManager(int spanCount, int orientation)
        try {
            Reflect.hookAllConstructors(sglm, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    rewriteSpan(param.args, "ctor");
                }
            });
            n++;
        } catch (Throwable t) {
            RhFileLog.log("RhFeedCols: SGLM 构造 hook FAILED: " + t);
        }
        // 2) setSpanCount：运行期改列数
        try {
            Reflect.hookAllMethods(sglm, "setSpanCount", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    rewriteSpan(param.args, "setSpanCount");
                }
            });
        } catch (Throwable t) {
            RhFileLog.log("RhFeedCols: setSpanCount hook FAILED: " + t);
        }
        RhFileLog.log("RhFeedCols: StaggeredGridLayoutManager armed（列数 2~4）");
        installTextAdapter(cl, sglm);
        return n;
    }

    /* ==================== 文字宽度跟随列宽 ==================== */

    /**
     * 列数变窄后，商品卡的标题/价格等文本节点宽度还是二列时代的设计稿像素（实测标题 617px），
     * 塞进 ~480px 的列里 → 文字按 617 宽排版、右半边被卡片边缘硬裁，看起来「名字显示不全」。
     *
     * 这里只改**文字的宽度**（高度/字号/图片/卡片高度一律不动）：
     *   · 文本节点 lp.width 超过列宽 → 收窄到 列宽-内边距；
     *   · 同时 setMaxWidth（TextView 自身属性，模板不会重置）兜底，保证按列宽重排、超出走省略号。
     */
    private static void installTextAdapter(ClassLoader cl, final Class<?> sglmCls) {
        Class<?> lm = Reflect.findClassIfExists(
                "androidx.recyclerview.widget.RecyclerView$LayoutManager", cl);
        if (lm == null) return;
        int armed = 0;
        armed += hookAdapt(lm, sglmCls, "measureChildWithMargins", false);
        armed += hookAdapt(lm, sglmCls, "measureChild", false);
        // 实测：闲鱼把 measure* 内联了，上面两个可能一次都不触发 → 用 addView 兜底（真实生效点）
        armed += hookAdapt(lm, sglmCls, "addView", true);
        // 兜底二：addView 只在首次挂载时触发，滚动复用的卡不会再走 → 每次布局后全量扫一遍可见卡
        armed += hookRecyclerLayout(cl, sglmCls);
        XposedBridge.log("[RhFeedCols] 文字宽度跟随列宽 armed=" + armed);
        RhFileLog.write("RhFeedCols: 文字宽度跟随列宽 armed=" + armed);
    }

    /** RecyclerView.onLayout 之后遍历所有可见 child（覆盖滚动复用场景） */
    private static int hookRecyclerLayout(ClassLoader cl, final Class<?> sglmCls) {
        Class<?> rvCls = Reflect.findClassIfExists(
                "androidx.recyclerview.widget.RecyclerView", cl);
        if (rvCls == null) return 0;
        try {
            Reflect.hookAllMethods(rvCls, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object lm = Reflect.callMethod(param.thisObject, "getLayoutManager");
                        if (lm == null || !sglmCls.isInstance(lm)) return;
                        int n = ((Integer) Reflect.callMethod(param.thisObject, "getChildCount")).intValue();
                        for (int i = 0; i < n; i++) {
                            android.view.View ch =
                                    (android.view.View) Reflect.callMethod(param.thisObject, "getChildAt", i);
                            if (ch != null) adaptText(ch, lm);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[RhFeedCols] onLayout adapt err: " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[RhFeedCols] hook RecyclerView.onLayout FAILED: " + t);
            return 0;
        }
    }

    private static int hookAdapt(Class<?> lm, final Class<?> sglmCls, String name, final boolean after) {
        try {
            Reflect.hookAllMethods(lm, name, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!after) runAdapt(param, sglmCls);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (after) runAdapt(param, sglmCls);
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[RhFeedCols] hook " + name + " FAILED: " + t);
            return 0;
        }
    }

    private static void runAdapt(XC_MethodHook.MethodHookParam param, Class<?> sglmCls) {
        try {
            Object[] a = param.args;
            if (a == null || a.length < 1 || !(a[0] instanceof android.view.View)) return;
            if (!sglmCls.isInstance(param.thisObject)) return;
            adaptText((android.view.View) a[0], param.thisObject);
        } catch (Throwable t) {
            XposedBridge.log("[RhFeedCols] adapt err: " + t);
        }
    }

    private static void adaptText(android.view.View item, Object lm) {
        if (!refreshCfg()) return;
        applyCardOutline(item);                                 // 圆角：右上角不再被裁成直角
        int rvW = recyclerWidth(lm);
        if (rvW <= 0) return;
        int spanW = rvW / cols;
        int maxW = spanW - 40;                                  // 留左右内边距
        if (maxW < 80) return;
        android.view.View dx = findDxRoot(item);
        android.view.ViewGroup.LayoutParams dlp = dx == null ? null : dx.getLayoutParams();
        if (adaptDbg < 4) {
            adaptDbg++;
            XposedBridge.log("[RhFeedCols] adapt? rvW=" + rvW + " spanW=" + spanW
                    + " dx=" + (dx == null ? "null" : dx.getClass().getSimpleName())
                    + " dlp=" + (dlp == null ? "null" : (dlp.width + "x" + dlp.height))
                    + " item=" + item.getClass().getSimpleName());
        }
        if (dlp == null || dlp.width <= 0) return;
        if (dlp.width > rvW / 2 * 1.2f) return;                 // 满宽卡（金刚区/广告）不动
        if (dlp.width <= maxW) return;                          // 已经是列内尺寸，不必处理
        // 字号缩放比例 = 列宽 / 设计稿卡宽（678 ≈ 屏宽×0.47）。三列时约 0.69。
        float fontScale = spanW / (rvW * 0.47f);
        if (fontScale > 1f) fontScale = 1f;
        if (fontScale < 0.5f) fontScale = 0.5f;
        walkText(item, maxW, fontScale, 0);
        shrinkCardHeight(item, dx, fontScale);                   // 卡片高度：只缩图片区，不动文字区
    }

    /**
     * 卡片高度等比缩小（只缩图片块，文字区原样保留）。
     *
     * 为什么不整卡等比 scale（v1.1.4 第一次尝试，用户「乱七八糟」）：
     *   DXRootView 缩放后视觉宽 480、布局宽还是 678，右侧留一大条空白，文字也被一起缩小。
     *
     * 这次只动高度：
     *   · 找到卡片里最高的那块 ImageView（商品主图）；
     *   · 图片高度 × f（f = 列宽比例，三列约 0.71）；
     *   · 顺着图片的父链（直到卡片根）把高度同步减掉同一个 delta。
     *   文字节点是图片的兄弟，不在父链上 → 高度、字号全不受影响；
     *   根高随之变矮，卡片自然变矮，一屏多放一行。
     */
    private static void shrinkCardHeight(android.view.View item, android.view.View dx, float f) {
        if (f >= 0.98f) return;
        android.view.ViewGroup.LayoutParams dlp = dx.getLayoutParams();
        if (dlp == null || dlp.height <= 0) return;
        // tag 记原始根高：模板重 bind 复位后也能重新缩（同 f 且未复位就跳过）
        Object t = item.getTag(TAG_H);
        int origRootH;
        if (t instanceof Integer) {
            origRootH = (Integer) t;
            if (dlp.height != origRootH) return;                 // 已经缩过
        } else {
            origRootH = dlp.height;
            item.setTag(TAG_H, origRootH);
        }

        android.widget.ImageView img = findMainImage(item);
        if (img == null) return;
        android.view.ViewGroup.LayoutParams ilp = img.getLayoutParams();
        if (ilp == null || ilp.height <= 0) return;
        int imgH = ilp.height;
        int delta = Math.round(imgH * (1f - f));
        if (delta < 20) return;

        ilp.height = Math.max(1, imgH - delta);
        img.requestLayout();
        android.view.View cur = img;
        while (true) {
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof android.view.View)) break;
            cur = (android.view.View) p;
            android.view.ViewGroup.LayoutParams lp = cur.getLayoutParams();
            if (lp != null && lp.height > 0) {
                lp.height = Math.max(1, lp.height - delta);
                cur.requestLayout();
            }
            if (cur == item) break;
        }
        if (hLogs < 4) {
            hLogs++;
            XposedBridge.log("[RhFeedCols] 卡片高度缩 x" + f + " 图片 " + imgH + "->"
                    + (imgH - delta) + " 根 " + origRootH + "->" + (origRootH - delta));
            RhFileLog.write("RhFeedCols: 卡片高度 " + origRootH + " -> " + (origRootH - delta)
                    + "（图片 " + imgH + " -> " + (imgH - delta) + "，x" + f + "）");
        }
    }

    /** 卡片里面积最大（高度最高）的 ImageView = 商品主图 */
    private static android.widget.ImageView findMainImage(android.view.View item) {
        java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<>();
        q.add(item);
        android.widget.ImageView best = null;
        int bestH = 0;
        int guard = 0;
        while (!q.isEmpty() && guard++ < 80) {
            android.view.View v = q.poll();
            if (v instanceof android.widget.ImageView) {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                int h = lp == null ? 0 : lp.height;
                if (h > bestH) {
                    bestH = h;
                    best = (android.widget.ImageView) v;
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return bestH >= 100 ? best : null;
    }

    /**
     * 卡片四角统一圆角（v1.1.4 圆角修复）。
     *
     * 列数改成 3 后，卡片内容宽度还是二列时代的 678，塞进 ~470 的列里，右边那一截连同
     * 右上角的圆弧一起被裁在列外 → 看起来「左上角圆、右上角直角」。
     * 不去改尺寸（改宽度会把文字排版搞乱），改用 ViewOutlineProvider 直接裁剪：
     * 整张卡片按圆角矩形裁一遍，四个角都是圆的，尺寸/排版完全不变。
     */
    private static void applyCardOutline(android.view.View item) {
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        if (item.getClipToOutline()) return;
        try {
            item.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View v, android.graphics.Outline outline) {
                    int w = v.getWidth();
                    int h = v.getHeight();
                    if (w <= 0 || h <= 0) {
                        outline.setEmpty();
                        return;
                    }
                    outline.setRoundRect(0, 0, w, h, CARD_RADIUS);
                }
            });
            item.setClipToOutline(true);
        } catch (Throwable t) {
            XposedBridge.log("[RhFeedCols] outline err: " + t);
        }
    }

    /**
     * 遍历卡片里的文本节点：宽度收到列内 + 字号统一按 fontScale 缩。
     * 覆盖商品标题、价格、卖家昵称、运费/想要人数等所有文本。
     * 原始字号存在 TAG_TS，模板重 bind 时按原值重算，不会越缩越小。
     */
    private static void walkText(android.view.View v, int maxW, float fontScale, int depth) {
        if (v == null || depth > 12) return;
        if (v instanceof android.widget.TextView
                && !(v instanceof android.widget.EditText)) {
            android.widget.TextView tv = (android.widget.TextView) v;
            android.view.ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp != null && lp.width > maxW) lp.width = maxW;

            Object t = tv.getTag(TAG_TS);
            float orig;
            if (t instanceof Float) {
                orig = (Float) t;
            } else {
                orig = tv.getTextSize();
                tv.setTag(TAG_TS, orig);
            }
            int target = Math.max(12, Math.round(orig * fontScale));
            int before = Math.round(tv.getTextSize());
            if (Math.abs(tv.getTextSize() - target) > 0.5f) {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target);
            }
            if (tv.getMaxWidth() != maxW) tv.setMaxWidth(maxW);
            // 收窄后末尾加省略号（单行 + 多行都设），而不是被卡片边缘硬切
            if (tv.getEllipsize() != android.text.TextUtils.TruncateAt.END) {
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            if (adaptLogs < 6) {
                adaptLogs++;
                String txt = tv.getText() == null ? "" : tv.getText().toString();
                if (txt.length() > 10) txt = txt.substring(0, 10);
                XposedBridge.log("[RhFeedCols] 字号 " + before + "->" + target
                        + " (orig=" + (int) orig + " x" + fontScale + ") len="
                        + (tv.getText() == null ? 0 : tv.getText().length())
                        + " [" + txt.replace('\n', ' ') + "]");
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkText(g.getChildAt(i), maxW, fontScale, depth + 1);
            }
        }
    }

    private static android.view.View findDxRoot(android.view.View item) {
        java.util.ArrayDeque<android.view.View> q = new java.util.ArrayDeque<>();
        q.add(item);
        android.view.View fallback = null;
        int guard = 0;
        while (!q.isEmpty() && guard++ < 64) {
            android.view.View v = q.poll();
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (v != item && lp != null && lp.width > 0 && fallback == null) fallback = v;
            if (v.getClass().getSimpleName().contains("DXRootView")) return v;
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return fallback;
    }

    private static int recyclerWidth(Object lm) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = lm.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("mRecyclerView");
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object rv = f.get(lm);
                if (rv instanceof android.view.View) {
                    int w = ((android.view.View) rv).getWidth();
                    if (w > 0) return w;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把「2 列」改写为配置列数；1 列/多列原样保留 */
    private static void rewriteSpan(Object[] args, String from) {
        try {
            if (args == null || args.length < 1 || !(args[0] instanceof Integer)) return;
            int span = (Integer) args[0];
            if (!firstHitLogged) {
                firstHitLogged = true;
                XposedBridge.log("[RhFeedCols] hit " + from + "(span=" + span + ") on=" + on + " cols=" + cols);
            }
            if (!refreshCfg()) return;
            if (span != 2) return;           // 只改闲鱼默认双列流
            if (span == cols) return;
            args[0] = cols;
            if (lastLoggedCols != cols) {
                lastLoggedCols = cols;
                XposedBridge.log("[RhFeedCols] span " + span + " -> " + cols);
                RhFileLog.write("RhFeedCols: 瀑布流列数 " + span + " -> " + cols + "（" + from + "）");
            }
        } catch (Throwable t) {
            XposedBridge.log("[RhFeedCols] rewrite span error: " + t);
        }
    }

    /** 兜底路径：vlayout 瀑布流助手（部分版本 Tangram 走这里） */
    private static int hookStaggeredHelper(ClassLoader cl) {
        Class<?> helper = Reflect.findClassIfExists(CLS_STAGGERED_HELPER, cl);
        if (helper == null) {
            RhFileLog.log("RhFeedCols: StaggeredGridLayoutHelper 未找到");
            return 0;
        }
        try {
            Reflect.hookAllMethods(helper, M_SET_LANE, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] a = param.args;
                        if (a == null || a.length < 1 || !(a[0] instanceof Integer)) return;
                        int lane = (Integer) a[0];
                        if (!firstHitLogged) {
                            firstHitLogged = true;
                            XposedBridge.log("[RhFeedCols] hit setLane(" + lane + ") on=" + on + " cols=" + cols);
                        }
                        if (!refreshCfg()) return;
                        if (lane < 2) return;            // 构造器初始化 setLane(1)，不碰
                        if (lane == cols) return;
                        a[0] = cols;
                        if (lastLoggedCols != cols) {
                            lastLoggedCols = cols;
                            XposedBridge.log("[RhFeedCols] lane " + lane + " -> " + cols);
                            RhFileLog.write("RhFeedCols: 瀑布流列数 " + lane + " -> " + cols);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[RhFeedCols] hook error: " + t);
                    }
                }
            });
            RhFileLog.log("RhFeedCols: StaggeredGridLayoutHelper.setLane armed（列数 2~4）");
            return 1;
        } catch (Throwable t) {
            RhFileLog.log("RhFeedCols: setLane hook FAILED: " + t);
            return 0;
        }
    }

    /** 3s 惰性重读配置；返回「是否需要改写」（开关开且列数有效） */
    private static boolean refreshCfg() {
        long now = System.currentTimeMillis();
        if (now - lastCfgAt > 3000L) {
            lastCfgAt = now;
            try {
                ConfigManager c = ConfigManager.load();
                if (c != null) {
                    on = c.feedColsOn;
                    cols = Math.max(2, Math.min(4, c.feedCols));
                }
            } catch (Throwable ignored) {
            }
        }
        return on && cols >= 3 && cols <= 4;
    }
}
