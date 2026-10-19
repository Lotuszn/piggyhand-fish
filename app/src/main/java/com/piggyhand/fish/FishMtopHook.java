package com.piggyhand.fish;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 淘系 mtop Hook：
 * 1. 侦察 —— 目标 App 发出的每一个 mtop 请求（api/version/data）全部打日志，
 *    走一遍"闲鱼币-赚更多"任务流程即可拿到全部任务接口。
 * 2. 借壳直调 —— 广播设置待发请求；App 下一次发出任意 mtop 请求时，在
 *    MtopBuilder.createMtopContext 之后把该请求的 api/version/data 偷梁换柱成
 *    我们的目标。签名、登录态、session、设备上下文 100% 复用 App 自己的，
 *    服务端视角就是一个完全合法的请求。响应由包装过的 listener 捕获打日志。
 *
 * 用法（root shell）：
 *   adb shell am broadcast -a com.piggyhand.fish.MTOP_CALL \
 *     --es api mtop.taobao.idle.xxx --es version 1.0 --es data '{"taskId":1}'
 * 结果看 LSPosed 日志里的 [hijack] 行。App 空闲时几秒内会有轮询请求触发发送。
 */
public final class FishMtopHook {

    private static final String TAG = "RewardHook";
    public static final String ACTION_MTOP_CALL = "com.piggyhand.fish.MTOP_CALL";

    /** 待发送请求 {api, version, data}，下一次 App 请求时借壳发出 */
    private static volatile String[] pendingInvoke;
    /** 模块运行所在 App 的 context（读写缓存文件用） */
    private static volatile Context appCtx;
    /** 宿主 classLoader（兼容版 fallback hook 用） */
    private static volatile ClassLoader hostCl;

    /* ---------- 兼容版自检/降级（2026-10-19：Android 17 + HyperOS 3 构造 hook 失效个案） ---------- */
    /** MtopFinishEvent 构造 hook 命中计数（0 = 构造 hook 在此 ROM 上失效） */
    static volatile int ctorHits = 0;
    /** MtopRequest.setApiName 方法 hook 命中计数 */
    static volatile int methodHits = 0;
    /** 方法级 fallback 事件 hook 是否已挂 */
    static volatile boolean fallbackArmed = false;
    /** 最近一条 direct 发出的 api（4s 内未出现在构建层则告警） */
    static volatile String directSentApi = null;

    private FishMtopHook() {}

    public static void hook(final ClassLoader cl) {
        hostCl = cl;
        /* ---------- 1. 请求侧侦察 ---------- */
        try {
            Class<?> reqCls = Reflect.findClass("mtopsdk.mtop.domain.MtopRequest", cl);
            Reflect.hookAllMethods(reqCls, "setApiName", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    methodHits++;
                    // 兼容版 direct 自检：发出的 api 出现在构建层 → 清除待告警标记
                    if (directSentApi != null && directSentApi.equals(String.valueOf(param.args[0]))) {
                        directSentApi = null;
                    }
                    log("[mtop-req] api = " + param.args[0]);
                }
            });
            Reflect.hookAllMethods(reqCls, "setVersion", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("[mtop-req] version = " + param.args[0]);
                }
            });
            Reflect.hookAllMethods(reqCls, "setData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // B 类日志体量控制：data 全量可达数 KB（feeds 等高频接口一天几十 MB），截断 300 字符保诊断线索
                    String d = String.valueOf(param.args[0]);
                    if (d.length() > 300) d = d.substring(0, 300) + "...(len=" + d.length() + ")";
                    log("[mtop-req] data = " + d);
                }
            });
            log("mtop request spy armed");
        } catch (Throwable t) {
            log("mtop request hook FAILED: " + t);
        }

        /* ---------- 2. 借壳核心：createMtopContext 之后偷梁换柱 ---------- */
        try {
            Class<?> builderCls = Reflect.findClass("mtopsdk.mtop.intf.MtopBuilder", cl);
            Reflect.hookAllMethods(builderCls, "createMtopContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final String[] p = pendingInvoke;
                    if (p == null) return;
                    try {
                        Object ctx = param.getResult();
                        if (ctx == null) return;

                        // 风控规避：优先借用前台请求；pageName 空但 App 在前台(backGround=false)也可
                        Object prop = getField(ctx, "property");
                        boolean bg = false;
                        Object pageName = null;
                        if (prop != null) {
                            pageName = getField(prop, "pageName");
                            Object bgFlag = getField(prop, "backGround");
                            bg = Boolean.TRUE.equals(bgFlag);
                        }
                        if (bg && (pageName == null || pageName.toString().length() == 0)) {
                            return; // 明确的后台轮询请求，跳过等下一个
                        }

                        pendingInvoke = null;
                        Object req = getField(ctx, "mtopRequest");
                        if (req == null) {
                            log("[hijack] ctx.mtopRequest is null, skip");
                            return;
                        }
                        // 诊断：记录被借掉的原始请求
                        Object origApi = Reflect.callMethod(req, "getApiName");
                        log("[hijack] carrier was: " + origApi);
                        Reflect.callMethod(req, "setApiName", p[0]);
                        Reflect.callMethod(req, "setVersion", p[1]);
                        Reflect.callMethod(req, "setData", p[2]);
                        Reflect.callMethod(req, "setNeedEcode", Boolean.FALSE);
                        Reflect.callMethod(req, "setNeedSession", Boolean.FALSE);

                        // 包装 listener 以捕获响应
                        Object listener = getField(ctx, "mtopListener");
                        if (listener != null) {
                            try {
                                Object proxy = proxyListener(cl, listener);
                                setField(ctx, "mtopListener", proxy);
                            } catch (Throwable t) {
                                log("[hijack] listener wrap failed: " + t);
                            }
                        }
                        log("[hijack] request replaced -> api=" + p[0] + " v=" + p[1] + " data=" + p[2]);
                    } catch (Throwable t) {
                        log("[hijack] FAILED: " + t);
                    }
                }
            });
            log("hijack armed on MtopBuilder.createMtopContext");        } catch (Throwable t) {
            log("createMtopContext hook FAILED: " + t);
        }

        /* ---------- 2.2 流量配对请求侧（v1.0.8）：注册在 hijack 之后，拿到最终 api/data ---------- */
        try {
            Class<?> builderCls2 = Reflect.findClass("mtopsdk.mtop.intf.MtopBuilder", cl);
            Reflect.hookAllMethods(builderCls2, "createMtopContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object ctx = param.getResult();
                        if (ctx == null) return;
                        Object req = getField(ctx, "mtopRequest");
                        if (req == null) return;
                        Object api = Reflect.callMethod(req, "getApiName");
                        if (api == null) return;
                        Object data = Reflect.callMethod(req, "getData");
                        String ds = data == null ? "" : String.valueOf(data);
                        RhTrafficPair.onRequest(String.valueOf(api), ds);
                        RhTokenVault.scanRequest(String.valueOf(api), ds);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("traffic pair request side armed");
        } catch (Throwable t) {
            log("pair request hook FAILED: " + t);
        }

        /* ---------- 2.5 全局响应监听：App 自己的任务列表/领奖响应直接落盘 ---------- */
        try {
            final Class<?> finishEvtCls = Reflect.findClass("mtopsdk.mtop.common.MtopFinishEvent", cl);
            Reflect.hookAllConstructors(finishEvtCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ctorHits++;
                    try {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        handleMtopFinishEvent(finishEvtCls, param.args[0]);
                    } catch (Throwable t) {
                        logLimit("task-resp hook error: " + t);
                    }
                }
            });
            log("global task response listener armed");
        } catch (Throwable t) {
            log("finish event hook FAILED: " + t);
        }

        /* ---------- 3. 捕获 ttid（诊断信息） ---------- */
        try {
            final Class<?> mtopCls = Reflect.findClass("mtopsdk.mtop.intf.Mtop", cl);
            Reflect.hookAllMethods(mtopCls, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null) {
                        for (Object a : param.args) {
                            if (a instanceof String) {
                                log("[mtop-build] ttid=" + a);
                                break;
                            }
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        /* ---------- 4. 广播触发入口 ---------- */
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    registerInvoker((Context) param.args[0]);
                }
            });
        } catch (Throwable t) {
            log("application attach hook FAILED: " + t);
        }
    }

    private static void registerInvoker(final Context ctx) {
        try {
            appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            RhFileLog.init(appCtx); // B 类运行日志就绪（Application 上下文最早可用点）
            RhTokenVault.init(appCtx); // v1.0.8：token 保险库加载（files/zhushou_tokens.json）
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    final String api = intent.getStringExtra("api");
                    if (api == null) return;
                    // 只在主进程执行：子进程没有 mtop SDK
                    String proc = null;
                    try {
                        proc = (String) Application.class.getMethod("getProcessName").invoke(null);
                    } catch (Throwable ignored) {
                    }
                    if (proc != null && proc.contains(":")) return;
                    // 全自动任务引擎入口
                    if ("__RUN_TASKS__".equals(api)) {
                        FishTaskAuto.launch(appCtx, intent.getStringExtra("extras"));
                        log("[tasks] engine launched via broadcast");
                        return;
                    }
                    // 报告回传：把上次钓鱼结果经广播发回模块 UI（免跨 App 存储权限）
                    if ("__GET_RESULT__".equals(api)) {
                        final String rep = readCacheFile("rh_tasks_result.txt");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Intent back = new Intent("com.piggyhand.fish.RESULT");
                                    back.setPackage("com.piggyhand.fish");
                                    back.putExtra("report", rep == null ? "" : rep);
                                    appCtx.sendBroadcast(back);
                                    log("[tasks] result sent back (" + (rep == null ? 0 : rep.length()) + " chars)");
                                } catch (Throwable t) {
                                    log("[tasks] result sendback FAILED: " + t);
                                }
                            }
                        }, "rh-result").start();
                        return;
                    }
                    String version = intent.getStringExtra("version");
                    String data = intent.getStringExtra("data");
                    // data 也可从缓存文件读取（避免 adb shell 转义问题）
                    if (data == null || data.length() == 0) {
                        data = readCacheFile("rh_data.json");
                    }
                    pendingInvoke = new String[]{
                            api,
                            version == null ? "1.0" : version,
                            data == null ? "{}" : data};
                    log("[hijack] pending set: " + api + " data=" + data + " (waiting next request)");
                    // 直调通道优先：走闲鱼自有 ApiProtocol 协议栈，无需载体请求
                    String mode = intent.getStringExtra("mode");
                    if (mode == null || !"hijack".equals(mode)) {
                        final String dApi = api;
                        final String dVer = version == null ? "1.0" : version;
                        final String dData = data == null ? "{}" : data;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                boolean ok = ApiDirect.send(appCtx.getClassLoader(), dApi, dVer, dData);
                                if (ok) {
                                    pendingInvoke = null; // 直调成功，取消借壳
                                    log("[direct] done, hijack cancelled");
                                } else {
                                    log("[direct] failed, keeping hijack pending as fallback");
                                }
                            }
                        }, "rh-direct").start();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(FishMtopHook.ACTION_MTOP_CALL);
            try {
                // Android 13+ 动态 receiver 需要导出 flag（RECEIVER_EXPORTED = 2）
                Context.class
                        .getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                        .invoke(ctx, receiver, filter, 2);
            } catch (Throwable e) {
                ctx.registerReceiver(receiver, filter);
            }
            log("mtop invoker armed: am broadcast -a " + ACTION_MTOP_CALL + " --es api <api> [--es data <json> 或 push rh_data.json]");
            // 每日自动跑（免电脑值守）：主进程 Application 就绪后检查日期标记，当日未跑则延迟触发
            FishTaskAuto.autoRunDaily(appCtx);
            // v1.0.9 🐟 鱼塘任务自动跑：开关 pond=true 且当日未跑时延迟 40s 触发
            RhPondTask.autoRunDaily(appCtx);
            // 闲鱼币余额日志（差分法今日领取）：每分钟取值写 zhushou_*.log，仅主进程
            FishTaskAuto.startCoinBalanceLogger(appCtx);
            // 报告 Socket 服务（仅主进程）：模块 UI 经 127.0.0.1:27715 免权限读取钓鱼报告，
            // 绕过 MIUI 跨 App 广播拦截与 scoped storage
            startReportServer();
            // 兼容版自检：25s 后盘点两类 hook 命中数；构造 hook 0 命中（新 ROM 失效个案）
            // 则动态挂方法级 fallback（EventCenter 参数含 MtopFinishEvent 的分发方法）。
            // 25s 窗口：闲鱼启动后请求已批量出现，且先于 autoRunDaily 40s 首轮触发。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(25000);
                    } catch (InterruptedException ignored) {
                    }
                    log("[compat] self-check @25s: ctorHits=" + ctorHits + " methodHits=" + methodHits);
                    if (ctorHits == 0 && !fallbackArmed) {
                        log("[compat] constructor hook DEAD on this ROM, arming method-level fallback...");
                        armFallbackRespHook();
                    }
                }
            }, "rh-compat").start();
        } catch (Throwable t) {
            log("register invoker receiver FAILED: " + t);
        }
    }

    /* ---------------- 报告 Socket 服务 ---------------- */

    /** 本机回环端口服务：accept → 收到含 REPORT 的请求 → 回写钓鱼报告全文 */
    private static void startReportServer() {
        String proc = null;
        try {
            proc = (String) Application.class.getMethod("getProcessName").invoke(null);
        } catch (Throwable ignored) {
        }
        if (proc != null && proc.contains(":")) return; // 仅主进程

        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                try {
                    ss = new java.net.ServerSocket(27715, 50,
                            java.net.InetAddress.getByName("127.0.0.1"));
                    log("[report-server] listening 127.0.0.1:27715");
                    while (true) {
                        final java.net.Socket s = ss.accept();
                        // 每连接独立线程：单连接挂起不能堵死服务（串行循环曾致 CLOSE_WAIT 堆积）
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    log("[report-server] conn accepted");
                                    s.setSoTimeout(3000); // 读超时防永久挂起
                                    java.io.BufferedReader br = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(s.getInputStream()));
                                    String req = br.readLine();
                                    log("[report-server] req=" + req);
                                    String body = "";
                                    if (req != null && req.trim().equals("REPORT")) {
                                        body = readCacheFile("rh_tasks_result.txt");
                                        if (body == null) body = "";
                                    } else if (req != null && req.startsWith("POND_REPORT")) {
                                        // v1.0.9 鱼塘任务报告读取
                                        body = readCacheFile("rh_tasks_pond.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("POND")) {
                                        // v1.0.9 鱼塘任务引擎触发（后台执行，结果 POND_REPORT 读）
                                        final Context pc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhPondTask.run(pc);
                                                    log("[pond] engine done");
                                                } catch (Throwable t) {
                                                    log("[pond] engine fail: " + t);
                                                }
                                            }
                                        }, "rh-pond").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("RUN")) {
                                        // 任务触发指令（绕过 MIUI 对跨 App 广播的拦截）：
                                        // RUN            → 默认全任务
                                        // RUN {"signin":true,...} → 带 extras
                                        String extras = req.length() > 3 ? req.substring(3).trim() : null;
                                        if (extras != null && extras.isEmpty()) extras = null;
                                        FishTaskAuto.launch(appCtx, extras);
                                        body = "OK";
                                    } else if (req != null && req.startsWith("CALL ")) {
                                        // v1.1 直调指令：CALL {"api":"mtop...","version":"1.0","data":{...}}
                                        // 进程内 ApiDirect 直调 → 等全局 MtopFinishEvent 落盘 → 回传响应全文
                                        String json = req.substring(5).trim();
                                        try {
                                            org.json.JSONObject jo = new org.json.JSONObject(json);
                                            final String cApi = jo.optString("api");
                                            final String cVer = jo.optString("version", "1.0");
                                            Object dObj = jo.opt("data");
                                            final String cData = dObj == null ? "{}" : String.valueOf(dObj);
                                            final String tail = cApi.lastIndexOf('.') >= 0
                                                    ? cApi.substring(cApi.lastIndexOf('.') + 1) : cApi;
                                            final long t0 = System.currentTimeMillis();
                                            boolean ok = ApiDirect.send(appCtx.getClassLoader(), cApi, cVer, cData);
                                            if (ok) {
                                                body = "NO_RESP";
                                                for (int i = 0; i < 16; i++) {
                                                    Thread.sleep(250);
                                                    java.io.File f = cacheFile("rh_resp_" + tail + ".json");
                                                    if (f != null && f.exists()
                                                            && f.lastModified() >= t0) {
                                                        body = readCacheFile("rh_resp_" + tail + ".json");
                                                        break;
                                                    }
                                                }
                                            } else {
                                                body = "SEND_FAIL";
                                            }
                                        } catch (Throwable t) {
                                            body = "ERR " + t;
                                        }
                                    }
                                    java.io.OutputStream os = s.getOutputStream();
                                    os.write(body.getBytes("UTF-8"));
                                    os.flush();
                                    log("[report-server] responded " + body.length() + " chars");
                                } catch (Throwable t) {
                                    log("[report-server] conn err: " + t);
                                } finally {
                                    try {
                                        s.close();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }, "rh-report-conn").start();
                    }
                } catch (Throwable t) {
                    log("[report-server] FAILED: " + t);
                } finally {
                    try {
                        if (ss != null) ss.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "rh-report-srv").start();
    }

    /* ---------------- 兼容版：响应捕获公共通道 + 方法级 fallback ---------------- */

    /**
     * MtopFinishEvent 统一处理：构造 hook 与方法级 fallback 共用。
     * 从事件对象取 MtopResponse（args[0]），按 api 域名过滤后落盘+联动。
     */
    private static void handleMtopFinishEvent(Class<?> finishEvtCls, Object resp) throws Throwable {
        // 确认 resp 是 MtopResponse（fireEvent 分发时参数可能混入其他事件类型）
        if (resp == null) return;
        Class<?> respCls = resp.getClass();
        boolean isMtopResp = false;
        for (Class<?> k = respCls; k != null && k != Object.class; k = k.getSuperclass()) {
            if ("mtopsdk.mtop.common.MtopResponse".equals(k.getName())) {
                isMtopResp = true;
                break;
            }
        }
        if (!isMtopResp) return;

        Object api = Reflect.callMethod(resp, "getApi");
        if (api == null) return;
        String a = String.valueOf(api);
        String la = a.toLowerCase();
        // v1.0.8 🎣 详情媒体提取（不依赖接口名：含 imageInfos 键才真正解析）
        try {
            RhFishMedia.parseResponse(respBody(resp, 200000));
        } catch (Throwable ignored) {
        }
        // 闲鱼业务接口全量落盘（探测新链路需要）；排除高频噪音接口防刷盘
        // 注意 "mtop.taobao.idle" 不带点：兼容 mtop.taobao.idlemtopsearch.*（搜索域）
        if (la.startsWith("mtop.taobao.idle")
                && !la.contains("device.report")
                && !la.contains("ut.log")
                && !la.contains("highway.upload")
                && !la.contains("comment.list")
                && !la.contains("item.recommend")
                && !la.contains("bubble.show")) {
            String body = respBody(resp, -1);
            writeTaskResp(a, body);
            log("[task-resp] captured " + a);
            // v1.0.8 流量配对：请求-响应闭环 + elapsed + 状态
            String pairLine = RhTrafficPair.onResponse(a, body);
            if (pairLine != null) log(pairLine);
            // v1.0.8 token 捞取（响应体扫风控字段）
            RhTokenVault.scanResponse(a, body);
            // 闲鱼币余额白送字段（差分日志）：signin→data.idleCoinAmount / map.init→coinNum
            if (la.equals("mtop.taobao.idle.coin.signin")
                    || la.equals("mtop.taobao.idle.treasure.hunt.map.init")) {
                try {
                    org.json.JSONObject rj = new org.json.JSONObject(body);
                    org.json.JSONObject d2 = rj.optJSONObject("data");
                    String cv = d2 != null ? d2.optString("idleCoinAmount", "") : "";
                    if (cv.length() == 0 && d2 != null) cv = d2.optString("coinNum", "");
                    if (cv.length() == 0 && d2 != null) {
                        org.json.JSONObject ui = d2.optJSONObject("userInfo");
                        if (ui != null) cv = ui.optString("coinNum", "");
                    }
                    long lv = FishTaskAuto.parseCoinNum(cv);
                    if (lv > 0 && appCtx != null) FishTaskAuto.recordCoinBalance(appCtx, lv);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 方法级 fallback：构造 hook 失效的 ROM（Android 17 个案）上，从事件总线的
     * 分发方法兜住响应。遍历 mtopsdk.mtop.infra.EventCenter 全部方法，凡参数表
     * 含 MtopFinishEvent 的全部 hook，after 里扫描参数调 handleMtopFinishEvent。
     * 纯方法 hook —— 与用户机器上已验证存活的 setApiName/build 同一机制。
     */
    private static void armFallbackRespHook() {
        try {
            final Class<?> finishEvtCls = Reflect.findClass("mtopsdk.mtop.common.MtopFinishEvent", hostCl);
            Class<?> ecCls = Reflect.findClass("mtopsdk.mtop.infra.EventCenter", hostCl);
            if (finishEvtCls == null || ecCls == null) {
                log("[compat] fallback target not found (EventCenter/MtopFinishEvent), give up");
                return;
            }
            int armed = 0;
            for (final Method m : ecCls.getDeclaredMethods()) {
                boolean touches = false;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p == finishEvtCls) {
                        touches = true;
                        break;
                    }
                }
                if (!touches) continue;
                Reflect.hookAllMethods(ecCls, m.getName(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null) return;
                            for (Object arg : param.args) {
                                if (arg != null && finishEvtCls.isInstance(arg)) {
                                    Object inner = null;
                                    try {
                                        inner = Reflect.callMethod(arg, "getMtopResponse");
                                    } catch (Throwable ignored) {
                                    }
                                    if (inner == null) continue;
                                    handleMtopFinishEvent(finishEvtCls, inner);
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            logLimit("fallback hook error: " + t);
                        }
                    }
                });
                armed++;
            }
            fallbackArmed = true;
            log("[compat] fallback armed on EventCenter: " + armed + " method(s) hooked");
        } catch (Throwable t) {
            log("[compat] fallback arm FAILED: " + t);
        }
    }

    /** 兼容版 direct 自检：ApiDirect.send 成功后调用，4s 内 api 未出现在构建层则告警 */
    static void noteDirectSent(final String api) {
        directSentApi = api;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                }
                String p = directSentApi;
                if (p != null && p.equals(api)) {
                    log("[direct] WARN: " + api + " 发出 4s 未进入 mtop 构建层"
                            + "（send 疑似静默失败，响应监听大概率同时失效）");
                }
            }
        }, "rh-dcheck").start();
    }

    /** 限频错误日志：同类异常 1 分钟最多打 1 条（防刷盘） */
    private static volatile String lastErrSig = null;
    private static volatile long lastErrAt = 0;

    private static void logLimit(String msg) {
        long now = System.currentTimeMillis();
        String sig = msg.length() > 48 ? msg.substring(0, 48) : msg;
        synchronized (FishMtopHook.class) {
            if (sig.equals(lastErrSig) && now - lastErrAt < 60000) return;
            lastErrSig = sig;
            lastErrAt = now;
        }
        log(msg);
    }

    /* ---------------- 文件通道 ---------------- */

    /** 任务响应落盘：rh_resp.json 总线 + rh_resp_<api尾段>.json 分文件（避免 PC 端清空竞争） */
    private static void writeTaskResp(String api, String body) {
        writeCacheFile("rh_resp.json", body == null ? "" : body);
        String last = api == null ? "unknown" : api;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        writeCacheFile("rh_resp_" + last + ".json", body == null ? "" : body);
    }

    private static java.io.File cacheFile(String name) {
        Context c = appCtx;
        if (c == null) return null;
        try {
            return new java.io.File(c.getExternalCacheDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readCacheFile(String name) {
        try {
            java.io.File f = cacheFile(name);
            if (f == null || !f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            in.read(b);
            in.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            log("readCacheFile " + name + " FAIL: " + t);
            return null;
        }
    }

    private static void writeCacheFile(String name, String content) {
        try {
            java.io.File f = cacheFile(name);
            if (f == null) return;
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(content);
            w.close();
        } catch (Throwable t) {
            log("writeCacheFile " + name + " FAIL: " + t);
        }
    }

    /* ---------------- 反射字段工具 ---------------- */

    private static Object getField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            log("getField " + name + " FAIL: " + t);
        }
        return null;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (Throwable t) {
            log("setField " + name + " FAIL: " + t);
        }
    }

    /* ---------------- listener 代理 ---------------- */

    private static Object proxyListener(final ClassLoader cl, final Object orig) {
        LinkedHashSet<Class<?>> ifaces = new LinkedHashSet<>();
        Class<?> c = orig.getClass();
        while (c != null) {
            for (Class<?> i : c.getInterfaces()) ifaces.add(i);
            c = c.getSuperclass();
        }
        Class<?>[] arr = ifaces.toArray(new Class<?>[0]);
        return Proxy.newProxyInstance(cl, arr, new java.lang.reflect.InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (args != null) {
                    for (Object a : args) {
                        if (a != null && a.getClass().getName().endsWith("MtopFinishEvent")) {
                            Object resp = Reflect.callMethod(a, "getMtopResponse");
                            if (resp != null) {
                                String body = respBody(resp, -1);
                                Object api = Reflect.callMethod(resp, "getApi");
                                log("[hijack-resp] " + describeResp(resp));
                                // 完整响应写文件（供 PC 端读取，无截断；总线+分文件双写）
                                writeTaskResp(api == null ? "hijack" : String.valueOf(api),
                                        body == null ? "" : body);
                            }
                        }
                    }
                }
                try {
                    Method m = orig.getClass().getMethod(method.getName(), method.getParameterTypes());
                    m.setAccessible(true);
                    return m.invoke(orig, args);
                } catch (Throwable t) {
                    log("[hijack-resp] forward fail: " + t);
                    return null;
                }
            }
        });
    }

    private static String describeResp(Object resp) {
        return respBody(resp, 30000);
    }

    private static String respBody(Object resp, int max) {
        if (resp == null) return "null";
        try {
            Object ret = Reflect.callMethod(resp, "getBytedata");
            if (ret instanceof byte[]) {
                String s = new String((byte[]) ret, "UTF-8");
                return max > 0 && s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(resp);
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        RhFileLog.write(msg);
    }
}
