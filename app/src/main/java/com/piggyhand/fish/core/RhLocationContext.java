package com.piggyhand.fish.core;

import com.piggyhand.fish.core.RhRuntime; // 同包显式引用（原 hook 依赖已倒置）

import android.content.Context;
import de.robv.android.xposed.XposedBridge;

/**
 * 本机位置/凭证上下文（1.1.2）：从闲鱼原生请求流量里捕获用户自己的
 * fishAdCode / gps / asac，供引擎全部请求动态取用。
 *
 * 设计原则：
 *  - 用户授权过闲鱼定位，闲鱼前台自产的请求带着用户真实位置——我们只抄写，
 *    不自己调系统定位（避免权限/弹窗/来源特征不一致）
 *  - 取不到 → 返回 null → 调用方省略字段（服务端对缺字段请求照常服务，
 *    1.1.1 用户4 实测 req={"version":4} map.init SUCCESS）；绝不用任何固定值冒充
 *  - 持久化到应用内部 files/rh_loc.json，冷启动后仍有值
 *
 * 1.5.74 加固：原先写在 getExternalCacheDir()（/sdcard/Android/data/<pkg>/cache），
 * 该目录在部分 ROM 上对同组应用/文件管理器可读，asac 明文落在那里属于凭据外泄面。
 * 现改到内部私有目录；旧文件在首次读取时迁移一次并删除，迁移失败则保留可读路径继续工作。
 */
public final class RhLocationContext {

    private RhLocationContext() {}

    private static volatile String fishAdCode;
    private static volatile String gps;
    private static volatile String asac;
    private static volatile boolean loaded = false;
    private static final Object LOAD_LOCK = new Object();

    /** 请求体 JSON 快扫：先 indexOf 命中键再解析（高频路径零开销） */
    public static void capture(String dataJson) {
        if (dataJson == null || dataJson.length() < 20) return;
        boolean hasFa = dataJson.contains("\"fishAdCode\"");
        boolean hasGps = dataJson.contains("\"gps\"");
        boolean hasAsac = dataJson.contains("\"asac\"");
        if (!hasFa && !hasGps && !hasAsac) return;
        try {
            org.json.JSONObject j = new org.json.JSONObject(dataJson);
            boolean changed = false;
            if (hasFa) {
                String v = clean(j.optString("fishAdCode", null));
                if (v != null && !v.equals(fishAdCode)) { fishAdCode = v; changed = true; }
            }
            if (hasGps) {
                String v = clean(j.optString("gps", null));
                if (v != null && !v.equals(gps)) { gps = v; changed = true; }
            }
            if (hasAsac) {
                String v = clean(j.optString("asac", null));
                if (v != null && !v.equals(asac)) { asac = v; changed = true; }
            }
            if (changed) {
                XposedBridge.log("[RewardHook] [loc] captured fishAdCode=" + fishAdCode
                        + " gps=" + gps + " asac=" + mask(asac));
                persist();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 本机捕获的城市码；无捕获返回 null（调用方省略字段） */
    public static String fishAdCode() { ensureLoaded(); return fishAdCode; }

    /** 本机捕获的定位串 "lat,lng"；无捕获返回 null */
    public static String gps() { ensureLoaded(); return gps; }

    /** 本机捕获的会话凭证；无捕获返回 null */
    public static String asac() { ensureLoaded(); return asac; }

    /** 字符串拼装用：返回 "\"fishAdCode\":\"..\",\"gps\":\"..\","（结尾带逗号）或 "" */
    public static String jsonPair() {
        String fa = fishAdCode(), gp = gps();
        StringBuilder sb = new StringBuilder();
        if (fa != null) sb.append("\"fishAdCode\":\"").append(fa).append("\",");
        if (gp != null) sb.append("\"gps\":\"").append(gp).append("\",");
        return sb.toString();
    }

    /**
     * 完整 JSON 对象体："{"fishAdCode":"..","gps":".."}"（无尾逗号），两值都缺时 "{}"。
     * v1.5.1 修复：MI9 日志实证 cycle.challenge.info 请求体 "{" + jsonPair() + "}"
     * 因 jsonPair 尾逗号直接接 } 产生非法 JSON（Expected literal value at char 51），
     * 纯 pair 收尾的调用点一律改用本方法。
     */
    public static String jsonPairObj() {
        String p = jsonPair();
        if (p.endsWith(",")) p = p.substring(0, p.length() - 1);
        return "{" + p + "}";
    }

    private static String clean(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.length() == 0 || "null".equalsIgnoreCase(v)) return null;
        return v;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOAD_LOCK) {
            if (loaded) return;
            try {
                Context c = RhRuntime.appCtx;
                if (c != null) {
                    java.io.File f = privFile(c);
                    boolean migrated = false;
                    if (!f.exists()) {
                        // 旧版本写在外部 cache，读一次当兜底：老用户升级后不用重新捕获
                        java.io.File old = legacyFile(c);
                        if (old.exists()) {
                            f = old;
                            migrated = true;
                        }
                    }
                    if (f.exists()) {
                        byte[] b = new byte[(int) f.length()];
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        in.read(b);
                        in.close();
                        org.json.JSONObject j = new org.json.JSONObject(new String(b, "UTF-8"));
                        String v = clean(j.optString("fishAdCode", null));
                        if (v != null) fishAdCode = v;
                        v = clean(j.optString("gps", null));
                        if (v != null) gps = v;
                        v = clean(j.optString("asac", null));
                        if (v != null) asac = v;
                        XposedBridge.log("[RewardHook] [loc] loaded fishAdCode=" + fishAdCode
                                + " gps=" + gps + " asac=" + mask(asac));
                        // 从外部迁来的：写回内部私有目录，成功后删外部文件（删不掉不影响功能）
                        if (migrated) persist();
                    }
                }
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    /** 内部私有目录（app data/files，非 root 不可读） */
    private static java.io.File privFile(Context c) {
        java.io.File dir = c.getFilesDir();
        if (dir == null) dir = c.getDir("rh", Context.MODE_PRIVATE);
        return new java.io.File(dir, "rh_loc.json");
    }

    /** 1.5.74 之前的位置：外部 cache，升级后只读一次 */
    private static java.io.File legacyFile(Context c) {
        java.io.File d = c.getExternalCacheDir();
        return d == null ? null : new java.io.File(d, "rh_loc.json");
    }

    private static void persist() {
        try {
            Context c = RhRuntime.appCtx;
            if (c == null) return;
            org.json.JSONObject j = new org.json.JSONObject();
            if (fishAdCode != null) j.put("fishAdCode", fishAdCode);
            if (gps != null) j.put("gps", gps);
            if (asac != null) j.put("asac", asac);
            java.io.File f = privFile(c);
            // 原子写：先落 .tmp 再 rename，避免进程被杀时留下半个 JSON（下次读取解析失败）
            java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp, false);
            w.write(j.toString());
            w.flush();
            w.close();
            if (!tmp.renameTo(f)) {
                // rename 失败（个别 ROM 跨挂载点）：退回覆盖写，至少保证有值
                java.io.FileWriter w2 = new java.io.FileWriter(f, false);
                w2.write(j.toString());
                w2.flush();
                w2.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            java.io.File old = legacyFile(c);
            if (old != null && old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete(); // 明文 asac 不再留在外部存储
            }
        } catch (Throwable ignored) {
        }
    }

    private static String mask(String s) {
        if (s == null) return "null";
        return s.length() <= 8 ? s : s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
