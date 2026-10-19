package com.piggyhand.fish;

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
    /** 视频预览路由特征串（2026-10-19 实测：videoURL 明文直链挂在参数里） */
    private static final String ROUTE_VIDEO = "video_preview_page";

    /** 7.25.31 实名侦察：Flutter 容器家族 + 原生详情页（hook 挂点，注入时按路由过滤） */
    private static final String[] CONTAINERS = {
            "com.taobao.idlefish.detail.DetailActivity",
            "com.idlefish.flutterboost.containers.FlutterBoostActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishAVFlutterActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostSurfaceActivity",
            "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostTransparencyActivity",
    };

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
                                // 只在图片查看器/视频预览路由贴 🎣（详情页/首页不显示）
                                android.content.Intent it = act.getIntent();
                                String data = it == null ? null : it.getDataString();
                                if (data != null && (data.contains(ROUTE_GALLERY) || data.contains(ROUTE_VIDEO))) {
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
        flog("[float] 🎣 悬浮钮 hook 完成，容器类 " + armed + "/" + CONTAINERS.length);
    }

    /** 路由侦察：intent 结构打日志（收敛注入范围 + 单张定位的数据底料） */
    private static void dumpRoute(Activity act) {
        try {
            android.content.Intent it = act.getIntent();
            if (it == null) return;
            String data = it.getDataString();
            // 画廊/视频路由喂媒体缓存（每次全量覆盖 → 永远跟随当前查看器）
            harvestGallery(it);
            harvestVideo(it);
            if (data == null) return;
            StringBuilder sb = new StringBuilder("[route] ");
            sb.append(act.getClass().getSimpleName());
            if (data != null) sb.append(" data=").append(data);
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
            org.json.JSONArray arr = new org.json.JSONArray(beans);
            List<String> urls = new ArrayList<String>();
            for (int k = 0; k < arr.length(); k++) {
                org.json.JSONObject bean = arr.optJSONObject(k);
                if (bean == null) continue;
                org.json.JSONObject info = bean.optJSONObject("imgInfo");
                if (info == null) continue;
                String u = info.optString("url", "");
                if (u == null || u.length() == 0) u = info.optString("imagePath", "");
                if (u != null && u.length() > 0) urls.add(u.startsWith("//") ? "https:" + u : u);
            }
            RhFishMedia.setImageUrls(urls);
        } catch (Throwable ignored) {
        }
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
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.rightMargin = dp(act, 16);
            lp.bottomMargin = dp(act, 96);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMenu(act);
                }
            });

            wrap.addView(btn);
            act.addContentView(wrap, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
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
        try {
            de.robv.android.xposed.XposedBridge.log("[RewardHook] " + msg);
        } catch (Throwable ignored) {
        }
        RhFileLog.write(msg);
    }
}
