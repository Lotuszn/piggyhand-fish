package com.piggyhand.fish;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/**
 * 闲鱼版本读取：全工程唯一口径（导出日志 / 日志头 / 报告页共用）。
 *
 * 为什么单独抽：
 *  - 旧实现走 su + dumpsys + grep 取 versionName。su 未授权、授权弹窗未点、dumpsys 超时
 *    都会返回 "?"，而导出日志恰恰是用户机器出问题时唯一能拿到的东西——这条链路不能依赖 root。
 *  - 模块 manifest 已对 com.taobao.idlefish 声明 <queries>，App 进程可直接问 PMS；
 *    闲鱼进程内查的是自己，零成本。
 *  - 缓存 60s：导出、刷新日志、报告页会连着问，避免反复 IPC。
 *    用户中途覆盖安装闲鱼 → 调 invalidate() 让下次重查。
 */
public final class FishVer {

    private static final long TTL = 60000L;

    private static volatile String cName;
    private static volatile long cCode = -1;
    private static volatile long cTs;

    private FishVer() {
    }

    public static String name(Context c) {
        ensure(c);
        return cName;
    }

    public static long code(Context c) {
        ensure(c);
        return cCode;
    }

    /** "7.29.10(529)"；查不到给 "?(?)" —— 不编造，缺什么就显示什么 */
    public static String label(Context c) {
        ensure(c);
        return cName + "(" + (cCode < 0 ? "?" : String.valueOf(cCode)) + ")";
    }

    /** 闲鱼被覆盖安装/卸载重装后调用 */
    public static void invalidate() {
        cTs = 0;
        cName = null;
        cCode = -1;
    }

    private static void ensure(Context c) {
        long now = System.currentTimeMillis();
        if (cName != null && now - cTs < TTL) return;
        synchronized (FishVer.class) {
            if (cName != null && now - cTs < TTL) return;
            String n = "?";
            long cd = -1;
            if (c != null) {
                try {
                    PackageInfo pi = c.getPackageManager().getPackageInfo(Symbols.HOST_PKG, 0);
                    if (pi.versionName != null && pi.versionName.length() > 0) n = pi.versionName;
                    // getLongVersionCode 是 API 28+；低版本回落 int 字段（versionCode 仍是有效值）
                    cd = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Throwable ignored) {
                    // 未安装 / 包可见性被系统收紧：保持 "?"，导出头里另记 pkg= 说明是哪一端
                }
            }
            cName = n;
            cCode = cd;
            cTs = now;
        }
    }
}
