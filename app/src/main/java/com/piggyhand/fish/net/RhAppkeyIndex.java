package com.piggyhand.fish.net;

import org.json.JSONObject;

/**
 * P1-D appkey 动态索引（detail.query/complete 缺参修复的数据源）：
 * 2026-11-17 起服务端对 mtop.taobao.idle.mini.game.task.detail.query / task.complete
 * 强校验 appkey；channel.query 清单里部分任务项（1047278691 / 1047276220 系实测）
 * 自身不带 appkey 字段，导致该两步必报 FAIL_SYS_BIZPARAM_MISSED。
 *
 * 数据来源（全部动态捕获，不写死任何值——凭据类硬编码为零容忍）：
 *  1. 请求侧：宿主/模块每个 mini.game 请求体流经 RhTrafficPair.onRequest，
 *     body 形如 {"appkey":"35334609","taskId":"1047207205"}，按 taskId 精确索引；
 *  2. 响应侧：mini.game 响应 JSON（channel.query / center.query / detail.query）
 *     递归收割任何「appkey 与 taskId/gameId 同节点」的键值对。
 *
 * 查询链：byTask(taskId) → byGame(gameId/id) → 未命中返回空串（调用方省略字段，
 * 行为等同旧版，不会更差）。进程级缓存，条目封顶防膨胀，全程静默容错。
 */
public final class RhAppkeyIndex {

    /** 条目上限：单用户单会话游戏数有限，64 足够；溢出清空重来（宁可失联不可膨胀） */
    private static final int CAP = 64;

    private static final java.util.concurrent.ConcurrentHashMap<String, String> BY_TASK =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> BY_GAME =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    private RhAppkeyIndex() {}

    /** 请求体收割：api 含 mini.game 且 body 带 appkey 时按 taskId/gameId 入索引 */
    public static void noteBody(String api, String data) {
        try {
            if (api == null || data == null) return;
            if (!api.contains("mini.game")) return;
            if (!data.contains("appkey")) return;
            JSONObject o = new JSONObject(data);
            putFrom(o);
        } catch (Throwable ignored) {
        }
    }

    /** 响应树收割：递归遍历，任何「同节点含 appkey + taskId/gameId/id」的键值对入索引 */
    public static void harvestTree(JSONObject node) {
        try {
            walk(node, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void walk(JSONObject o, int depth) {
        if (o == null || depth > 6) return;
        putFrom(o);
        // 深度优先展开子节点；条目满后停止下钻（收益递减）
        if (BY_TASK.size() + BY_GAME.size() >= CAP * 2) return;
        java.util.Iterator<String> it = o.keys();
        while (it != null && it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                walk((JSONObject) v, depth + 1);
            } else if (v instanceof org.json.JSONArray) {
                org.json.JSONArray a = (org.json.JSONArray) v;
                int n = Math.min(a.length(), 64);
                for (int i = 0; i < n; i++) {
                    Object x = a.opt(i);
                    if (x instanceof JSONObject) walk((JSONObject) x, depth + 1);
                }
            }
        }
    }

    /** 单节点索引：appkey 非空时挂 taskId 与 gameId/id 两个键 */
    private static void putFrom(JSONObject o) {
        try {
            String ak = o.optString("appkey", "");
            if (ak == null || ak.length() == 0) return;
            String tid = o.optString("taskId", "");
            if (tid != null && tid.length() > 0 && BY_TASK.size() < CAP) {
                BY_TASK.put(tid, ak);
            }
            String gid = o.optString("gameId", "");
            if (gid == null || gid.length() == 0) gid = o.optString("id", "");
            if (gid != null && gid.length() > 0 && BY_GAME.size() < CAP) {
                BY_GAME.put(gid, ak);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按任务 ID 精确查询；未命中空串 */
    public static String byTask(String taskId) {
        try {
            return taskId == null ? "" : BY_TASK.getOrDefault(taskId, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 按游戏 ID 查询；未命中空串 */
    public static String byGame(String gameId) {
        try {
            return gameId == null ? "" : BY_GAME.getOrDefault(gameId, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 报告/调试用：当前索引规模 */
    public static String stat() {
        return "task=" + BY_TASK.size() + " game=" + BY_GAME.size();
    }
}
