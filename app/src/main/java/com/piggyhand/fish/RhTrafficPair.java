package com.piggyhand.fish;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1a 流量配对（v1.0.8 合入，源自支线实战验证）：
 * - 请求侧：MtopBuilder.createMtopContext 之后登记（此时 api 已是最终值，含被 hijack 借壳改写后的；
 *   实测 ApiDirect 直调同样经过 createMtopContext，配对覆盖率 100%）
 *   以 api 为键挂 pending 队列（同 api 并发极低，队列兜底乱序）。
 * - 响应侧：MtopFinishEvent 构造时同 api 出队配对，输出 elapsed 与状态。
 * - 状态：MATCHED（请求+响应闭环）/ UNMATCHED（响应无对应请求：跨进程发起或 TTL 超时）/ TIMEOUT。
 * - TTL 60s：超时请求按 TIMEOUT 从队头清理，防内存驻留。
 * - 日志体量：req/resp 摘要各截 160 字符；全量响应仍走 rh_resp 落盘，互不替代。
 * 全部静默容错：任何异常只影响本条日志，不影响主线 hook。
 */
public final class RhTrafficPair {

    private static final long TTL_MS = 60_000L;
    private static final int MAX_PER_API = 8;
    private static final int SUM_LEN = 160;

    private static final class Req {
        final long t0;
        final String dataSum;
        Req(long t0, String dataSum) { this.t0 = t0; this.dataSum = dataSum; }
    }

    /** api -> pending 请求队列（FIFO） */
    private static final Map<String, Deque<Req>> PENDING =
            new ConcurrentHashMap<String, Deque<Req>>();

    private RhTrafficPair() {}

    /** 请求侧登记（createMtopContext 之后调用；api/data 均为最终发出值） */
    public static void onRequest(String api, String data) {
        if (api == null || api.length() == 0) return;
        try {
            String key = api.toLowerCase(Locale.US);
            Deque<Req> q = PENDING.get(key);
            if (q == null) {
                q = new ArrayDeque<Req>();
                Deque<Req> prev = PENDING.putIfAbsent(key, q);
                if (prev != null) q = prev;
            }
            synchronized (q) {
                q.addLast(new Req(System.currentTimeMillis(),
                        data == null ? "" : summarize(data)));
                while (q.size() > MAX_PER_API) q.removeFirst();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 响应侧配对（MtopFinishEvent 构造之后调用）。返回日志行，由调用方决定落盘方式；
     * 返回 null = 该响应不参与（api 为空等）。
     */
    public static String onResponse(String api, String respBody) {
        if (api == null || api.length() == 0) return null;
        try {
            String key = api.toLowerCase(Locale.US);
            long t1 = System.currentTimeMillis();
            Req r = null;
            Deque<Req> q = PENDING.get(key);
            if (q != null) {
                synchronized (q) {
                    // 先清理队头过期项（TIMEOUT），再取最近的 pending
                    while (!q.isEmpty() && t1 - q.peek().t0 > TTL_MS) {
                        Req dead = q.poll();
                        logLine("[pair] TIMEOUT api=" + key
                                + " waited=" + (t1 - dead.t0) + "ms");
                    }
                    r = q.poll();
                }
            }
            if (r != null) {
                return "[pair] MATCHED api=" + key
                        + " elapsed=" + (t1 - r.t0) + "ms"
                        + " req=" + r.dataSum
                        + " resp=" + summarize(respBody);
            }
            return "[pair] UNMATCHED api=" + key
                    + " resp=" + summarize(respBody)
                    + " (无 pending：跨进程发起 / TTL 超时)";
        } catch (Throwable t) {
            return "[pair] pair error: " + t;
        }
    }

    private static void logLine(String s) {
        try {
            de.robv.android.xposed.XposedBridge.log("[RewardHook] " + s);
            RhFileLog.write(s);
        } catch (Throwable ignored) {
        }
    }

    private static String summarize(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() > SUM_LEN ? one.substring(0, SUM_LEN) + "...(len=" + one.length() + ")" : one;
    }
}
