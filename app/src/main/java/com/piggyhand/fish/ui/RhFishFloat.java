package com.piggyhand.fish.ui;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.Reflect;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.hook.RhFishMedia;
import com.piggyhand.fish.hook.RhMediaDownloader;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 🎣 悬浮工具钮（v1.1）：
 * - 显示时机：仅在图片查看器路由（fleamarket://detail_photo_gallery）出现，
 *   点进详情页不显示（用户需求：开大图才有钩子）
 * - 数据源：画廊路由 imageBeans（闲鱼传给查看器的明文原图清单，无水印 URL），
 *   每次画廊 onCreate 全量覆盖缓存 → 菜单数字/保存内容永远跟随当前商品
 * - UI：自绘圆角深色卡片菜单（替代系统老式 AlertDialog）
 * - 容器 hook 仍按类名家族（6 类）挂 onCreate，路由判断收敛注入
 * 全部静默容错。
 */
public final class RhFishFloat {

    private static final String TAG_MARK = "rh_fish_float";
    /** 图片查看器路由特征串 */
    private static final String ROUTE_GALLERY = "detail_photo_gallery";
    /** 原生图片查看器路由特征串（dex 实证：fleamarket://native_detail_photo_gallery?&pageUniqueId=） */
    private static final String ROUTE_GALLERY_NATIVE = "native_detail_photo_gallery";
    /** 视频预览路由特征串（2026-10-19 实测：videoURL 明文直链挂在参数里） */
    private static final String ROUTE_VIDEO = "video_preview_page";

    /** 7.25.31 实名侦察：Flutter 容器家族 + 原生详情页（hook 挂点，注入时按路由过滤）
     *  7.29.20 追加：图片查看器已从 Flutter 容器改为原生 Activity
     *  （实测 2026-11-26：点图集进 com.taobao.idlefish.photo.DetailPhotoGalleryActivity，
     *   intent 只有 act=android.intent.action.idlefish + component + extras，没有 data URI），
     *   原 CONTAINERS 全是 Flutter 家族，类名对不上 → onCreate 从未被 hook → 三天日志 [float] 0 行。 */
    private static final String[] CONTAINERS = {
            "com.taobao.idlefish.detail.DetailActivity",
            "com.taobao.idlefish.photo.DetailPhotoGalleryActivity",
            "com.idlefish.flutterboost.containers.FlutterBoostActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishAVFlutterActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostSurfaceActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostTransparencyActivity",
    };

    /** 原生图片查看器类名特征（7.29.20+）：这类 Activity 没有 data URI，按类名判定 */
    private static final String CLS_NATIVE_GALLERY = "DetailPhotoGalleryActivity";

    private RhFishFloat() {}

    public static void hook(final ClassLoader cl) {
        // 配置开关：fishMedia=false 时整个 🎣 功能不挂（模块 App「强化功能→无水印保存」可切，重启闲鱼生效）
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg == null || !cfg.fishMedia) {
                flog("[float] disabled by config (fishMedia=false)");
                return;
            }
        } catch (Throwable t) {
            flog("[float] config check fail, continue: " + t);
        }
        // 仅主进程注入（:channel/:gpu_process 等子进程无 UI 容器，跳过）
        try {
            String proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            if (proc != null && proc.contains(":")) return;
        } catch (Throwable ignored) {
        }
        int armed = 0;
        for (String name : CONTAINERS) {
            try {
                Class<?> cls = Reflect.findClassIfExists(name, cl);
                if (cls == null) continue;
                Reflect.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object thiz = param.thisObject;
                            if (thiz instanceof Activity) {
                                Activity act = (Activity) thiz;
                                dumpRoute(act);
                                // 只在图片查看器/视频预览贴 🎣（详情页/首页不显示）
                                if (isMediaViewer(act)) {
                                    inject(act);
                                }
                            }
                        } catch (Throwable t) {
                            flog("[float] inject err: " + t);
                        }
                    }
                });
                armed++;
            } catch (Throwable t) {
                flog("[float] hook fail " + name + ": " + t);
            }
        }
        // 7.29.20 原生查看器兜底：DetailPhotoGalleryActivity 不 override onCreate
        // （dex 实证：类方法表只有 <init> / buildUriFromIntent / initHeroTransition /
        //   finishGallery / showDebugEntryToast 等，onCreate 继承自 BaseFragmentActivity），
        // 所以「按类名挂 onCreate」这条路对它永远挂不到 —— 上一版加进 CONTAINERS 后
        // 点开大图依旧零日志，就是这个原因。改成挂 Activity.onCreate 基类，回调里按类名过滤。
        try {
            Reflect.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object thiz = param.thisObject;
                        if (!(thiz instanceof Activity)) return;
                        Activity act = (Activity) thiz;
                        String cn = act.getClass().getName();
                        if (cn == null || !cn.contains(CLS_NATIVE_GALLERY)) return;
                        dumpRoute(act);
                        inject(act);
                    } catch (Throwable t) {
                        flog("[float] native gallery err: " + t);
                    }
                }
            });
            flog("[float] native gallery fallback armed (Activity.onCreate)");
        } catch (Throwable t) {
            flog("[float] native gallery fallback fail: " + t);
        }
        // 第三道保险：直接挂查看器自己的构造器。Activity.onCreate 是基类方法，
        // 部分 ROM/LSPosed 组合下对 boot class 的 hook 会静默失败；构造器是应用类方法，
        // 一定挂得上。构造期 window 还没建，post 到主线程 600ms 后再注入。
        try {
            Class<?> g = Reflect.findClassIfExists("com.taobao.idlefish.photo.DetailPhotoGalleryActivity", cl);
            if (g != null) {
                Reflect.hookAllConstructors(g, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object thiz = param.thisObject;
                            if (!(thiz instanceof Activity)) return;
                            final Activity act = (Activity) thiz;
                            flog("[float] ctor hit: " + act.getClass().getName());
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        dumpRoute(act);
                                        inject(act);
                                    } catch (Throwable t) {
                                        flog("[float] ctor inject err: " + t);
                                    }
                                }
                            }, 600);
                        } catch (Throwable t) {
                            flog("[float] ctor err: " + t);
                        }
                    }
                });
                flog("[float] native gallery ctor fallback armed");
            } else {
                flog("[float] native gallery class NOT FOUND");
            }
        } catch (Throwable t) {
            flog("[float] native gallery ctor fail: " + t);
        }
        flog("[float] 🎣 悬浮钮 hook 完成，容器类 " + armed + "/" + CONTAINERS.length);
    }

    /** 单实例路由日志去重（WeakHashMap：Activity 销毁后自动回收，不泄漏） */
    private static final java.util.Map<Activity, Boolean> DUMPED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Activity, Boolean>());

    /** 首次访问返回 true，之后同一实例返回 false */
    private static boolean dumpOnce(Activity act) {
        try {
            return DUMPED.put(act, Boolean.TRUE) == null;
        } catch (Throwable t) {
            return true; // 去重失败就退化成每次都打，不影响功能
        }
    }

    /**
     * 判定当前 Activity 是不是「图片查看器 / 视频预览」。
     *
     * 两条判定路径并存，覆盖新旧两代实现：
     *  1. 原生查看器（7.29.20+）：类名含 DetailPhotoGalleryActivity。它的 intent data 是
     *     fleamarket://native_detail_photo_gallery?...&imageBeans=[...]（实测有值，2344 字符），
     *     所以 URI 判断也能命中；但该类不 override onCreate，按类名挂 onCreate 挂不到，
     *     才是 11-26 三天日志 [float] 全 0 的真正原因。
     *  2. Flutter 容器（7.25.x~7.29.10）：靠 intent data 里的 detail_photo_gallery /
     *     native_detail_photo_gallery / video_preview_page 判断，逻辑保持不变。
     */
    private static boolean isMediaViewer(Activity act) {
        try {
            String cn = act.getClass().getName();
            if (cn != null && cn.contains(CLS_NATIVE_GALLERY)) return true;
            android.content.Intent it = act.getIntent();
            String data = it == null ? null : it.getDataString();
            if (data == null) return false;
            return data.contains(ROUTE_GALLERY)
                    || data.contains(ROUTE_GALLERY_NATIVE)
                    || data.contains(ROUTE_VIDEO);
        } catch (Throwable t) {
            flog("[float] isMediaViewer err: " + t);
            return false;
        }
    }

    /** 路由侦察：intent 结构打日志（收敛注入范围 + 单张定位的数据底料） */
    private static void dumpRoute(Activity act) {
        try {
            android.content.Intent it = act.getIntent();
            if (it == null) return;
            String data = it.getDataString();
            // 日志去重：ctor 与 Activity.onCreate 两条路径都会走到这里，同一个 Activity
            // 只处理一次，否则一次点开大图刷 4 条同样的长 URI
            boolean firstDump = dumpOnce(act);
            if (firstDump) {
                // 画廊/视频路由喂媒体缓存（全量覆盖 → 永远跟随当前查看器）
                harvestGallery(it);
                harvestVideo(it);
                // 7.29.20 原生查看器：真正的图集在 ROUTER_URL_DATA / url_query_param 里
                harvestGalleryExtras(it);
                dumpExtrasKeys(it, act.getClass().getSimpleName());
            }
            if (data == null || !firstDump) return;
            StringBuilder sb = new StringBuilder("[route] ");
            sb.append(act.getClass().getSimpleName());
            // 长 URI 截断：7.29.20 的 native_detail_photo_gallery 把整个 imageBeans 塞进
            // data（实测 2344 字符），全量打会把日志撑爆
            sb.append(" data=").append(data.length() > 300 ? data.substring(0, 300) + "..." : data);
            String url = it.getStringExtra("url");
            if (url != null) sb.append(" url=").append(url);
            String s = sb.toString();
            if (s.contains("fleamarket") || s.contains("detail") || s.contains("item")
                    || s.contains("image") || s.contains("pic")) {
                flog(s);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * extras 侦察：打印 key 名 + 值的类型和长度（不打内容，避免日志爆）。
     *
     * 7.29.20 的图片查看器 intent 是
     *   Intent { act=android.intent.action.idlefish cmp=.../.photo.DetailPhotoGalleryActivity (has extras) }
     * —— 没有任何 data URI，图集数据只能从 extras 进。先看清楚 key 与类型，再决定取值方式。
     * 只对图片查看器打，详情页/首页不刷日志。
     */
    private static void dumpExtrasKeys(android.content.Intent it, String clsSimple) {
        try {
            if (clsSimple == null || !clsSimple.contains(CLS_NATIVE_GALLERY)) return;
            android.os.Bundle b = it.getExtras();
            if (b == null) {
                flog("[route] extras = null (" + clsSimple + ")");
                return;
            }
            StringBuilder sb = new StringBuilder("[route] extras(" + clsSimple + ") keys=");
            java.util.Set<String> ks = b.keySet();
            int n = 0;
            for (String k : ks) {
                if (n++ > 40) { sb.append("..."); break; }
                Object v = b.get(k);
                sb.append(k).append('[');
                if (v == null) {
                    sb.append("null");
                } else {
                    sb.append(v.getClass().getSimpleName());
                    if (v instanceof String) {
                        sb.append(':').append(((String) v).length());
                    } else if (v instanceof java.util.Collection) {
                        sb.append(':').append(((java.util.Collection<?>) v).size());
                    }
                }
                sb.append("] ");
            }
            flog(sb.toString());
        } catch (Throwable t) {
            flog("[route] dumpExtrasKeys err: " + t);
        }
    }

    /**
     * 从 extras 里捞图集（7.29.20 原生查看器兜底路径）。
     *
     * 实测这个查看器的 extras 是路由器封装：ROUTER_URL_DATA(HierarchicalUri) +
     * url_query_param(String,2344) + spm-url，真正的 imageBeans 藏在 url_query_param 的
     * 查询串里。正常情况 intent 的 data URI 也能拿到同一份数据（harvestGallery 已经处理），
     * 这里做兜底：万一某天宿主不再把 data 挂到 intent 上，从 extras 的 query 串再抠一次。
     * 命中才覆盖 RhFishMedia，否则保持旧缓存不动。
     */
    private static void harvestGalleryExtras(android.content.Intent it) {
        try {
            android.os.Bundle b = it.getExtras();
            if (b == null) return;
            for (String k : b.keySet()) {
                Object v = b.get(k);
                if (!(v instanceof String)) continue;
                String s = (String) v;
                int i = s.indexOf("imageBeans=");
                if (i < 0) continue;
                i += "imageBeans=".length();
                int end = s.indexOf('&', i);
                String beans = end < 0 ? s.substring(i) : s.substring(i, end);
                try {
                    beans = java.net.URLDecoder.decode(beans, "UTF-8");
                } catch (Throwable ignored) {
                }
                List<String> got = parseBeans(beans);
                if (!got.isEmpty()) {
                    flog("[float] extras[" + k + "] 图集 " + got.size() + " 张");
                    RhFishMedia.setImageUrls(got);
                    return;
                }
            }
        } catch (Throwable t) {
            flog("[float] harvestGalleryExtras err: " + t);
        }
    }

    /** 从 video_preview_page 路由参数提取 videoURL（明文视频直链）→ RhFishMedia 缓存 */
    private static void harvestVideo(android.content.Intent it) {
        try {
            String data = it.getDataString();
            if (data == null || !data.contains(ROUTE_VIDEO)) return;
            int i = data.indexOf("videoURL=");
            if (i < 0) return;
            i += "videoURL=".length();
            int end = data.indexOf('&', i);
            String u = end < 0 ? data.substring(i) : data.substring(i, end);
            u = java.net.URLDecoder.decode(u, "UTF-8");
            if (u.startsWith("//")) u = "https:" + u;
            RhFishMedia.setVideoUrl(u);
        } catch (Throwable ignored) {
        }
    }

    /** 从 detail_photo_gallery 路由参数提取 imageBeans[].imgInfo.url → RhFishMedia 缓存（全量覆盖） */
    private static void harvestGallery(android.content.Intent it) {
        try {
            String data = it.getDataString();
            if (data == null || !data.contains("imageBeans=")) return;
            int i = data.indexOf("imageBeans=") + "imageBeans=".length();
            int end = data.indexOf('&', i);
            String beans = end < 0 ? data.substring(i) : data.substring(i, end);
            beans = java.net.URLDecoder.decode(beans, "UTF-8");
            List<String> urls = parseBeans(beans);
            if (!urls.isEmpty()) RhFishMedia.setImageUrls(urls);
        } catch (Throwable ignored) {
        }
    }

    /** imageBeans JSON → 无水印 URL 列表（URI 参数版与 extras 版共用同一套字段） */
    private static List<String> parseBeans(String beans) {
        List<String> urls = new ArrayList<String>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(beans);
            for (int k = 0; k < arr.length(); k++) {
                org.json.JSONObject bean = arr.optJSONObject(k);
                if (bean == null) continue;
                org.json.JSONObject info = bean.optJSONObject("imgInfo");
                if (info == null) continue;
                String u = info.optString("url", "");
                if (u == null || u.length() == 0) u = info.optString("imagePath", "");
                if (u != null && u.length() > 0) urls.add(u.startsWith("//") ? "https:" + u : u);
            }
        } catch (Throwable ignored) {
        }
        return urls;
    }

    /** 注入 🎣 钮（tag 防重复；延迟 300ms 等 content 就绪） */
    private static void inject(final Activity act) {
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            if (decor.findViewWithTag(TAG_MARK) != null) return; // 已注入
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    injectNow(act);
                }
            }, 300);
        } catch (Throwable t) {
            flog("[float] inject err: " + t);
        }
    }

    private static void injectNow(final Activity act) {
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            if (decor.findViewWithTag(TAG_MARK) != null) return; // 已注入

            FrameLayout wrap = new FrameLayout(act);
            wrap.setTag(TAG_MARK);

            TextView btn = new TextView(act);
            btn.setText("🎣");
            btn.setTextSize(22);
            btn.setGravity(Gravity.CENTER);
            btn.setClickable(true);
            btn.setFocusable(true);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0xB3222222); // 半透明深底，不抢内容
            btn.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(act, 52), dp(act, 52));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMenu(act);
                }
            });

            wrap.addView(btn);
            // 容器只占按钮那一小块（WRAP_CONTENT + 右下角定位），不能再铺 MATCH_PARENT：
            // 原生图片查看器靠全屏 ViewPager 处理滑动/双击缩放，铺满会吃掉手势。
            FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wrapLp.gravity = Gravity.BOTTOM | Gravity.END;
            wrapLp.rightMargin = dp(act, 16);
            wrapLp.bottomMargin = dp(act, 96);
            act.addContentView(wrap, wrapLp);
            flog("[float] 🎣 注入 " + act.getClass().getSimpleName());
        } catch (Throwable t) {
            flog("[float] addContentView err: " + t);
        }
    }

    // ==================== 自绘菜单 UI（液态玻璃深色卡片风） ====================

    /** 功能菜单（自绘圆角卡片，替代老式 AlertDialog） */
    private static void showMenu(final Activity act) {
        try {
            final List<String> imgs = RhFishMedia.getImageUrls();
            final String video = RhFishMedia.getVideoUrl();

            // 行定义：[emoji, 文案, 动作tag]
            final List<String[]> rows = new ArrayList<String[]>();
            if (!imgs.isEmpty()) rows.add(new String[]{"🖼", "保存全部图片（" + imgs.size() + " 张，无水印）", "all"});
            if (!imgs.isEmpty()) rows.add(new String[]{"🔢", "保存指定张（输入序号）", "one"});
            if (video != null) rows.add(new String[]{"🎬", "保存视频", "video"});

            final Dialog dlg = new Dialog(act);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(act, 24));
            bg.setColor(0xF2202020);
            card.setBackground(bg);

            // 标题
            card.addView(text(act, "🎣 小猪手工具", 17, true, 0xFFFFFFFF, new int[]{dp(act, 24), dp(act, 22), dp(act, 24), dp(act, 4)}));
            // 副标题：当前媒体统计
            String sub = imgs.isEmpty() && video == null
                    ? "没读到当前商品的图片，关掉大图重新点开一张试试"
                    : "图片 " + imgs.size() + " 张" + (video != null ? " · 视频 1 个" : "") + " · 保存到相册「小猪手」";
            card.addView(text(act, sub, 12, false, 0x99FFFFFF, new int[]{dp(act, 24), 0, dp(act, 24), dp(act, 10)}));
            card.addView(line(act));

            if (rows.isEmpty()) {
                card.addView(text(act, " :( ", 14, false, 0x66FFFFFF, new int[]{dp(act, 24), dp(act, 18), dp(act, 24), dp(act, 18)}));
            }
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) card.addView(line(act));
                final String[] row = rows.get(i);
                TextView tv = text(act, row[0] + "  " + row[1], 16, false, 0xFFEAEAEA,
                        new int[]{dp(act, 24), dp(act, 15), dp(act, 24), dp(act, 15)});
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            dlg.dismiss();
                            if ("all".equals(row[2])) {
                                RhMediaDownloader.downloadImages(act, imgs);
                            } else if ("video".equals(row[2])) {
                                RhMediaDownloader.downloadVideo(act, video);
                            } else if ("one".equals(row[2])) {
                                askIndex(act, imgs.size());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                card.addView(tv);
            }
            card.addView(line(act));
            // 取消
            TextView cancel = text(act, "取消", 14, false, 0x8CFFFFFF, new int[]{0, dp(act, 14), 0, dp(act, 14)});
            cancel.setGravity(Gravity.CENTER);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                }
            });
            card.addView(cancel);

            showDialog(act, dlg, card);
        } catch (Throwable t) {
            flog("[float] menu err: " + t);
        }
    }

    /** 指定张：同风格输入弹窗 */
    private static void askIndex(final Activity act, final int max) {
        try {
            final Dialog dlg = new Dialog(act);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(act, 24));
            bg.setColor(0xF2202020);
            card.setBackground(bg);

            card.addView(text(act, "保存第几张？（共 " + max + " 张）", 16, true, 0xFFFFFFFF, new int[]{dp(act, 24), dp(act, 22), dp(act, 24), dp(act, 12)}));

            final EditText et = new EditText(act);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setHint("1 - " + max);
            et.setTextSize(16);
            et.setTextColor(0xFFFFFFFF);
            et.setHintTextColor(0x66FFFFFF);
            android.graphics.drawable.GradientDrawable ebg = new android.graphics.drawable.GradientDrawable();
            ebg.setCornerRadius(dp(act, 12));
            ebg.setColor(0x14FFFFFF);
            et.setBackground(ebg);
            et.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.setMargins(dp(act, 24), 0, dp(act, 24), dp(act, 8));
            et.setLayoutParams(elp);
            card.addView(et);

            card.addView(line(act));
            // 按钮行：取消 | 保存
            LinearLayout btnRow = new LinearLayout(act);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView cancel = text(act, "取消", 15, false, 0x8CFFFFFF, new int[]{0, dp(act, 14), 0, dp(act, 14)});
            cancel.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cancel.setLayoutParams(clp);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                }
            });
            TextView ok = text(act, "保存", 15, true, 0xFF4CD97B, new int[]{0, dp(act, 14), 0, dp(act, 14)});
            ok.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ok.setLayoutParams(olp);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        dlg.dismiss();
                        int idx = Integer.parseInt(et.getText().toString().trim());
                        RhMediaDownloader.downloadOneImage(act, idx);
                    } catch (Throwable ignored) {
                    }
                }
            });
            btnRow.addView(cancel);
            View divider = new View(act);
            divider.setBackgroundColor(0x1EFFFFFF);
            btnRow.addView(divider, new LinearLayout.LayoutParams(dp(act, 1), ViewGroup.LayoutParams.MATCH_PARENT));
            btnRow.addView(ok);
            card.addView(btnRow);

            showDialog(act, dlg, card);
        } catch (Throwable t) {
            flog("[float] askIndex err: " + t);
        }
    }

    /** 组装弹窗：透明窗体 + 居中 + 82% 宽 + 点外可关 */
    private static void showDialog(Activity act, Dialog dlg, View content) {
        try {
            dlg.setContentView(content);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                w.setDimAmount(0.45f);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.82);
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
                w.setWindowAnimations(0);
            }
            dlg.setCancelable(true);
            dlg.setCanceledOnTouchOutside(true);
            dlg.show();
        } catch (Throwable t) {
            flog("[float] dialog err: " + t);
        }
    }

    /** 快捷 TextView（padding: l,t,r,b） */
    private static TextView text(Activity act, String s, int sp, boolean bold, int color, int[] pad) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        tv.setTextColor(color);
        tv.setPadding(pad[0], pad[1], pad[2], pad[3]);
        return tv;
    }

    /** 1px 分隔线 */
    private static View line(Activity act) {
        View v = new View(act);
        v.setBackgroundColor(0x1EFFFFFF);
        v.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (act.getResources().getDisplayMetrics().density)));
        lp.setMargins(dp(act, 20), 0, dp(act, 20), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 双写日志：XposedBridge（不依赖 init 时序）+ 文件 */
    private static void flog(String msg) {
        // attach 期 RhFileLog 路径还没就绪，写文件会静默失败 —— 先镜像一份 logcat，
        // 保证 hook 注册结果、extras 结构这类早期/关键信息一定能看到。
        try {
            android.util.Log.e("RewardHook", msg);
        } catch (Throwable ignored) {
        }
        try {
            de.robv.android.xposed.XposedBridge.log("[RewardHook] " + msg);
        } catch (Throwable ignored) {
        }
        RhFileLog.write(msg);
    }
}
