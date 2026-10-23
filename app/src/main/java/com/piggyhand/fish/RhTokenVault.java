package com.piggyhand.fish;

import org.json.JSONObject;

/**
 * P1b token 捞取（v1.0.8 合入，源自支线实战验证）：
 * 风控 token 不用算，从正常业务流量里捞现成的。
 * - 扫描对象：MtopFinishEvent 响应体 + createMtopContext 请求数据（有些 token 在请求参数里）
 * - 目标 key 名单（闲鱼风控族，大小写不敏感、递归 JSON 深度≤4）：
 *     x5sec / wua / riskToken / referToken / umt / x-sign / apdToken / punbid / xminiWua / ieToken
 *     / fromToken（10-19 实测补充：getolivertaskbenefit 等广告任务接口的跳转凭证，高频出现，
 *     请求侧与响应侧双向捕获）
 * - 存储：闲鱼 files/zhushou_tokens.json，结构 {api: {key: {v: value, at: 捕获时间, side: req|resp}}}
 *   值变才落盘（5s 节流防 fromToken 类高频变化写盘风暴）；值截 240 字符防超大 token 灌爆文件。
 * - 日志：首次捕获或值变化时打 [token] 行；同值静默。
 * - 用途：为将来涉风控接口（宝藏计划/鱼塘类）准备凭证缓存；当前只收集观察，不参与请求。
 */
public final class RhTokenVault {

    private static final String FILE_NAME = "zhushou_tokens.json";
    private static final int MAX_VAL_LEN = 240;
    private static final int MAX_DEPTH = 4;
    private static final String[] KEYS = {
            "x5sec", "wua", "riskToken", "referToken", "umt",
            "x-sign", "apdToken", "punbid", "xminiWua", "ieToken",
            "fromToken" // 10-19 支线实测补充：广告任务链路跳转凭证，req/resp 双向捕获
    };

    /** api -> (key -> value) 内存态：与文件互为镜像，判「值变」用 */
    private static final java.util.Map<String, JSONObject> CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, JSONObject>();
    private static volatile android.content.Context appCtx;
    private static volatile boolean loaded = false;

    private RhTokenVault() {}

    /** 上下文注入（registerInvoker 处调用一次） */
    public static void init(android.content.Context ctx) {
        if (ctx == null) return;
        android.content.Context ac = ctx.getApplicationContext();
        if (ac == null) ac = ctx;
        if (appCtx == null) appCtx = ac;
        ensureLoaded();
    }

    /** 响应侧扫描：命中即落盘 + 日志 */
    public static void scanResponse(String api, String respBody) {
        scan(api, respBody, "resp");
    }

    /** 请求侧扫描（createMtopContext 的 data） */
    public static void scanRequest(String api, String data) {
        scan(api, data, "req");
    }

    private static void scan(String api, String body, String side) {
        if (api == null || body == null || body.length() == 0) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject hits = new JSONObject();
            collect(root, 0, hits);
            if (hits.length() == 0) return;
            String a = api.toLowerCase(java.util.Locale.US);
            JSONObject apiObj = CACHE.get(a);
            if (apiObj == null) {
                apiObj = new JSONObject();
                JSONObject prev = CACHE.putIfAbsent(a, apiObj);
                if (prev != null) apiObj = prev;
            }
            java.util.Iterator<String> it = hits.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = hits.optString(k);
                String old = apiObj.has(k) ? apiObj.optJSONObject(k).optString("v") : null;
                if (v.equals(old)) continue; // 同值静默
                JSONObject rec = new JSONObject();
                rec.put("v", v.length() > MAX_VAL_LEN ? v.substring(0, MAX_VAL_LEN) + "..." : v);
                rec.put("at", System.currentTimeMillis());
                rec.put("side", side);
                apiObj.put(k, rec);
                RhFileLog.write("[token] " + side + " api=" + a + " key=" + k
                        + " val=" + (v.length() > 40 ? v.substring(0, 40) + "..." : v));
            }
            persist();
        } catch (Throwable ignored) {
            // 不是合法 JSON / 扫描失败：静默，不影响主流程
        }
    }

    /** 递归收集命中 key（JSONObject + JSONArray 双路，深度限制防爆栈） */
    private static void collect(Object node, int depth, JSONObject out) {
        if (node == null || depth > MAX_DEPTH) return;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.opt(k);
                    for (String target : KEYS) {
                        if (k.equalsIgnoreCase(target)) {
                            String vs = v == null ? "" : String.valueOf(v);
                            if (vs.length() > 0 && vs.length() <= 4096) out.put(target, vs);
                            break;
                        }
                    }
                    collect(v, depth + 1, out);
                }
            } else if (node instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) node;
                for (int i = 0; i < arr.length() && i < 32; i++) {
                    collect(arr.opt(i), depth + 1, out);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (RhTokenVault.class) {
            if (loaded) return;
            loaded = true;
            try {
                android.content.Context c = appCtx;
                if (c == null) return;
                java.io.File dir = c.getExternalFilesDir(null);
                if (dir == null) return;
                java.io.File f = new java.io.File(dir, FILE_NAME);
                if (!f.exists()) return;
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(b);
                in.close();
                JSONObject root = new JSONObject(new String(b, "UTF-8"));
                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String api = it.next();
                    CACHE.put(api, root.optJSONObject(api));
                }
                RhFileLog.write("[token] vault loaded: " + CACHE.size() + " api entries");
            } catch (Throwable ignored) {
            }
        }
    }

    /** persist 节流：fromToken 类高频变化字段会导致每秒级重写，5s 内只落盘一次（值变时延迟补写） */
    private static volatile long lastPersistAt = 0L;
    private static final Object PERSIST_LOCK = new Object();

    private static void persist() {
        synchronized (PERSIST_LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastPersistAt < 5000L) return; // 节流：内存 CACHE 全量镜像，下次值变时补写
            lastPersistAt = now;
        }
        try {
            android.content.Context c = appCtx;
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            String dirPath = dir != null ? dir.getAbsolutePath()
                    : ConfigManager.fishExternal("files");
            java.io.File dirF = new java.io.File(dirPath);
            if (!dirF.exists()) dirF.mkdirs();
            JSONObject root = new JSONObject();
            for (java.util.Map.Entry<String, JSONObject> e : CACHE.entrySet()) {
                try {
                    root.put(e.getKey(), e.getValue());
                } catch (Throwable ignored) {
                }
            }
            // 原子写：临时文件 + rename
            java.io.File tmp = new java.io.File(dirF, FILE_NAME + ".tmp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp, false);
            fo.write(root.toString().getBytes("UTF-8"));
            fo.close();
            java.io.File dst = new java.io.File(dirF, FILE_NAME);
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } catch (Throwable ignored) {
        }
    }
}
