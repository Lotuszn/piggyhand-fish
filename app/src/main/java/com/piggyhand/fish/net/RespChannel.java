package com.piggyhand.fish.net;

import com.piggyhand.fish.core.RhFileLog;

import de.robv.android.xposed.XposedBridge;

/**
 * net 层响应事件路由（Phase 2a-2 依赖倒置 2026-11-15）。
 * 静态持有 hook 层注册的 RespSink 实现；未注册时静默丢弃并只告警一次——
 * 正常时序下 FishMtopHook.hook() 先于任何直调执行，此分支仅为防御。
 */
public final class RespChannel {

    private static volatile RespSink sink;
    private static volatile boolean warnedNoSink = false;

    private RespChannel() {
    }

    /** hook 层启动时注册（FishMtopHook.hook() 开头调用，进程内一次） */
    public static void install(RespSink s) {
        sink = s;
    }

    /** 候选响应喂给 hook 层响应通道（MtopResponse 校验在实现侧兜底） */
    public static void feed(Object resp, String api) {
        RespSink s = sink;
        if (s != null) {
            s.feedRespCandidate(resp, api);
            return;
        }
        if (!warnedNoSink) {
            warnedNoSink = true;
            XposedBridge.log("[RewardHook] [resp-channel] sink not installed, drop: " + api);
            RhFileLog.write("[resp-channel] sink not installed, drop: " + api);
        }
    }

    /** 直调 send 前登记（hook 层兼容版自检用） */
    public static void noteSent(String api) {
        RespSink s = sink;
        if (s != null) {
            s.noteDirectSent(api);
        }
    }
}
