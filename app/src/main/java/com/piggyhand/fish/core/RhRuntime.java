package com.piggyhand.fish.core;

/**
 * 模块运行时基建唯一来源：宿主上下文 + 落盘目录。
 * Phase 2a 依赖倒置（2026-11-15）：core/task 一律依赖本类，不再引 hook 包——
 * FishMtopHook 在 Application attach 最早可用点写入 appCtx（单写点，无漂移）。
 */
public final class RhRuntime {

    /** 模块运行所在 App 的 context（Application attach 时最早赋值） */
    public static volatile android.content.Context appCtx;

    /** 引擎就绪标记（原 FishMtopHook.ENGINE_LISTENING，Phase 2b 移入 core——
     *  task 读就绪状态改走本字段，不再引 hook 包）。FishMtopHook 单写点置位。 */
    public static volatile boolean engineListening = false;

    private RhRuntime() {
    }

    /** 宿主 cache 目录（优先外部 cache）；任务响应与临时文件落盘用 */
    public static java.io.File cacheDir() {
        java.io.File f;
        android.content.Context c = appCtx;
        if (c == null) return null;
        try {
            f = c.getExternalCacheDir();
            if (f != null && f.exists()) return f;
        } catch (Throwable ignored) {
        }
        try {
            f = c.getCacheDir();
            if (f == null) return null;
            if (!f.exists()) f.mkdirs();
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 持久目录（外部 files，退内部 files）。
     *  任务状态与报告都落这里：外部 cache 会随装包/清缓存整目录消失
     *  （v1.2.9 实测：一次 pm install -r 后 rh_cash_claim.txt 就没了，
     *  面板状态从「今日已开红包」退回「今日未执行」）。 */
    public static java.io.File persistDir(android.content.Context ctx) {
        android.content.Context c = (ctx != null) ? ctx : appCtx;
        if (c == null) return null;
        try {
            java.io.File f = c.getExternalFilesDir(null);
            if (f == null) f = c.getFilesDir();
            if (f != null && !f.exists()) f.mkdirs();
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
