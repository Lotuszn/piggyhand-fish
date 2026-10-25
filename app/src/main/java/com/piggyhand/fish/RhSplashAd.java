package com.piggyhand.fish;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 🚫 去除开屏广告（RhSplashAd v1）
 *
 * 闲鱼 7.28.30 实测结构（2026-10-26 抓包 + dex 静态还原，见 memory 2026-10-26）：
 *   入口   SplashAdController.requestAndShow(Activity, isHotStart, PreviewInfo)
 *   冷启动 loadSplashAd(Activity)          热启动 loadSplashAdHotStart()
 *   异步   requestAsyncSplashAd(boolean)   toC 兜底 loadToCSplashAd()
 *   展示   SplashAdShower.showSplashAd(Activity, View, Z) / showCover(Activity)
 *   网络   mtop.taobao.idlecommerce.splash.ads (v2.0) + splash.async.ads (v1.0)
 *
 * 拦截策略：从入口掐断，请求根本不发 —— 既能去掉广告，也省掉开屏那 500~1200ms
 * 的 mtop 往返（实测 02:05:58 发请求 → 02:05:59.7 上屏，端到端 1.17s）。
 *
 * 为什么还要主动调 runActionsAfterSplashAd()：
 *   正常路径里广告展示结束后由它把「排队动作」放出来（addActionAfterSplashAd 塞进去的，
 *   比如从外部链接冷启直接跳商品详情）。入口被掐断后这条链断开，不主动清一次，
 *   那些动作会一直挂在队列里，表现为「点分享链接进闲鱼没跳到详情页」。
 *
 * 双层兜底：
 *   [1] Controller 五个入口 —— 主路径
 *   [2] Shower 的 showSplashAd / showCover —— 某些缓存命中路径会绕过 Controller 直接调展示层
 *
 * 开关热生效：回调内 60s 惰性重读 noSplashAd，切开关约 1 分钟内生效，不用重启闲鱼。
 */
public final class RhSplashAd {

    /** 开屏广告控制器（单例 instance()） */
    private static final String CLS_CONTROLLER = "com.taobao.fleamarket.splashad.SplashAdController";
    /** 展示层：真正把广告 View 贴上去的地方 */
    private static final String CLS_SHOWER = "com.taobao.fleamarket.splashad.SplashAdShower";

    /** 开关惰性重读 */
    private static volatile long lastCfg = 0;
    private static volatile boolean enabled = true;
    /** 日志限频（开屏可能一秒内被多次触发） */
    private static volatile long lastLog = 0;

    private RhSplashAd() {
    }

    public static void hook(final ClassLoader cl) {
        Class<?> controller = Reflect.findClassIfExists(CLS_CONTROLLER, cl);
        Class<?> shower = Reflect.findClassIfExists(CLS_SHOWER, cl);
        if (controller == null && shower == null) {
            RhFileLog.log("RhSplashAd: 开屏广告类未找到（版本变更？），跳过");
            return;
        }

        /* ---------- 1. 入口：requestAndShow（掐断 + 放行排队动作） ---------- */
        XC_MethodHook entryKiller = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!on()) return;
                param.setResult(null);
                // 见类注释：不主动放行，外部链接冷启跳详情会卡住
                try {
                    Reflect.callMethod(param.thisObject, "runActionsAfterSplashAd", new Object[0]);
                } catch (Throwable t) {
                    RhFileLog.write("RhSplashAd: runActionsAfterSplashAd 调用失败 -> " + t);
                }
                hit("拦截 requestAndShow（未发起 mtop 请求）");
            }
        };

        // 逐方法计数：类名/方法名对不上时会直接暴露成 0，不用等广告真出来才发现没挂上
        StringBuilder armed = new StringBuilder("RhSplashAd: armed");
        if (controller != null) {
            armed.append(" | Controller")
                    .append(" requestAndShow=").append(hookOne(controller, "requestAndShow", entryKiller))
                    .append(" loadSplashAd=").append(hookOne(controller, "loadSplashAd", killer("loadSplashAd")))
                    .append(" loadSplashAdHotStart=").append(hookOne(controller, "loadSplashAdHotStart", killer("loadSplashAdHotStart")))
                    .append(" requestAsyncSplashAd=").append(hookOne(controller, "requestAsyncSplashAd", killer("requestAsyncSplashAd")))
                    .append(" loadToCSplashAd=").append(hookOne(controller, "loadToCSplashAd", killer("loadToCSplashAd")));
        }
        /* ---------- 2. 兜底：展示层直接置空（缓存命中路径可能绕过 Controller） ---------- */
        if (shower != null) {
            armed.append(" | Shower")
                    .append(" showSplashAd=").append(hookOne(shower, "showSplashAd", killer("showSplashAd")))
                    .append(" showCover=").append(hookOne(shower, "showCover", killer("showCover")));
        }
        RhFileLog.log(armed.toString());
    }

    /** 按名 hook 类内全部同名重载，返回成功挂载数（0 = 类名/方法名对不上，方案失效） */
    private static int hookOne(Class<?> cls, String name, XC_MethodHook h) {
        int n = 0;
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, h);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            RhFileLog.write("RhSplashAd: hookOne " + name + " err: " + t);
        }
        return n;
    }

    /** 生成一个「直接置空」的 hook（stub 的 MethodHookParam 没有 method 字段，名字只能外部传） */
    private static XC_MethodHook killer(final String tag) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!on()) return;
                param.setResult(null);
                hit("拦截 " + tag);
            }
        };
    }

    /** 开关热读：60s 惰性刷新，避免每次开屏都读一遍配置 */
    private static boolean on() {
        long now = System.currentTimeMillis();
        if (now - lastCfg > 60000) {
            lastCfg = now;
            try {
                ConfigManager c = ConfigManager.load();
                if (c != null) enabled = c.noSplashAd;
            } catch (Throwable ignored) {
            }
        }
        return enabled;
    }

    /** 限频日志：开屏流程短，1.5s 内只记一条 */
    private static void hit(String s) {
        long now = System.currentTimeMillis();
        if (now - lastLog < 1500) return;
        lastLog = now;
        RhFileLog.write("RhSplashAd: " + s);
    }
}
