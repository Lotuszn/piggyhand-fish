package com.piggyhand.fish;

import android.content.res.Configuration;
import android.util.DisplayMetrics;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 🖼 显示更多商品 · 按应用覆盖显示密度（v1.1.4）
 *
 * 为什么不用 Flutter 视口 dpr 欺骗（v1.1.4 实测否决）：
 *   闲鱼首页商品瀑布流是原生渲染（淘宝系 DinamicX/原生 View），Flutter 只承载部分子页；
 *   FlutterRenderer.setViewportMetrics 在首页路径上从未被调用 → 对「首页一屏更多商品」无效。
 *
 * 现方案：在闲鱼进程内，把 Resources 的显示密度按百分比压低 —— 等价于系统的
 *   「设置 → 显示 → 显示大小」调小，但只作用在闲鱼（原生 View 与 Flutter 引擎的
 *   dpr 都取自 Resources.getDisplayMetrics().density，一处压制全局生效）：
 *
 *   updateConfiguration(Configuration, DisplayMetrics)（framework） hook，调用前改：
 *     cfg.densityDpi        = 稳定物理 dpi × percent%
 *     dm.densityDpi         = 同上
 *     dm.density            = densityDpi / 160
 *     dm.scaledDensity      = dm.density × fontScale（保留系统字体倍率）
 *
 * 关键点：
 *   · 基准取 DisplayMetrics.DENSITY_DEVICE_STABLE（ro.sf.lcd_density，物理稳定值），
 *     不是拿当前 metrics 乘 —— 重复调用永不累积，改百分比立刻回正；
 *   · 开关关 / percent>=100：整段直通，零改写；
 *   · 3s 惰性重读 uiScaleOn / uiScalePercent（ConfigManager.load 走 override 文件，
 *     模块 App 改完即生效；下次配置下发/页面重建时应用）。
 *
 * 边界：percent 夹 40~100（100=关）；densityDpi 目标落在 [120, 稳定dpi] 内。
 */
public final class RhUiScale {

    /** 1dp = 多少 px 的换算基数 */
    private static final float DENSITY_DEFAULT_SCALE = 1f / 160f;

    private static volatile boolean on = false;
    private static volatile int percent = 100;
    private static volatile long lastCfgAt = 0L;
    private static volatile int lastLoggedPercent = -1;
    private static volatile boolean firstApplyLogged = false;

    private RhUiScale() {
    }

    public static void hook(ClassLoader cl) {
        try {
            Class<?> res = Reflect.findClassIfExists("android.content.res.Resources", cl);
            if (res == null) {
                RhFileLog.log("RhUiScale: android.content.res.Resources 不可见，跳过");
                return;
            }
            // updateConfiguration(Configuration, DisplayMetrics) 与
            // updateConfiguration(Configuration, DisplayMetrics, CompatibilityInfo) 双签名一起挂
            Reflect.hookAllMethods(res, "updateConfiguration", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        applyScale(param.args);
                    } catch (Throwable t) {
                        XposedBridge.log("[RhUiScale] apply error: " + t);
                    }
                }
            });
            RhFileLog.log("RhUiScale: Resources.updateConfiguration armed（按应用密度覆盖，40~100%）");
        } catch (Throwable t) {
            RhFileLog.log("RhUiScale: hook FAILED: " + t);
        }
    }

    /** 把一次配置下发的密度按百分比压低（幂等：基准永远是物理稳定 dpi） */
    private static void applyScale(Object[] args) {
        if (args == null || args.length < 1) return;
        if (!(args[0] instanceof Configuration)) return;
        if (!refreshCfg()) return;

        Configuration cfg = (Configuration) args[0];
        DisplayMetrics dm = (args.length > 1 && args[1] instanceof DisplayMetrics)
                ? (DisplayMetrics) args[1] : null;

        int base = DisplayMetrics.DENSITY_DEVICE_STABLE;
        if (base <= 0) base = dm != null && dm.densityDpi > 0 ? dm.densityDpi : 560;
        int target = Math.round(base * percent / 100f);
        if (target < 120) target = 120;
        if (target > base) target = base;
        if (target == cfg.densityDpi && (dm == null || target == dm.densityDpi)) return;

        int oldDpi = dm != null && dm.densityDpi > 0 ? dm.densityDpi : cfg.densityDpi;
        cfg.densityDpi = target;

        if (dm != null) {
            dm.densityDpi = target;
            dm.density = target * DENSITY_DEFAULT_SCALE;
            float fontScale = cfg.fontScale > 0f ? cfg.fontScale : 1f;
            dm.scaledDensity = dm.density * fontScale;
        }

        if (!firstApplyLogged) {
            firstApplyLogged = true;
            RhFileLog.write("RhUiScale: 首调 dpi " + oldDpi + " -> " + target
                    + " (" + percent + "%) density=" + (dm == null ? "?" : String.valueOf(dm.density)));
        }
        if (lastLoggedPercent != percent) {
            lastLoggedPercent = percent;
            RhFileLog.write("RhUiScale: dpi " + base + " -> " + target
                    + " (" + percent + "%)，逻辑分辨率 x" + fmt(100f / percent));
        }
    }

    /** 3s 惰性重读配置；返回「是否需要改写」（开关开且 percent 有效） */
    private static boolean refreshCfg() {
        long now = System.currentTimeMillis();
        if (now - lastCfgAt > 3000L) {
            lastCfgAt = now;
            try {
                ConfigManager c = ConfigManager.load();
                if (c != null) {
                    on = c.uiScaleOn;
                    percent = Math.max(40, Math.min(100, c.uiScalePercent));
                }
            } catch (Throwable ignored) {
            }
        }
        return on && percent >= 40 && percent < 100;
    }

    private static String fmt(float f) {
        return String.valueOf(Math.round(f * 100f) / 100f);
    }
}
