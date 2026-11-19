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

    /* 场景级索引（P1-E，2026-11-19）：GAME_CHANNEL 清单里部分任务项（1047278691 / 1047276220 实测）
     * 自身不带 appkey，gameId 也对不上，前四级兜底全空。渠道上的这批任务共用同一个 appkey
     * （实测 35334609），所以按 taskScene 聚合一层。
     * 安全阀：同一场景一旦出现第二个不同 appkey，说明该场景 appkey 是按游戏区分的，
     * 此时 SCENE_MULTI 置位、byScene 永久返回空 —— 宁可省略字段（等同旧版），
     * 也不给服务端递一个错的 appkey 把拒绝原因从 MISSED 变成 NOT_MATCH。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> SCENE_AK =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final java.util.Set<String> SCENE_MULTI =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /* 渠道批次兜底（P1-E 主数据源，2026-11-19）：channel.query 清单里那批任务项自己不带 appkey，
     * 响应树里也没有可收割的同节点。但它们跟其他任务同属一批清单，这批清单里带 appkey 的任务
     * 绝大多数共用同一个值（11-19 实测 35334609）。所以按批次做频次统计：
     * 带 appkey 的样本 >= 3 且最高频值占比 >= 80% 才记，否则视为「按游戏区分」，不兜底。 */
    private static volatile String CHANNEL_AK = "";

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

    /** 单节点索引：appkey 非空时挂 taskId / gameId(id) / taskScene 三个键 */
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
            // 场景级：同一节点既带 appkey 又带 taskScene/scene 才记
            String sc = o.optString("taskScene", "");
            if (sc.length() == 0) sc = o.optString("scene", "");
            putScene(sc, ak);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 场景级收割。首次见到某场景的 appkey 记为唯一候选；再见到不同值即标记为「不唯一」，
     * 此后该场景不再兜底（byScene 返回空）。同一个值重复出现不算冲突。
     */
    private static void putScene(String scene, String ak) {
        try {
            if (scene == null || scene.length() == 0 || ak == null || ak.length() == 0) return;
            if (SCENE_MULTI.contains(scene)) return;
            String prev = SCENE_AK.putIfAbsent(scene, ak);
            if (prev != null && !prev.equals(ak)) {
                SCENE_MULTI.add(scene);
                SCENE_AK.remove(scene);
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

    /** 按场景查询（末级兜底）；该场景 appkey 不唯一或未收录时返回空串 */
    public static String byScene(String scene) {
        try {
            if (scene == null || scene.length() == 0) return "";
            if (SCENE_MULTI.contains(scene)) return "";
            return SCENE_AK.getOrDefault(scene, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 渠道清单批次收割：统计本批任务里出现的 appkey 频次。
     * 规则——带 appkey 的样本 >= 3，且最高频值占比 >= 80% 才记为该渠道兜底值；
     * 占比不足说明渠道内 appkey 是按游戏区分的，此时不记，宁可省略字段。
     */
    public static void noteChannelTasks(org.json.JSONArray tasks) {
        try {
            if (tasks == null || tasks.length() == 0) return;
            java.util.HashMap<String, Integer> freq = new java.util.HashMap<String, Integer>();
            int withAk = 0;
            int n = Math.min(tasks.length(), 128);
            for (int i = 0; i < n; i++) {
                org.json.JSONObject t = tasks.optJSONObject(i);
                if (t == null) continue;
                org.json.JSONObject g = t.optJSONObject("game");
                String ak = g != null ? g.optString("appkey", "") : "";
                if (ak.length() == 0) ak = t.optString("appkey", "");
                if (ak.length() == 0) continue;
                withAk++;
                Integer c = freq.get(ak);
                freq.put(ak, c == null ? 1 : c + 1);
            }
            if (withAk < 3) return;
            String best = "";
            int bestN = 0;
            for (java.util.Map.Entry<String, Integer> e : freq.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            // 占比 < 80% → 该渠道 appkey 不是共用值，不兜底
            if (bestN * 10 < withAk * 8) {
                CHANNEL_AK = "";
                return;
            }
            CHANNEL_AK = best;
        } catch (Throwable ignored) {
        }
    }

    /** 渠道批次兜底 appkey；未达到统计门槛返回空串 */
    public static String channelAk() {
        return CHANNEL_AK == null ? "" : CHANNEL_AK;
    }

    /** 报告/调试用：当前索引规模 */
    public static String stat() {
        return "task=" + BY_TASK.size() + " game=" + BY_GAME.size()
                + " scene=" + SCENE_AK.size() + (SCENE_MULTI.isEmpty() ? "" : " scene_multi=" + SCENE_MULTI.size())
                + " channel=" + (channelAk().length() == 0 ? "-" : channelAk());
    }
}
