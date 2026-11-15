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

    /** 响应文件基名：api 全名 CRC32 短哈希（8 位 hex）。
     *  备忘录 2026-11-13 方案 A：hook 写侧与引擎读侧共用同一规则，替换原「api 末段」命名——
     *  原 rh_resp_<末段> 命名下 detail.query/center.query/channel.query/point.withdraw.query/
     *  ad.query 五组尾段撞车（跨引擎串包实证），全名哈希后一 api 一文件，互不可见。 */
    public static String respBase(String api) {
        String a = api == null ? "unknown" : api;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(a.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    /** api 级互斥锁（备忘录方案 B）：同 api 串行——oliver.show 多活动（104042/102178）同 api
     *  不同参数时，跨引擎各持同名锁排队，先发的先收，杜绝读串包；不同 api 完全并行。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> API_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    public static Object lockFor(String api) {
        String a = api == null ? "unknown" : api;
        Object lock = API_LOCKS.get(a);
        if (lock != null) return lock;
        Object fresh = new Object();
        Object old = API_LOCKS.putIfAbsent(a, fresh);
        return old != null ? old : fresh;
    }
}
