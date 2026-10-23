package com.piggyhand.fish;

import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * B 类运行日志（10-17）：模块运行细节双写文件，设备适配排查用。
 * - 引擎（闲鱼进程）各 log() 双写到此；插件 App 进程也可用（init 注入各自的 ctx）
 * - 路径：闲鱼 files/zhushou_run_YYYY.MM.DD.log（免 root、清缓存不丢；App 经 su 导出）
 * - 按天分文件、当天首行写环境头（机型/系统）、保留 3 天（第三天删第一天）
 * - 常开句柄 + 每条 flush：崩溃时已写内容不丢；写失败静默吞掉绝不影响主流程
 */
public final class RhFileLog {

    private static final Object LOCK = new Object();
    private static volatile Context appCtx;
    private static String curDay = "";
    private static String cleanedDay = "";
    private static java.io.FileOutputStream fos;

    private RhFileLog() {
    }

    /** 上下文就绪点注入（引擎=Application attach 后；App=onCreate） */
    public static void init(Context ctx) {
        if (ctx == null) return;
        Context ac = ctx.getApplicationContext();
        if (appCtx == null) appCtx = ac != null ? ac : ctx;
    }

    /** 追加一行运行日志（线程安全） */
    public static void write(String msg) {
        Context ctx = appCtx;
        if (ctx == null || msg == null) return;
        try {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
                String day = df.format(new Date(now));
                java.io.File dir = ctx.getExternalFilesDir(null);
                if (dir == null) {
                    dir = new java.io.File(ConfigManager.fishExternal("files"));
                }
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "zhushou_run_" + day + ".log");
                boolean firstToday = !f.exists();
                if (!day.equals(curDay)) {          // 跨天换句柄
                    closeQuietly();
                    curDay = day;
                }
                if (fos == null) fos = new java.io.FileOutputStream(f, true);
                if (firstToday) {                   // 当天首行：环境头（适配排查第一现场）
                    // pkgver = 当前进程宿主包版本（闲鱼进程=闲鱼版本 / App 进程=小猪手版本）
                    String head = "# xiaozhushou=" + BuildInfo.VERSION
                            + " pkgver=" + pkgVersion(ctx)
                            + " model=" + Build.MODEL
                            + " brand=" + Build.BRAND
                            + " android=" + Build.VERSION.RELEASE + "(" + Build.VERSION.SDK_INT + ")"
                            + " pkg=" + ctx.getPackageName() + "\n";
                    fos.write(head.getBytes("UTF-8"));
                }
                String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(now))
                        + " " + msg + "\n";
                fos.write(line.getBytes("UTF-8"));
                fos.flush();
                if (!day.equals(cleanedDay)) {      // 每天首次写盘清理 3 天前
                    cleanedDay = day;
                    cleanOld(dir, df);
                }
            }
        } catch (Throwable ignored) {               // 磁盘满/权限异常等：丢弃本条，下轮重建句柄
            closeQuietly();
        }
    }

    /** XposedBridge.log + 文件双写便捷方法（供未统一收敛的调用点用） */
    public static void log(String msg) {
        XposedBridge.log(msg);
        write(msg);
    }

    /** 当前进程宿主包的 versionName（自己查自己，无可见性限制）；异常返回 "?" */
    private static String pkgVersion(Context ctx) {
        try {
            String v = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 保留今天/昨天/前天，删除更早的 zhushou_run_*.log */
    private static void cleanOld(java.io.File dir, SimpleDateFormat df) {
        try {
            java.io.File[] fs = dir.listFiles();
            if (fs == null) return;
            String keepDay = df.format(new Date(System.currentTimeMillis() - 2 * 86400000L));
            Date kd = df.parse(keepDay);
            for (java.io.File f : fs) {
                String n = f.getName();
                if (!n.startsWith("zhushou_run_") || !n.endsWith(".log")) continue;
                try {
                    Date fd = df.parse(n.substring(12, n.length() - 4));
                    if (fd != null && fd.before(kd)) f.delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly() {
        try {
            if (fos != null) fos.close();
        } catch (Throwable ignored) {
        }
        fos = null;
    }
}
