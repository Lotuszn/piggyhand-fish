package com.piggyhand.fish.net;

import com.piggyhand.fish.core.RhFileLog;

/**
 * P0-C 时序门（通道就绪信号）：
 * Android16 特定机型（23113RKC6C / 23116PN5BC / PJA110 三证）冷启动首轮跑批撞进
 * mtop 通道未就绪窗口，固定 40~50s 延迟后直调请求全灭（-205/-405 连接层断）。
 * Android17 反证（2509FPN0BC）：同症状 constructor hook DEAD 但首轮即成功——
 * 证明问题不是 API 级别，而是「发首批时通道是否已被宿主流量证实存活」。
 *
 * 门语义（版本无关，能探测不查版本）：
 *  - noteSuccess()：宿主/模块任一 MATCHED 响应体含 SUCCESS 时记录首个成功时刻；
 *  - awaitChannel(base, extra)：先睡满 base（保持既有波次节奏，正常用户时序逐秒不变），
 *    base 到点时若通道已被证实存活 → 立即放行（正常用户永远走这条路，零感知）；
 *    未证实 → 顺延等待首个成功 MATCHED，最多 extra 封顶；
 *    封顶仍未见到（配对层整体失效等极端场景）→ 放行，行为等同旧版固定延迟，不会更差。
 *
 * 信号源挂 RhTrafficPair.onResponse（MATCHED 分支），静默容错，任何异常不影响主线。
 */
public final class RhChGate {

    /** 首个「成功响应配对」时刻（epoch ms），0 = 尚未见到 */
    private static volatile long firstOkAt = 0L;

    private RhChGate() {}

    /** MATCHED 且响应体含 SUCCESS 时调用（幂等，只记首个）；由 RhTrafficPair 内部调用 */
    static void noteSuccess(String respBody) {
        if (firstOkAt != 0L) return;
        if (respBody == null || !respBody.contains("SUCCESS")) return;
        firstOkAt = System.currentTimeMillis();
    }

    /** 通道是否已被一次成功响应证实存活 */
    public static boolean seen() {
        return firstOkAt != 0L;
    }

    /**
     * 引擎首轮等待：保底 base 毫秒（与旧固定延迟一致），之后若通道未证实存活则
     * 顺延等待首个成功 MATCHED，最多 extra 毫秒。线程中断立即返回（不吞中断语义）。
     */
    public static void awaitChannel(long baseMs, long extraMs) {
        long t0 = System.currentTimeMillis();
        long baseEnd = t0 + baseMs;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= baseEnd) break;
            try {
                Thread.sleep(Math.min(500L, baseEnd - now));
            } catch (InterruptedException e) {
                return; // 中断：交回调用方，不阻塞引擎线程退出
            }
        }
        if (seen()) return; // 正常用户路径：base 内必有成功流量，行为与旧版逐秒一致

        // 通道未证实：顺延等待（每引擎只打一次日志，便于日志侧归因）
        try {
            RhFileLog.write("[gate] channel not proven after " + (baseMs / 1000L)
                    + "s, extending wait for first SUCCESS-MATCHED (cap +" + (extraMs / 1000L) + "s)");
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + extraMs;
        while (!seen()) {
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                try {
                    RhFileLog.write("[gate] cap reached without SUCCESS-MATCHED, proceeding (legacy behavior)");
                } catch (Throwable ignored) {
                }
                return; // 封顶放行 = 旧版行为，不会更差
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                return;
            }
        }
        try {
            RhFileLog.write("[gate] channel proven at +"
                    + ((firstOkAt - t0) / 1000L) + "s, proceeding");
        } catch (Throwable ignored) {
        }
    }
}
