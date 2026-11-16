package com.piggyhand.fish.core;

import android.app.Application;
import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.piggyhand.fish.config.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 虚拟屏保活：闲鱼进程内自建 1x1 私有虚拟屏 + Presentation 挂持续 showing 窗口，
 * 把进程 procstate 抬到 IMPORTANT_FOREGROUND，脱离 CachedAppsFreezer 冻结集合。
 *
 * 真机基线（2026-11-16，HyperOS）：闲鱼退后台约 15 秒即被 cgroup 冻结
 * （uid_10344 下对应 pid 目录的 cgroup.freeze 写成 1），引擎线程全部停摆、socket 无响应。
 *
 * 侦察结论：闲鱼客户端存在 getDisplays() 调用（3 处 dex 命中），故配套 hook 遮蔽，
 * getDisplays 永远只回默认屏，虚拟屏对闲鱼侧不可见。
 *
 * 开关链：UI"常用功能·后台保活"（vdKeepAliveOn，默认开）→
 *   ① 引擎就绪时 startIfEnabled 读 cfg 初值；
 *   ② UI 切换实时发 VD_ON/VD_OFF 命令走 applyEnabled（不回读 cfg，避开 override 文件异步写入竞态）。
 */
public final class VdKeepAlive {

    private static final String TAG = "RhVd";

    private static volatile boolean STARTED = false;
    private static volatile boolean MASKED = false;

    private static volatile VirtualDisplay VD;
    private static volatile Presentation PRES;
    private static volatile ImageReader READER;
    private static volatile Surface SURF;

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private VdKeepAlive() {
    }

    /**
     * MainHook hook 注册期调用：遮蔽闲鱼侧 getDisplays 枚举 + onResume 状态对齐。
     * 对齐逻辑：OFF 拆窗后闲鱼很快被冻结，用户再切 ON 时 socket 不通、VD_ON 命令丢失——
     * 在闲鱼任意 Activity 恢复（解冻）时读 cfg 纠正建/拆状态，保证开关最终一致。
     */
    public static void maskDisplays(ClassLoader cl) {
        if (MASKED) return;
        MASKED = true;
        try {
            Class<?> dm = Reflect.findClassIfExists("android.hardware.display.DisplayManager", cl);
            if (dm == null) {
                log("mask: DisplayManager not found");
            } else {
                Reflect.hookAllMethods(dm, "getDisplays", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Display[] arr = (Display[]) param.getResult();
                            if (arr == null || arr.length <= 1) return;
                            for (Display d : arr) {
                                if (d != null && d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                                    param.setResult(new Display[]{d});
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("mask: getDisplays -> default only");
            }
        } catch (Throwable t) {
            log("mask fail: " + t);
        }
        // onResume 对齐（1s 节流；cfg 与建屏状态不一致时纠正）
        try {
            Reflect.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                private final long[] LAST = new long[]{0L};

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - LAST[0] < 1000L) return;
                    LAST[0] = now;
                    try {
                        boolean want = ConfigManager.load().vdKeepAliveOn;
                        if (want && PRES == null) {
                            startIfEnabled(((android.app.Activity) param.thisObject).getApplicationContext());
                        } else if (!want && PRES != null) {
                            stopInternal();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("resume align hooked");
        } catch (Throwable t) {
            log("resume hook fail: " + t);
        }
    }

    /** TaskRegistry.onEngineReady 时调用：读 cfg 初值，开着才建屏（抢 10~15s 冻结窗口） */
    public static synchronized void startIfEnabled(Context appCtx) {
        if (STARTED || appCtx == null) return;
        boolean on = true;
        try {
            on = ConfigManager.load().vdKeepAliveOn;
        } catch (Throwable ignored) {
            // 读不到配置按开：冻结死引擎是主矛盾，保活是默认解
        }
        if (!on) {
            log("off by config, skip");
            return;
        }
        startInternal(appCtx);
    }

    /** UI 开关实时切换（VD_ON/VD_OFF 命令）：ON 直接建屏 / OFF 拆屏，不回读 cfg */
    public static synchronized void applyEnabled(Context appCtx, boolean on) {
        if (on) {
            if (appCtx == null) return;
            if (PRES != null) {
                log("already on");
                return;
            }
            startInternal(appCtx);
        } else {
            stopInternal();
        }
    }

    /**
     * 建/拆统一 post 主线程：Presentation（Dialog 系）创建与 dismiss 都要求带 Looper 的线程，
     * ReportServer 工作线程（rh-report-conn）无 Looper，直跑会抛
     * "Can't create handler inside thread that has not called Looper.prepare()"。
     */
    private static void startInternal(final Context appCtx) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                doStart(appCtx);
            }
        });
    }

    private static void stopInternal() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                doStop();
            }
        });
    }

    private static void doStart(Context appCtx) {
        if (STARTED || PRES != null) return;
        // 仅主进程建屏：channel/render 进程无需保活，避免多进程各挂一块屏的内存电耗
        try {
            String pn = Application.getProcessName();
            if (pn != null && pn.indexOf(':') >= 0) {
                log("skip non-main proc: " + pn);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            DisplayManager dmi = (DisplayManager) appCtx.getSystemService(Context.DISPLAY_SERVICE);
            if (dmi == null) {
                log("no DisplayManager");
                return;
            }
            // 1x1 空转 surface：ImageReader 公开 API，无帧即无渲染开销；静态持有防 GC 回收
            READER = ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, 1);
            SURF = READER.getSurface();
            // 私有 presentation 屏：不带 PUBLIC，无投屏图标、不进系统 UI
            VD = dmi.createVirtualDisplay("rh_ka", 1, 1, 160, SURF,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null, null);
            if (VD == null) {
                log("createVirtualDisplay null");
                cleanupRefs();
                return;
            }
            Display disp = VD.getDisplay();
            if (disp == null) {
                log("display null");
                cleanupRefs();
                return;
            }
            PRES = new Presentation(appCtx, disp);
            PRES.show();
            STARTED = true;   // 成功才标记；失败留给 onResume 对齐重试
            log("presentation shown on display " + disp.getDisplayId()
                    + " flags=0x" + Integer.toHexString(disp.getFlags()));
        } catch (Throwable t) {
            cleanupRefs();
            log("start fail: " + t);
        }
    }

    private static void doStop() {
        try {
            if (PRES != null) PRES.dismiss();
        } catch (Throwable ignored) {
        }
        try {
            if (VD != null) VD.release();
        } catch (Throwable ignored) {
        }
        try {
            if (SURF != null) SURF.release();
        } catch (Throwable ignored) {
        }
        try {
            if (READER != null) READER.close();
        } catch (Throwable ignored) {
        }
        cleanupRefs();
        STARTED = false;
        log("keepalive stopped, window released");
    }

    private static void cleanupRefs() {
        PRES = null;
        VD = null;
        SURF = null;
        READER = null;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        RhFileLog.log("[" + TAG + "] " + msg);
    }
}
