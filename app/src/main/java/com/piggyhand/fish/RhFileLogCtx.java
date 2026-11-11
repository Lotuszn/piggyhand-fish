package com.piggyhand.fish;

import android.content.Context;

/**
 * v1.4.4 上下文访问器：给 ApiProtoDump 等无状态落盘类提供 Application 上下文。
 * 单一来源 = FishMtopHook.appCtx（Application attach 时最早赋值）。
 */
public final class RhFileLogCtx {

    private RhFileLogCtx() {
    }

    public static Context get() {
        return FishMtopHook.appCtx;
    }
}
