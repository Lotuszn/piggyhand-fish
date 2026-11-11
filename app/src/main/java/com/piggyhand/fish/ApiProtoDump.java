package com.piggyhand.fish;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v1.4.4 ApiProtocol 万能网关全量捕获（7.29.x 适配）：
 * 闲鱼 7.29.10 起页面业务请求改走 com.taobao.idlefish.protocol.net.api.ApiProtocol
 * 聚合网关：mtop api 名固定，真实路由编码在请求体/上下文里，老的
 * mtop.taobao.idle.* 前缀落盘分支全部漏掉。本类把 ApiProtocol 请求的
 * MtopContext 深度字段 dump + 响应全文，逐行 JSON 追加到
 * external files/rh_apiproto.jsonl，16MB 轮转。
 * 全部静默容错：任何异常只丢本条，不影响 hook 主线。
 */
public final class ApiProtoDump {

    private static final Object LOCK = new Object();
    private static final long ROTATE_BYTES = 16L * 1024 * 1024;
    private static final String FNAME = "rh_apiproto.jsonl";
    /** 同一 MtopResponse 实例 3s 内只落一次（构造 hook 与方法级 fallback 会喂两遍） */
    private static final java.util.WeakHashMap<Object, Long> SEEN = new java.util.WeakHashMap<Object, Long>();

    private ApiProtoDump() {
    }

    /** 请求侧：MtopContext 深度 dump（一层字段 + mtopRequest 二层 + property 三层） */
    public static void dumpCtx(Object ctx) {
        if (ctx == null) return;
        try {
            StringBuilder sb = new StringBuilder(2048);
            sb.append('{');
            ts(sb);
            sb.append("\"dir\":\"REQ\"");
            deepFields(sb, ctx, "ctx", 0);
            sb.append('}');
            append(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 响应侧：ApiProtocol 响应全文（resp 用于 3s 引用级去重） */
    public static void dumpResp(Object resp, String body) {
        if (body == null || body.length() == 0) return;
        try {
            if (resp != null) {
                synchronized (SEEN) {
                    Long last = SEEN.get(resp);
                    long now = System.currentTimeMillis();
                    if (last != null && now - last < 3000L) return;
                    SEEN.put(resp, now);
                    if (SEEN.size() > 128) SEEN.clear(); // 防泄漏兜底
                }
            }
            StringBuilder sb = new StringBuilder(4096);
            sb.append('{');
            ts(sb);
            sb.append("\"dir\":\"RESP\"");
            sb.append(",\"body\":").append(jsonStr(body));
            sb.append('}');
            append(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void ts(StringBuilder sb) {
        sb.append("\"t\":\"").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date())).append("\",\"tid\":").append(Thread.currentThread().getId())
                .append(','); // 尾逗号补齐：调用方后续键直接拼接
    }

    /** 深度字段遍历：obj 一层全字段；字段值若为对象再下钻一层（mtopRequest/property） */
    private static void deepFields(StringBuilder sb, Object obj, String prefix, int depth) {
        if (obj == null || depth > 2) return;
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null || v instanceof String || v instanceof Number
                            || v instanceof Boolean || v instanceof Character) {
                        sb.append(",\"").append(prefix).append('.').append(f.getName()).append("\":")
                                .append(v == null ? "null" : jsonStr(String.valueOf(v)));
                    } else if (v instanceof byte[]) {
                        byte[] b = (byte[]) v;
                        sb.append(",\"").append(prefix).append('.').append(f.getName())
                                .append("\":\"bytes(").append(b.length).append(")\"");
                    } else {
                        String vn = v.getClass().getName();
                        // 下钻关键子对象：mtopRequest / property / mtopListener 不钻（listener 有环）
                        if (depth < 1 && !vn.contains("Listener") && !vn.contains("Callback")
                                && !vn.contains("Context") && !vn.contains("Handler")
                                && !vn.contains("thread")) {
                            deepFields(sb, v, prefix + "." + f.getName(), depth + 1);
                        } else {
                            sb.append(",\"").append(prefix).append('.').append(f.getName())
                                    .append("\":\"").append(vn).append("@")
                                    .append(Integer.toHexString(System.identityHashCode(v))).append('"');
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void append(String line) {
        synchronized (LOCK) {
            java.io.FileOutputStream fos = null;
            try {
                android.content.Context ctx = RhFileLogCtx.get();
                if (ctx == null) return;
                java.io.File dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = new java.io.File(ConfigManager.fishExternal("files"));
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, FNAME);
                if (f.length() > ROTATE_BYTES) {
                    java.io.File old = new java.io.File(dir, FNAME + ".1");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                fos = new java.io.FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.write('\n');
                fos.flush();
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
