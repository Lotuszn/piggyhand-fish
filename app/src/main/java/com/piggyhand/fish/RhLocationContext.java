package com.piggyhand.fish;

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
 *  - 持久化到闲鱼 cache 目录 rh_loc.json，冷启动后仍有值
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
                Context c = FishMtopHook.appCtx;
                if (c != null) {
                    java.io.File f = new java.io.File(c.getExternalCacheDir(), "rh_loc.json");
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
                    }
                }
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    private static void persist() {
        try {
            Context c = FishMtopHook.appCtx;
            if (c == null) return;
            org.json.JSONObject j = new org.json.JSONObject();
            if (fishAdCode != null) j.put("fishAdCode", fishAdCode);
            if (gps != null) j.put("gps", gps);
            if (asac != null) j.put("asac", asac);
            java.io.File f = new java.io.File(c.getExternalCacheDir(), "rh_loc.json");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(j.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String mask(String s) {
        if (s == null) return "null";
        return s.length() <= 8 ? s : s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
