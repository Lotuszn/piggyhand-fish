package com.piggyhand.fish.core;

/**
 * 日志脱敏（2026-11-26）。
 *
 * 背景：mtop 请求体与 header 里带会话凭证（asac / fromToken / token / cookie / sid），
 * 这些行原来原样进 logcat 与 rh_file.log —— 等于把凭据落盘。发行审计对"凭据未 mask 写日志"
 * 按 WARN 处理，这里统一收口。
 *
 * 只做一件小事：把 JSON 里指定键的值压成 "abcd***wxyz"，其余字节原样保留。
 * 复杂度 O(n·k)，n = 文本长度，k = 键数（7），单次几 KB 的请求体无压力。
 */
public final class RhSecret {

    private RhSecret() {}

    /** 需要脱敏的 JSON 键（写成带引号的完整前缀，避免 "asacXxx" 误命中） */
    private static final String[] KEYS = {
            "\"asac\":\"",
            "\"fromToken\":\"",
            "\"user_token\":\"",
            "\"token\":\"",
            "\"cookie\":\"",
            "\"session\":\"",
            "\"sid\":\""
    };

    /**
     * 把每个敏感键的值替换成掩码版本。
     * 值长度 <= 8 时整段打码，避免"4+4"把短值原样吐出。
     */
    public static String maskJson(String s) {
        if (s == null || s.length() < 8) return s;
        // 快扫：一个都没有就直接返回原串，高频路径零额外分配
        boolean hit = false;
        for (int i = 0; i < KEYS.length; i++) {
            if (s.contains(KEYS[i])) {
                hit = true;
                break;
            }
        }
        if (!hit) return s;

        String out = s;
        for (int k = 0; k < KEYS.length; k++) {
            String key = KEYS[k];
            int from = 0;
            while (true) {
                int i = out.indexOf(key, from);
                if (i < 0) break;
                int vs = i + key.length();
                int ve = out.indexOf('"', vs);
                if (ve < 0) break; // 值被截断，保持原样（不猜）
                String v = out.substring(vs, ve);
                if (v.length() == 0 || v.startsWith("***")) {
                    from = ve + 1;
                    continue;
                }
                String masked = maskValue(v);
                out = out.substring(0, vs) + masked + out.substring(ve);
                from = vs + masked.length() + 1;
            }
        }
        return out;
    }

    /** 单值掩码：>8 留首尾各 4，<=8 全打码 */
    public static String maskValue(String v) {
        if (v == null) return "null";
        int n = v.length();
        if (n == 0) return "";
        if (n <= 8) {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append('*');
            return sb.toString();
        }
        return v.substring(0, 4) + "***" + v.substring(n - 4);
    }
}
