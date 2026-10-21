package com.piggyhand.fish;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.util.Enumeration;
import java.util.regex.Pattern;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "RewardHook";
    private static final String SELF_PKG = "com.piggyhand.fish";

    private static ConfigManager cfg;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        if (SELF_PKG.equals(lpp.packageName)) return;

        cfg = ConfigManager.load();
        if (cfg == null || !cfg.enabled) return;
        if (!cfg.targets.isEmpty() && !cfg.targets.contains(lpp.packageName)) return;

        log("=== active on " + lpp.packageName + " ===");
        if (cfg.mtopHook) {
            // 淘系 mtop 侦察 + 广播直调入口（闲鱼专用，其他 App 无此 SDK 时自动跳过）
            FishMtopHook.hook(lpp.classLoader);
            // v1.0.8 🎣 悬浮工具钮（详情页原图/视频保存，Flutter 容器注入）
            RhFishFloat.hook(lpp.classLoader);
        }
        // v1.0.9 💬 聊天页左滑禁用（常驻注册，RhChatHook 回调内热读 noChatSwipe 开关）
        RhChatHook.hook(lpp.classLoader);
        // v1.1.4 🖼 显示更多商品（常驻注册，RhFeedCols 回调内 3s 热读 feedColsOn/feedCols）
        RhFeedCols.hook(lpp.classLoader);
        // v1.1.4 🖼 UI 比例（常驻注册，RhUiScale 回调内 3s 热读 uiScaleOn/uiScalePercent）
        RhUiScale.hook(lpp.classLoader);
        applyRules(lpp);
        if (cfg.adSdkPresets) hookAdSdkPresets(lpp);
        if (cfg.scout) scout(lpp);
    }

    /* ======================= 规则引擎 ======================= */

    private void applyRules(final XC_LoadPackage.LoadPackageParam lpp) {
        for (final ConfigManager.Rule r : cfg.rules) {
            if (r.clazz == null || r.method == null) continue;
            try {
                Class<?> c = Reflect.findClassIfExists(r.clazz, lpp.classLoader);
                if (c == null) {
                    log("rule: class not found -> " + r.clazz);
                    continue;
                }

                if ("return_true".equals(r.action) || "return_false".equals(r.action)) {
                    boolean v = "return_true".equals(r.action);
                    Reflect.hookAllMethods(c, r.method, Reflect.replaceWith(v));
                    log("rule: return_" + v + " -> " + r.clazz + "." + r.method);

                } else if ("return_value".equals(r.action) && r.value != null) {
                    Reflect.hookAllMethods(c, r.method, Reflect.replaceWith(r.value));
                    log("rule: return_value(" + describe(r.value) + ") -> " + r.clazz + "." + r.method);

                } else if ("invoke".equals(r.action) && r.target != null) {
                    Reflect.hookAllMethods(c, r.method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object[] callArgs = buildArgs(r, param.args);
                                Object result;
                                if (r.targetClass != null) {
                                    result = Reflect.callStaticMethod(
                                            Reflect.findClass(r.targetClass, lpp.classLoader),
                                            r.target, callArgs);
                                } else {
                                    result = Reflect.callMethod(param.thisObject, r.target, callArgs);
                                }
                                log("rule: invoke " + (r.targetClass != null ? r.targetClass + "." : "")
                                        + r.target + " -> " + describe(result));
                            } catch (Throwable t) {
                                log("rule invoke FAILED: " + Log.getStackTraceString(t));
                            }
                        }
                    });
                    log("rule: invoke(" + r.target + ") after " + r.clazz + "." + r.method);

                } else { // 默认 log
                    Reflect.hookAllMethods(c, r.method, logHook(r.clazz + "." + r.method));
                    log("rule: log -> " + r.clazz + "." + r.method);
                }
            } catch (Throwable t) {
                log("rule failed " + r.clazz + "." + r.method + ": " + t);
            }
        }
    }

    private static Object[] buildArgs(ConfigManager.Rule r, Object[] hookArgs) {
        if (r.args == null) return new Object[0];
        Object[] out = new Object[r.args.length];
        for (int i = 0; i < r.args.length; i++) {
            Object a = r.args[i];
            // 支持 {"$arg": N}：把被 hook 方法的第 N 个参数透传给目标方法
            if (a instanceof org.json.JSONObject && ((org.json.JSONObject) a).has("$arg")) {
                int idx = ((org.json.JSONObject) a).optInt("$arg", -1);
                out[i] = (hookArgs != null && idx >= 0 && idx < hookArgs.length) ? hookArgs[idx] : null;
            } else {
                out[i] = a;
            }
        }
        return out;
    }

    /* ======================= 侦察模式 ======================= */

    private void scout(final XC_LoadPackage.LoadPackageParam lpp) {
        try {
            DexFile dex = new DexFile(lpp.appInfo.sourceDir);
            Pattern p = Pattern.compile(cfg.scoutPattern, Pattern.CASE_INSENSITIVE);
            Enumeration<String> entries = dex.entries();
            int hooked = 0;
            while (entries.hasMoreElements() && hooked < cfg.maxScoutMethods) {
                String cn = entries.nextElement();
                if (cn == null || !p.matcher(cn).find()) continue;
                hooked += hookWholeClass(lpp, cn, null);
            }
            log("scout finished: " + hooked + " methods hooked (pattern=" + cfg.scoutPattern + ")");
        } catch (Throwable t) {
            log("scout failed: " + t);
        }
    }

    /* ==================== 广告 SDK 预设侦察 ==================== */

    private static final String[] AD_SDK_CLASSES = {
            // 穿山甲 / 字节（CSJ）
            "com.bytedance.sdk.openadsdk.core.reward.TTRewardManager",
            "com.bytedance.sdk.openadsdk.reward.TTRewardVideoAdImpl",
            "com.bytedance.sdk.openadsdk.core.reward.TTRewardCache",
            // 优量汇（GDT / 腾讯）
            "com.qq.e.ads.rewardvideo.RewardVideoAD",
            "com.qq.e.comm.pi.IRewardVideoAD",
            // 快手（KS）
            "com.kwad.components.ad.reward.RewardAd",
            "com.kwad.sdk.api.KsRewardVideoAd",
    };

    private void hookAdSdkPresets(final XC_LoadPackage.LoadPackageParam lpp) {
        for (String cn : AD_SDK_CLASSES) {
            int n = hookWholeClass(lpp, cn, null);
            if (n > 0) log("ad-sdk preset: hooked " + n + " methods in " + cn);
        }
    }

    private int hookWholeClass(XC_LoadPackage.LoadPackageParam lpp, String className, String methodRegex) {
        int n = 0;
        try {
            Class<?> c = lpp.classLoader.loadClass(className);
            Pattern mp = methodRegex == null ? null : Pattern.compile(methodRegex);
            for (Member m : c.getDeclaredMethods()) {
                if (mp != null && !mp.matcher(m.getName()).find()) continue;
                try {
                    XposedBridge.hookMethod(m, logHook(className + "." + m.getName()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, logHook(className + ".<init>"));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // 类不存在（未集成该 SDK / 混淆改名），静默跳过
        }
        return n;
    }

    /* ======================= 日志钩子 ======================= */

    private static XC_MethodHook logHook(final String tag) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                StringBuilder sb = new StringBuilder("[hook] ").append(tag).append(" args=[");
                if (param.args != null) {
                    for (int i = 0; i < param.args.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(describe(param.args[i]));
                    }
                }
                sb.append("]");
                log(sb.toString());
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 3; i < Math.min(st.length, 14); i++) {
                    log("    at " + st[i]);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                log("[hook] " + tag + " returned=" + describe(param.getResult())
                        + " threw=" + param.hasThrowable());
            }
        };
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + o + "\"";
        String s = String.valueOf(o);
        return s.length() > 120 ? s.substring(0, 120) + "...(" + o.getClass().getName() + ")" : s;
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        Log.i(TAG, msg);
        RhFileLog.write(msg);
    }
}
