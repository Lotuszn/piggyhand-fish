package com.piggyhand.fish;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import de.robv.android.xposed.XposedBridge;

/**
 * 失败归因 + 能力矩阵（COMPAT_RULES.md 规则 12）。
 *
 * 解决的问题：以前所有失败都只写一句"查询失败"，用户报日志时无法区分
 * 是接口改名、通路断了、参数不对，还是服务端风控——四种原因的处理方式完全不同，
 * 混在一起就只能靠猜。
 *
 * 归因码是决策依据，不是文案装饰：
 *   RISK         -> 只降频熔断，改参数无用（重试反而扩大风控窗口）
 *   PARAM        -> 换参数模板
 *   NOT_FOUNDED  -> 换接口名族
 *   NO_PATH      -> 查符号 / 查进程 / 查注册时机
 *   TIMEOUT      -> 查回调捕获与网络
 */
public final class Diag {

    private Diag() {}

    public static final String NOT_FOUNDED = "NOT_FOUNDED";
    public static final String NO_PATH = "NO_PATH";
    public static final String PARAM = "PARAM";
    public static final String RISK = "RISK";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * 关键词 -> 归因码。顺序即优先级：
     * RISK 放最前，因为"服务器繁忙"这类文案同时也出现在普通错误里，
     * 但它的处置方式最特殊（禁止重试），必须优先识别出来。
     */
    private static final String[][] TABLE = {
            {RISK, "RMB_CHECK"},
            {RISK, "服务器繁忙"},
            {RISK, "活动太火爆"},
            {RISK, "操作频繁"},
            {RISK, "访问频繁"},
            {NOT_FOUNDED, "API_NOT_FOUNDED"},
            {NOT_FOUNDED, "API_NOT_FOUND"},
            {PARAM, "PARAM_ILLEGAL"},
            {PARAM, "TASK_CONFIG_INVALID"},
            {PARAM, "ILLEGAL_ARGUMENT"},
            {PARAM, "参数"},
            {NO_PATH, "moduleForProtocol"},
            {NO_PATH, "NoSuchMethod"},
            {NO_PATH, "ClassNotFound"},
            {NO_PATH, "WRONG_PROC"},
            {NO_PATH, "NO_PATH"},
            {NO_PATH, "未注册"},
            {TIMEOUT, "无响应"},
            {TIMEOUT, "TIMEOUT"},
            {TIMEOUT, "timeout"},
    };

    /** 原始错误串 -> 归因码；认不出返回 UNKNOWN（不硬凑，硬凑比不标更误导） */
    public static String code(String raw) {
        if (raw == null) return TIMEOUT;   // 现有调用约定：null 响应即无响应
        for (String[] kv : TABLE) {
            if (raw.contains(kv[1])) return kv[0];
        }
        return UNKNOWN;
    }

    /** 给报告行加归因前缀；认不出就原样返回，避免污染既有文案 */
    public static String tag(String raw) {
        String c = code(raw);
        if (UNKNOWN.equals(c)) return raw;
        // 已经是 "NO_PATH: xxx" 或 "[RISK] xxx" 形式的不再叠加前缀，避免双标记
        if (raw.startsWith("[" + c + "]") || raw.startsWith(c + ":")) return raw;
        return "[" + c + "] " + raw;
    }

    /**
     * 能力矩阵。默认只做静态检查（不联网、不发请求）：
     * 探测类请求会进风控计数，规则 12 明确只允许只读接口，而这里连只读接口都不打。
     *
     * @param ctx    宿主 Context（取其 ClassLoader 做符号解析）
     * @param isMain 是否主进程
     * @param port   本进程 report-server 端口
     */
    public static JSONObject caps(Context ctx, boolean isMain, int port) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());

            JSONObject host = new JSONObject();
            host.put("pkg", Symbols.HOST_PKG);
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(Symbols.HOST_PKG, 0);
                host.put("ver", pi.versionName);
                host.put("code", pi.versionCode);
            } catch (Throwable t) {
                host.put("ver", "?");
                host.put("code", -1);
            }
            o.put("host", host);

            JSONObject proc = new JSONObject();
            proc.put("name", processName());
            proc.put("main", isMain);
            proc.put("port", port);
            o.put("proc", proc);

            JSONObject mod = new JSONObject();
            mod.put("ver", BuildInfo.VERSION);
            mod.put("code", BuildInfo.CODE);
            o.put("module", mod);

            ClassLoader cl = ctx.getClassLoader();
            Class<?> papi = Symbols.papiClass(cl);
            JSONObject sym = new JSONObject();
            sym.put("protocol_center", Symbols.protocolCenter(cl, papi).probe());
            sym.put("papi_context", mark(papi));
            sym.put("api_protocol", mark(Symbols.apiProtocolClass(cl)));
            sym.put("api_callback", mark(Symbols.apiCallbackClass(cl)));
            sym.put("mtop_launcher", mark(Symbols.mtopLauncherClass(cl)));
            o.put("symbols", sym);

            JSONObject verdict = new JSONObject();
            boolean centerOk = !sym.getString("protocol_center").startsWith("MISS");
            boolean engineOk = centerOk && isMain;
            verdict.put("engine_ok", engineOk);
            if (!isMain) {
                verdict.put("reason", "NO_PATH: not main process (XModuleCenter registers only in main)");
            } else if (!centerOk) {
                verdict.put("reason", "NO_PATH: protocol center not resolvable");
            } else {
                verdict.put("reason", "ok");
            }
            o.put("verdict", verdict);
        } catch (Throwable t) {
            try {
                o.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** PApiContext 的 Class，多候选；取不到返回 null（caps 里 protocol_center 就会记为 MISS） */
    public static Class<?> papiClass(ClassLoader cl) {
        return Symbols.papiClass(cl);
    }

    private static String mark(Class<?> c) {
        return c == null ? "MISS" : "ok " + c.getName();
    }

    /** 当前进程名；反射 Application.getProcessName()，失败返回 "?" */
    public static String processName() {
        try {
            return (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }
}
