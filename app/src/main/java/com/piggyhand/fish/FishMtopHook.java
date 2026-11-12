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
    /** v1.5.5 P0-3：报告服务 bind 成功 ≈ hook 链尾部完成，作为引擎就绪信号。
     *  冷启动 auto-run 的 45s 固定首探改为「就绪即走、45s 兜底」，首轮 probe 不再
     *  撞在未就绪的通道上白打（(2) 号日志「探测」timeout ×10 的来源之一）。 */
    public static volatile boolean ENGINE_LISTENING = false;


    private static final String TAG = "RewardHook";
    public static final String ACTION_MTOP_CALL = "com.piggyhand.fish.MTOP_CALL";

    /** 待发送请求 {api, version, data}，下一次 App 请求时借壳发出 */
    private static volatile String[] pendingInvoke;
    /** v1.4.5 最近创建的 WebView 实例（H5 EVAL 自动化目标页） */
    static volatile Object lastWebView;
    /** v1.4.5 该实例最近加载的 http URL（调试用） */
    static volatile String lastWebViewUrl;
    /** v1.4.5 已挂 loadUrl hook 的运行时类（去重） */
    static final java.util.Set<String> loadUrlHooked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** v1.5.1 最近 onResume 的前台 activity（WV_RESUME 的 activity 层二次恢复用） */
    static volatile Object lastActivity;
    /** v1.4.5 主进程全部 UC WebView 实例（EVAL 时逐个 probe 选出任务页实例） */
    static final java.util.Set<Object> allWebViews =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    /** 模块运行所在 App 的 context（读写缓存文件用） */
    static volatile Context appCtx; // 包级可见（RhLocationContext 落盘需要）
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
                    String raw = String.valueOf(param.args[0]);
                    // 1.1.2 位置上下文捕获（截断前喂原始体，快扫无命中零开销）
                    try {
                        RhLocationContext.capture(raw);
                    } catch (Throwable ignored) {
                    }
                    // 1.4.3 广告归因请求体捕获：commercial 任务的 idleadv 上报参数一次到手
                    try {
                        if (raw.contains("adPositionCode") || raw.contains("idleadv")
                                || raw.contains("adEvent") || raw.contains("scenarioId")) {
                            java.io.File f = new java.io.File(cacheDir(), "rh_req_idleadv.json");
                            java.io.FileWriter w = new java.io.FileWriter(f, false);
                            w.write(raw);
                            w.close();
                        }
                    } catch (Throwable ignored) {
                    }
                    // B 类日志体量控制：data 全量可达数 KB（feeds 等高频接口一天几十 MB），截断 300 字符保诊断线索
                    String d = raw;
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
                        // v1.4.4 ApiProtocol 网关全量请求 dump（MtopContext 深度字段），
                        // 7.29.x 页面业务请求 api 名固定、路由在 body 里，老 filter 全漏
                        ApiProtoDump.dumpCtx(ctx);
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
            final Class<?> finishEvtCls = Reflect.findClassIfExists("mtopsdk.mtop.common.MtopFinishEvent", cl);
            if (finishEvtCls == null) {
                log("finish event class not found, ctor hook skipped（响应靠 fallback 兜底）");
                return;
            }
            Reflect.hookAllConstructors(finishEvtCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ctorHits++;
                    try {
                        boolean fed = false;
                        if (param.args != null) {
                            // 7.28.60 构造签名不再保证 args[0]=MtopResponse：遍历全部参数，
                            // handleMtopFinishEvent 内部有 isMtopResp 校验自动忽略非响应参数
                            for (Object a : param.args) {
                                if (a == null) continue;
                                handleMtopFinishEvent(finishEvtCls, a);
                                fed = true;
                            }
                        }
                        if (!fed) {
                            // 无参构造变体：响应经 setter 后填，构造点从对象字段捞一次
                            Object inner = null;
                            try {
                                inner = Reflect.callMethod(param.thisObject, "getMtopResponse");
                            } catch (Throwable ignored) {
                            }
                            if (inner != null) handleMtopFinishEvent(finishEvtCls, inner);
                        }
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

        /* ---------- 3.5 Activity 生命周期捕获（v1.5.1 WV 通道：前台 activity 引用） ---------- */
        try {
            Reflect.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastActivity = param.thisObject;
                }
            });
            log("activity onResume tracker armed");
        } catch (Throwable t) {
            log("activity onResume hook FAILED: " + t);
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
                        final String rep = readReportFile("rh_tasks_result.txt");
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
            // v1.4.1 🍚 自动投喂自动跑：开关 pondFeed=true 且当日未跑时延迟 46s 触发（投喂至饲料耗尽）
            RhPondTask.autoRunFeedDaily(appCtx);
            // v1.1.9 🏅 会员任务自动跑：开关 oliverTask=true 且当日未跑时延迟 45s 触发
            RhOlivertask.autoRunDaily(appCtx);
            // v1.2.9 🎮 游戏任务自动跑：开关 gameTask=true 且当日未跑时延迟 50s 触发
            // 纯协议链（center→detail→complete→award），不依赖游戏页 UI
            RhGameTask.autoRunDaily(appCtx);
            // v1.4.6 🪙 赚游戏币任务自动跑：开关 coinGame=true 且标识<2 时延迟 240s 触发
            // （避开 40~50s 那波引擎，防 center.query 共用响应文件串包；标识 0/1/2 停止条件见 RhCoinTask）
            RhCoinTask.autoRunDaily(appCtx);
            // v1.2.9 🧧 自动开红包：开奖日 10:00 起进窗口，每日 2 轮（1 正常 + 1 补）
            RhCashClaim.autoRunDaily(appCtx);
            // v1.2.9 🪙 自动投币：00:01 后进窗口，每轮 30s 轮询 coin 余额补投到当期额度上限
            RhCashJoin.autoRunDaily(appCtx);
            // 会员页顶部「500 每日经验」：独立体系（user.level.exp.fetch），任务全完成也要领，
            // 所以单独起一条 20s 的线，不挂在 45s 的任务链上
            RhOlivertask.autoRunDailyExp(appCtx);
            // 闲鱼币余额日志（差分法今日领取）：每分钟取值写 zhushou_*.log，仅主进程
            FishTaskAuto.startCoinBalanceLogger(appCtx);
            // 报告 Socket 服务（仅主进程）：模块 UI 经 127.0.0.1:27715 免权限读取钓鱼报告，
            // 绕过 MIUI 跨 App 广播拦截与 scoped storage
            startReportServer();
            // v1.4.8 移除 WebView 远程调试开关（setWebContentsDebuggingEnabled）：
            // ANR 实证（/data/anr/ 五次）：UC U4 该调用在 core 未就绪时于主线程 Object.wait
            // 阻塞数秒（getCoreType 锁），WebView 构造密集页（兑换商店）直接 ANR；
            // 且 EVAL 通道走 evaluateJavascript，不依赖此 CDP 开关，属纯冗余风险。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // UC SDK 类晚于模块初始化加载，延迟 2s 再从宿主 classloader 取
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        Class<?> ucWv = hostCl.loadClass("com.uc.webview.export.WebView");
                        de.robv.android.xposed.XposedBridge.hookAllConstructors(
                                ucWv,
                                new de.robv.android.xposed.XC_MethodHook() {
                                    @Override
                                    public void afterHookedMethod(
                                            de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                        lastWebView = param.thisObject;
                                        try {
                                            allWebViews.add(param.thisObject);
                                        } catch (Throwable ignored) {
                                        }
                                        // v1.4.8 删 ctor 内同步 setWebContentsDebuggingEnabled：
                                        // 主线程阻塞源（UC core 未就绪时 Object.wait 数秒 → ANR）
                                        de.robv.android.xposed.XposedBridge.log(
                                                "[RewardHook][webview-debug] UC webview ctor captured");
                                    }
                                });
                        // v1.4.5 loadUrl 跟踪：lastWebView 只认真实 http 页面实例，
                        // 排除启动期预热空白 WebView 抢占 EVAL 目标
                        de.robv.android.xposed.XposedBridge.hookAllMethods(
                                ucWv, "loadUrl",
                                new de.robv.android.xposed.XC_MethodHook() {
                                    @Override
                                    public void beforeHookedMethod(
                                            de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                        try {
                                            if (param.args != null && param.args.length > 0
                                                    && param.args[0] instanceof String) {
                                                String u = (String) param.args[0];
                                                if (u.startsWith("http")) {
                                                    lastWebView = param.thisObject;
                                                    lastWebViewUrl = u;
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                        log("[webview-debug] UC ctor+loadUrl hooks armed");
                        log("[webview-debug] UC ctor hook armed");
                    } catch (Throwable t) {
                        log("[webview-debug] UC class/hook FAILED: " + t);
                    }
                    // v1.5.1 系统 WebView 构造兜底：XRiver 容器可能用系统内核而非 UC，
                    // 两类实例都进 allWebViews，WV_RESUME/WV_EVAL 才能覆盖全部页面
                    try {
                        Reflect.hookAllConstructors(android.webkit.WebView.class,
                                new de.robv.android.xposed.XC_MethodHook() {
                                    @Override
                                    public void afterHookedMethod(
                                            de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                        try {
                                            allWebViews.add(param.thisObject);
                                            log("[webview-debug] sys webview ctor captured");
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                        log("[webview-debug] sys webview ctor hook armed");
                    } catch (Throwable t) {
                        log("[webview-debug] sys webview hook FAILED: " + t);
                    }
                    // v1.5.1 挂起侦查：谁在调 UC WebView 的暂停/隐藏类方法（调用栈直指凶手）。
                    // 游戏页渲染一帧后全冻（rAF 停、evaluate 吞、loadUrl 吞），resumeTimers/onResume
                    // 均无效——需要在调用点抓真凶，而不是继续盲试恢复 API。
                    try {
                        java.lang.String[] pauseMethods = {"onPause", "onVisibilityChanged",
                                "onWindowVisibilityChanged", "dispatchWindowVisibilityChanged",
                                "onDetachedFromWindow"};
                        // UC 类必须反射加载：编译 classpath 只有 android.jar，直接 .class 引用编不过
                        Class<?> ucWvCls;
                        try {
                            ucWvCls = hostCl.loadClass("com.uc.webview.export.WebView");
                        } catch (Throwable ucErr) {
                            log("[wv-suspicious] UC WebView class not found: " + ucErr);
                            ucWvCls = null;
                        }
                        if (ucWvCls != null) {
                            for (final java.lang.String pm : pauseMethods) {
                                Reflect.hookAllMethods(ucWvCls, pm,
                                        new de.robv.android.xposed.XC_MethodHook() {
                                            @Override
                                            public void beforeHookedMethod(
                                                    de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                                StringBuilder sb = new StringBuilder("[wv-suspicious] UC.").append(pm);
                                                if (param.args != null) {
                                                    for (Object a : param.args) sb.append(' ').append(a);
                                                }
                                                log(sb.toString());
                                                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                                for (int i = 3; i < Math.min(st.length, 16); i++) {
                                                    log("    at " + st[i]);
                                                }
                                            }
                                        });
                            }
                            log("[wv-suspicious] UC pause-method watchers armed");
                        }
                    } catch (Throwable t) {
                        log("[wv-suspicious] arm FAILED: " + t);
                    }
                    // v1.5.1 View 层可见性监控：UC 未 override 的方法走基类，在 View.dispatchWindowVisibilityChanged
                    // 上统一挂网，回调里只对 UC WebView 实例打日志（排除其他 view 的噪音）
                    try {
                        Reflect.hookAllMethods(android.view.View.class, "dispatchWindowVisibilityChanged",
                                new de.robv.android.xposed.XC_MethodHook() {
                                    @Override
                                    public void beforeHookedMethod(
                                            de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                        try {
                                            if (param.thisObject == null) return;
                                            String cn = param.thisObject.getClass().getName();
                                            if (!cn.startsWith("com.uc.")) return;
                                            StringBuilder sb = new StringBuilder("[wv-vis] ").append(cn)
                                                    .append(" vis=").append(param.args != null && param.args.length > 0 ? param.args[0] : "?");
                                            log(sb.toString());
                                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                            for (int i = 3; i < Math.min(st.length, 14); i++) {
                                                log("    at " + st[i]);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                        log("[wv-vis] View dispatch watcher armed");
                    } catch (Throwable t) {
                        log("[wv-vis] arm FAILED: " + t);
                    }
                }
            }, "rh-wvdbg-uc").start();
            // v1.4.8 删 8s 主线程兜底 setWebContentsDebuggingEnabled 调用：
            // ANR trace 铁证（FishMtopHook$12$1.run:503 主线程 TimedWaiting 于 UC getCoreType 锁）。
            // EVAL 走 evaluateJavascript 不需要该开关；sys/UC ctor 两处的 setter 也已一并移除。
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

    /** v1.5.1 遍历捕获的 WebView 实例：resumeTimers 全局恢复 timers，
     *  抢救 pauseTimers 失衡导致的 H5/Cocos 游戏页 rAF 全停。结果落盘 rh_wv_res.txt。
     *  优先 socket 线程直调（UC 内核不做线程检查），抛异常再 post 回 UI 线程兜底。 */
    private static String wvResumeAll() {
        final java.util.List<Object> list;
        synchronized (allWebViews) {
            list = new java.util.ArrayList<Object>(allWebViews);
        }
        if (list.isEmpty()) return "NO_WV";
        final StringBuilder out = new StringBuilder();
        for (final Object wv : list) {
            boolean done = false;
            // 路径 1：socket 线程直调
            try {
                wv.getClass().getMethod("resumeTimers").invoke(wv);
                out.append("RESUMED-direct url=").append(urlOf(wv)).append('\n');
                log("[wv-resume] RESUMED-direct url=" + urlOf(wv));
                done = true;
            } catch (Throwable t1) {
                log("[wv-resume] direct fail: " + t1);
            }
            // 路径 2：post 回 UI 线程
            if (!done) {
                try {
                    java.lang.reflect.Method postM = wv.getClass().getMethod("post", Runnable.class);
                    postM.invoke(wv, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                wv.getClass().getMethod("resumeTimers").invoke(wv);
                                // v1.5.1 补生命周期恢复：U4 深度挂起时仅 resumeTimers 不够；
                                // onResume 结果显式记录（此前静默吞掉，无法判断是否生效）
                                String r2;
                                try {
                                    wv.getClass().getMethod("onResume").invoke(wv);
                                    r2 = "onResume-ok";
                                } catch (Throwable tt) {
                                    r2 = "onResume-ERR:" + tt;
                                }
                                // v1.5.1 activity 层二次恢复：XRiver 容器 onResume 会把可见性传播给 U4 renderer
                                Object act = lastActivity;
                                String r3 = "no-act";
                                if (act != null) {
                                    try {
                                        act.getClass().getMethod("onResume").invoke(act);
                                        r3 = "act-ok:" + act.getClass().getSimpleName();
                                    } catch (Throwable tt) {
                                        r3 = "act-ERR:" + tt;
                                    }
                                }
                                String line = "RESUMED-posted " + r2 + " " + r3 + " url=" + urlOf(wv) + "\n";
                                synchronized (out) {
                                    out.append(line);
                                }
                                log("[wv-resume]" + line.trim());
                            } catch (Throwable t) {
                                synchronized (out) {
                                    out.append("ERR-post ").append(t).append('\n');
                                }
                                log("[wv-resume] ERR-post " + t);
                            }
                            writeCacheFile("rh_wv_res.txt", out.toString());
                        }
                    });
                    out.append("POSTED url=").append(urlOf(wv)).append('\n');
                } catch (Throwable t2) {
                    out.append("ERR ").append(t2).append('\n');
                    log("[wv-resume] post fail: " + t2);
                }
            }
        }
        writeCacheFile("rh_wv_res.txt", out.toString());
        return "OK started";
    }

    /** v1.5.1 强制重载所有捕获的 WebView 当前 URL：重建页面 JS 上下文，
     *  U4 renderer 挂起导致 evaluate 无响应时的兜底。结果落盘 rh_wv_res.txt。 */
    private static String wvReloadAll() {
        final java.util.List<Object> list;
        synchronized (allWebViews) {
            list = new java.util.ArrayList<Object>(allWebViews);
        }
        if (list.isEmpty()) return "NO_WV";
        final StringBuilder out = new StringBuilder();
        for (final Object wv : list) {
            try {
                java.lang.reflect.Method postM = wv.getClass().getMethod("post", Runnable.class);
                postM.invoke(wv, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String url = urlOf(wv);
                            if (url.startsWith("http")) {
                                // 重载 = loadUrl 当前地址：整页重建（loading→游戏），JS 上下文全新
                                wv.getClass().getMethod("loadUrl", String.class).invoke(wv, url);
                                synchronized (out) {
                                    out.append("RELOADED ").append(url).append('\n');
                                }
                                log("[wv-reload] " + url);
                            } else {
                                synchronized (out) {
                                    out.append("SKIP url=").append(url).append('\n');
                                }
                            }
                        } catch (Throwable t) {
                            synchronized (out) {
                                out.append("ERR-reload ").append(t).append('\n');
                            }
                            log("[wv-reload] ERR " + t);
                        }
                        writeCacheFile("rh_wv_res.txt", out.toString());
                    }
                });
                out.append("RELOAD-POSTED url=").append(urlOf(wv)).append('\n');
            } catch (Throwable t2) {
                out.append("ERR ").append(t2).append('\n');
            }
        }
        writeCacheFile("rh_wv_res.txt", out.toString());
        return "OK started";
    }

    /** v1.5.1 反射取 WebView 当前 URL（线程违规/无方法均返回占位符） */
    private static String urlOf(Object wv) {
        try {
            return String.valueOf(wv.getClass().getMethod("getUrl").invoke(wv));
        } catch (Throwable t) {
            return "<no-url:" + t.getClass().getSimpleName() + ">";
        }
    }

    /** v1.5.1 UI 线程开启全部捕获 WebView 的 CDP 调试口：
     *  UC export WebView 与系统 WebView 各有一个静态 setWebContentsDebuggingEnabled。
     *  成功后 /proc/net/unix 出现 @<pid>_devtools_remote，adb forward 即可接 CDP。 */
    private static String wvEnableDebug() {
        final java.util.List<Object> list;
        synchronized (allWebViews) {
            list = new java.util.ArrayList<Object>(allWebViews);
        }
        final StringBuilder out = new StringBuilder();
        if (list.isEmpty()) return "NO_WV";
        for (final Object wv : list) {
            try {
                java.lang.reflect.Method postM = wv.getClass().getMethod("post", Runnable.class);
                postM.invoke(wv, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Class<?> cls = wv.getClass();
                            while (cls != null) {
                                try {
                                    java.lang.reflect.Method m = cls.getDeclaredMethod(
                                            "setWebContentsDebuggingEnabled", boolean.class);
                                    m.setAccessible(true);
                                    m.invoke(null, Boolean.TRUE);
                                    synchronized (out) {
                                        out.append("DEBUG-ON ").append(cls.getName()).append('\n');
                                    }
                                    log("[wv-debug] on via " + cls.getName());
                                    break;
                                } catch (NoSuchMethodException e) {
                                    cls = cls.getSuperclass();
                                }
                            }
                        } catch (Throwable t) {
                            synchronized (out) {
                                out.append("ERR-debug ").append(t).append('\n');
                            }
                            log("[wv-debug] ERR " + t);
                        }
                        writeCacheFile("rh_wv_res.txt", out.toString());
                    }
                });
            } catch (Throwable t) {
                out.append("ERR ").append(t).append('\n');
            }
        }
        // 系统内核的调试口是全局静态：无论实例走没走上面，都补一发系统层
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                try {
                    android.webkit.WebView.class.getMethod("setWebContentsDebuggingEnabled", boolean.class)
                            .invoke(null, Boolean.TRUE);
                    synchronized (out) {
                        out.append("DEBUG-ON android.webkit.WebView(static)\n");
                    }
                } catch (Throwable t) {
                    synchronized (out) {
                        out.append("ERR-sys ").append(t).append('\n');
                    }
                }
                String snap;
                synchronized (out) {
                    snap = out.toString();
                }
                writeCacheFile("rh_wv_res.txt", snap);
            }
        }, "rh-wv-debug-sys").start();
        return "OK started";
    }

    /** v1.5.1 遍历捕获的 WebView 实例：evaluateJavascript（直调，UC 内核不做线程检查；
     *  每实例返回值落盘 rh_wv_eval.txt，多实例以 ---- 分隔，1.5s 后快照覆盖写）。 */
    private static String wvEvalAll(final String js) {
        final java.util.List<Object> list;
        synchronized (allWebViews) {
            list = new java.util.ArrayList<Object>(allWebViews);
        }
        if (list.isEmpty()) return "NO_WV";
        final StringBuilder out = new StringBuilder();
        for (final Object wv : list) {
            final StringBuilder seg = new StringBuilder();
            try {
                seg.append("URL ").append(urlOf(wv)).append('\n');
                // evaluateJavascript 的 ValueCallback 是单方法接口，Proxy 代理捕获返回值
                java.lang.reflect.Method evalM = null;
                for (java.lang.reflect.Method m : wv.getClass().getMethods()) {
                    if ("evaluateJavascript".equals(m.getName())
                            && m.getParameterTypes().length == 2
                            && m.getParameterTypes()[0] == String.class
                            && m.getParameterTypes()[1].isInterface()) {
                        evalM = m;
                        break;
                    }
                }
                if (evalM == null) throw new NoSuchMethodException("evaluateJavascript(String,IF)");
                final StringBuilder cbOut = seg;
                Object cb = java.lang.reflect.Proxy.newProxyInstance(
                        wv.getClass().getClassLoader(),
                        new Class[]{evalM.getParameterTypes()[1]},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
                                if (a != null && a.length > 0 && a[0] != null) {
                                    String v = String.valueOf(a[0]);
                                    if (v.length() > 4000) v = v.substring(0, 4000) + "...(trunc)";
                                    synchronized (cbOut) {
                                        cbOut.append("RESULT ").append(v).append('\n');
                                    }
                                }
                                return null;
                            }
                        });
                evalM.invoke(wv, js, cb);
            } catch (Throwable t) {
                seg.append("ERR ").append(t).append('\n');
                log("[wv-eval] ERR " + t);
            }
            synchronized (out) {
                out.append("----\n").append(seg);
            }
        }
        // 结果延迟快照覆盖写：onReceiveValue 回调可能晚于本次同步写
        final StringBuilder ref = out;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                String snap;
                synchronized (ref) {
                    snap = ref.toString();
                }
                writeCacheFile("rh_wv_eval.txt", snap);
            }
        }, "rh-wv-eval-write").start();
        writeCacheFile("rh_wv_eval.txt", out.toString());
        return "OK started";
    }


    /** v1.4.5 主线程专用：对单个 WebView 反射执行 evaluateJavascript（回调丢弃，结果走 title 桥） */
    private static void invokeEvalOne(Object wv, String js) throws Exception {
        java.lang.reflect.Method evalM = null;
        for (java.lang.reflect.Method m : wv.getClass().getMethods()) {
            if ("evaluateJavascript".equals(m.getName())
                    && m.getParameterTypes().length >= 1
                    && m.getParameterTypes()[0] == String.class) {
                evalM = m;
                break;
            }
        }
        if (evalM == null) {
            wv.getClass().getMethod("loadUrl", String.class).invoke(wv, "javascript:" + js);
            return;
        }
        if (evalM.getParameterTypes().length == 2 && evalM.getParameterTypes()[1].isInterface()) {
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    appCtx.getClassLoader(),
                    new Class[]{evalM.getParameterTypes()[1]},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] a) {
                            return null;
                        }
                    });
            evalM.invoke(wv, js, cb);
        } else if (evalM.getParameterTypes().length == 2) {
            // 2 参但第二参不是接口（抽象类等）——退化为 1 参或 loadUrl
            try {
                java.lang.reflect.Method m1 = wv.getClass().getMethod("evaluateJavascript", String.class);
                m1.invoke(wv, js);
            } catch (Throwable ignored) {
                wv.getClass().getMethod("loadUrl", String.class).invoke(wv, "javascript:" + js);
            }
        } else {
            evalM.invoke(wv, js);
        }
    }

    /** 本机回环端口服务：accept → 收到含 REPORT 的请求 → 回写钓鱼报告全文 */
    private static void startReportServer() {
        String proc = null;
        try {
            proc = (String) Application.class.getMethod("getProcessName").invoke(null);
        } catch (Throwable ignored) {
        }
        // v1.5.1 渲染进程通道：WebView 实例只在 :normal_render_proc0，WV_RESUME/WV_EVAL 必须在
        // 持有实例的进程里执行。主进程照旧 27715；渲染进程 27717（reportPort+2），只支持 PING/WV_*
        final boolean isRenderProc = proc != null && proc.contains(":");
        if (isRenderProc && !proc.contains("render")) return; // 渲染进程专用通道；其余子进程不需要

        log("[report-server] enter, proc=" + proc + " userId=" + ConfigManager.myUserId()
                + " port=" + (ConfigManager.reportPort() + (isRenderProc ? 2 : 0)));
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                // 端口按 userId 偏移（0 → 27715 / 分身 999 → 28714）：回环端口全设备共享，
                // 固定端口会被另一个用户的闲鱼抢占，后绑定者直接抛异常
                // 渲染进程再 +2（27717）：主/渲两个服务不能抢同一端口
                final int port = ConfigManager.reportPort() + (isRenderProc ? 2 : 0);
                try {
                    ss = new java.net.ServerSocket(port, 50,
                            java.net.InetAddress.getByName("127.0.0.1"));
                    log("[report-server] listening 127.0.0.1:" + port);
                    if (!isRenderProc) ENGINE_LISTENING = true;
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
                                    // 引擎命令一律拒在渲染进程：COIN/GAME/COINEX 依赖
                                    // XModuleCenter.moduleForProtocol(PApiContext)，而协议模块只在主进程
                                    // Application.onCreate 里注册，渲染进程调用恒返回 null。
                                    // 不拦会让调用方拿到 OK started 后全链路 无响应/TASK_PLATFORM_ERROR，
                                    // 误判成版本不兼容（2026-11-12 7.29.10 实测踩坑）。
                                    if (isRenderProc && isEngineCmd(req)) {
                                        log("[report-server] reject engine cmd in render proc: " + req);
                                        body = "WRONG_PROC";
                                    } else if (req != null && req.startsWith("WV_RESUME")) {
                                        // v1.5.1 渲染进程专用：全局恢复 WebView timers（pauseTimers 失衡冻结 H5/游戏页的抢救通道）
                                        body = wvResumeAll();
                                    } else if (req != null && req.startsWith("WV_DEBUG")) {
                                        // v1.5.1 开 U4/系统 WebView 的 CDP 远程调试口（core 就绪后 UI 线程调用）：
                                        // chrome-devtools Page.setWebLifecycleState('active') 是 renderer 深挂起的正统恢复通道
                                        body = wvEnableDebug();
                                    } else if (req != null && req.startsWith("WV_RELOAD")) {
                                        // v1.5.1 强制重载当前页（重建 JS 上下文；U4 renderer 深度挂起时 evaluate 不响应的最后一招）
                                        body = wvReloadAll();
                                    } else if (req != null && req.startsWith("WV_EVAL ")) {
                                        // v1.5.1 渲染进程专用：对捕获到的每个 WebView evaluateJavascript（结果落盘 rh_wv_eval.txt）
                                        body = wvEvalAll(req.substring(8));
                                    } else if (req != null && req.trim().startsWith("CAPS")) {
                                        // 能力矩阵（COMPAT_RULES 规则 9/12）：只做静态符号探测，
                                        // 不发任何网络请求——探测类请求会进风控计数，这里连只读接口都不打。
                                        // 主进程与渲染进程都能出结果，对比两份即可判定 NO_PATH:wrong-proc。
                                        org.json.JSONObject caps = Diag.caps(appCtx, !isRenderProc, port);
                                        String js = caps.toString();
                                        writeCacheFile("rh_caps.json", js);
                                        log("[caps] " + js);
                                        body = js;
                                    } else if (!isRenderProc && req != null && req.trim().equals("PING")) {
                                        // 身份回执（2026-10-23）：回环端口跨用户互通，仅凭"连得上"无法
                                        // 证明是本用户的引擎。回传自身 uid/userId，App 侧据此校验归属。
                                        int uid = android.os.Process.myUid();
                                        body = "RH|" + uid + "|" + ConfigManager.userIdOf(uid) + "|"
                                                + android.os.Process.myPid();
                                    } else if (req != null && req.trim().equals("REPORT")) {
                                        body = readReportFile("rh_tasks_result.txt");
                                        if (body == null) body = "";
                                    } else if (req != null && req.trim().equals("UI")) {
                                        // 桌面图标隐藏后的软件入口：显式 ComponentName 拉起配置界面。
                                        // 模块跑在闲鱼进程（另一个 uid），跨包启动必须带 NEW_TASK；
                                        // 宿主要在后台时被系统拦是已知限制，adb 的 am start 可作兜底。
                                        try {
                                            android.content.Intent uit = new android.content.Intent();
                                            uit.setComponent(new android.content.ComponentName(
                                                    "com.piggyhand.fish", "com.piggyhand.fish.MainActivity"));
                                            uit.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                            appCtx.startActivity(uit);
                                            body = "OK";
                                            log("[ui] launch main activity requested");
                                        } catch (Throwable t) {
                                            body = "FAIL " + t;
                                            log("[ui] launch FAIL: " + t);
                                        }
                                    } else if (req != null && req.startsWith("CASH_REPORT")) {
                                        // v1.2.9 开红包报告读取
                                        body = readReportFile("rh_tasks_cash.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("JOIN_REPORT")) {
                                        // v1.2.9 投币报告读取（必须先于 JOIN 的前缀匹配）
                                        body = readReportFile("rh_tasks_join.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.trim().equals("JOIN")) {
                                        // v1.2.9 手动投币一轮（后台执行，结果 JOIN_REPORT 读）
                                        final Context jc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhCashJoin.run(jc);
                                                    log("[join] manual done");
                                                } catch (Throwable t) {
                                                    log("[join] manual fail: " + t);
                                                }
                                            }
                                        }, "rh-cash-join-one").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("CASH_STATE")) {
                                        // v1.2.9 两组状态合并回传：CLAIM|day|state|round|lastRet
                                        //                        #JOIN|day|state|...
                                        // App 侧按 '#' 拆两段、按 '|' 取 [1]=day [2]=state 定文案
                                        try {
                                            body = "CLAIM|" + RhCashClaim.stateText(appCtx)
                                                    + "#JOIN|" + RhCashJoin.stateText(appCtx);
                                        } catch (Throwable t) {
                                            body = "NONE";
                                        }
                                    } else if (req != null && req.trim().equals("CLAIM")) {
                                        // v1.2.9 手动开红包一轮（结果 CASH_REPORT 读）
                                        final Context cc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhCashClaim.run(cc);
                                                    log("[cash] claim done");
                                                } catch (Throwable t) {
                                                    log("[cash] claim fail: " + t);
                                                }
                                            }
                                        }, "rh-cash").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("CYCLE_REPORT")) {
                                        // v1.2.10 圈数挑战手动触发结果读取（必须先于 CYCLE 的前缀匹配）
                                        body = readReportFile("rh_tasks_cycle.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("CYCLE")) {
                                        // v1.2.10 手动跑一次圈数挑战查档+领取（结果 CYCLE_REPORT 读，报告带 @runId 防旧档误读）
                                        final Context yc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    FishTaskAuto.runCycleClaimOnce(yc);
                                                    log("[cycle] manual run done");
                                                } catch (Throwable t) {
                                                    log("[cycle] manual fail: " + t);
                                                }
                                            }
                                        }, "rh-cycle").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("CLEAR_MARKS")) {
                                        // v1.5.3 清除全部当日任务标记（LVL 0/1/2 计数 + done/try/date 文件）。
                                        // 必须同步执行并回显结果：App 端拿到回显后才会 force-stop，
                                        // 异步回写会与进程被杀竞争。纯文件 IO 毫秒级，同步不阻塞。
                                        // 引擎运行中 clearDailyMarks 内部拒绝（防 bump 回写）。
                                        body = FishTaskAuto.clearDailyMarks();
                                    } else if (req != null && req.startsWith("GAME_REPORT")) {
                                        // v1.2.9 游戏任务报告读取（必须先于 GAME 的前缀匹配）
                                        body = readReportFile("rh_tasks_game.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("GAME")) {
                                        // v1.2.9 手动跑一轮游戏任务（纯协议链，结果 GAME_REPORT 读）
                                        final Context gc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhGameTask.run(gc);
                                                    log("[game] engine done");
                                                } catch (Throwable t) {
                                                    log("[game] engine fail: " + t);
                                                }
                                            }
                                        }, "rh-game").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("COINEX_REPORT")) {
                                        // v1.4.9 自动兑换报告读取（必须先于 COINEX / COIN 的前缀匹配）
                                        body = readReportFile("rh_tasks_coinexch.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("COINEX")) {
                                        // v1.4.9 手动执行一次兑换检查（manual=true，不受每日 1 次与时间窗口限制；
                                        // 自动触发走 RhCoinTask 的标识 2 挂点，硬性条件在 RhCoinExchange 内判）
                                        final Context cx2 = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhCoinExchange.runCheck(cx2, true);
                                                    log("[exch] manual run done");
                                                } catch (Throwable t) {
                                                    log("[exch] manual fail: " + t);
                                                }
                                            }
                                        }, "rh-coinex").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("COIN_REPORT")) {
                                        // v1.4.6 赚游戏币任务报告读取（必须先于 COIN 的前缀匹配）
                                        body = readReportFile("rh_tasks_coin.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("COIN")) {
                                        // v1.4.6 手动跑一轮赚游戏币任务（中央+渠道收口，结果 COIN_REPORT 读；
                                        // 手动触发不受标识 0/1/2 停止条件限制，跑完按复核结果滚动标记）
                                        final Context cc2 = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhCoinTask.run(cc2);
                                                    log("[coin] engine done");
                                                } catch (Throwable t) {
                                                    log("[coin] engine fail: " + t);
                                                }
                                            }
                                        }, "rh-coin").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("PONDFEED_REPORT")) {
                                        // v1.4.1 自动投喂报告读取（必须先于 PONDFEED / POND 的前缀匹配）
                                        body = readCacheFile("rh_tasks_pondfeed.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("PONDFEED")) {
                                        // v1.4.1 手动投喂（自动进行到饲料耗尽，结果 PONDFEED_REPORT 读）
                                        final Context fc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhPondTask.runFeed(fc);
                                                    log("[pondfeed] manual done");
                                                } catch (Throwable t) {
                                                    log("[pondfeed] manual fail: " + t);
                                                }
                                            }
                                        }, "rh-pondfeed").start();
                                        body = "OK started";
                                    } else if (req != null && req.startsWith("POND_REPORT")) {
                                        // v1.0.9 鱼塘任务报告读取
                                        body = readCacheFile("rh_tasks_pond.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("OLIVER_REPORT")) {
                                        // v1.1.9 会员任务报告读取（必须先于 OLIVER 判断，
                                        // 否则 OLIVER_REPORT 会被前缀匹配吃掉）
                                        body = readCacheFile("rh_tasks_oliver.txt");
                                        if (body == null) body = "NO_REPORT";
                                    } else if (req != null && req.startsWith("OLIVER_STATE")) {
                                        // v1.1.9 会员任务完成状态查询（同样必须先于 OLIVER 前缀）
                                        // 返回 "DONE <失效毫秒>" 或 "TODO"
                                        try {
                                            body = RhOlivertask.stateText(appCtx);
                                        } catch (Throwable t) {
                                            body = "TODO";
                                        }
                                    } else if (req != null && req.startsWith("OLIVER")) {
                                        // v1.1.9 会员任务引擎触发（后台执行，结果 OLIVER_REPORT 读）
                                        final Context oc = appCtx;
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    RhOlivertask.run(oc);
                                                    log("[oliver] engine done");
                                                } catch (Throwable t) {
                                                    log("[oliver] engine fail: " + t);
                                                }
                                            }
                                        }, "rh-oliver").start();
                                        body = "OK started";
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
                                            // 先删旧响应文件再轮询 exists()：部分 ROM（Android 10 sdcardfs）
                                            // lastModified 有缓存延迟，mtime 判断会漏掉新响应（MIX3 实测）
                                            try {
                                                java.io.File f0 = cacheFile("rh_resp_" + tail + ".json");
                                                if (f0 != null && f0.exists()) f0.delete();
                                            } catch (Throwable ignored) {
                                            }
                                            boolean ok = ApiDirect.send(appCtx.getClassLoader(), cApi, cVer, cData);
                                            if (ok) {
                                                body = "NO_RESP";
                                                for (int i = 0; i < 16; i++) {
                                                    Thread.sleep(250);
                                                    java.io.File f = cacheFile("rh_resp_" + tail + ".json");
                                                    if (f != null && f.exists()) {
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
                                    } else if (req != null && req.startsWith("EVAL ")) {
                                        // v1.4.5 H5 自动化：EVAL <js> → 最近 WebView 执行 → document.title 桥回传。
                                        // UC 多进程模式下 ValueCallback 对象无法跨 binder 传回（Proxy 静默丢失），
                                        // 改为 JS 侧把结果写 document.title="__RH__"+json，native 轮询 getTitle() 取回。
                                        String js = req.substring(5).trim();
                                        final Object wv = lastWebView;
                                        if (wv == null && allWebViews.isEmpty()) {
                                            body = "NO_WEBVIEW";
                                        } else {
                                            try {
                                                final String wrapped =
                                                        "(function(){var __r;try{__r=(function(){" + js
                                                                + "})()}catch(e){__r='JSERR '+e}"
                                                                + ";try{document.title='__RH__'+(typeof __r==='object'?"
                                                                + "JSON.stringify(__r):String(__r))}catch(e){}})()";
                                                final java.util.concurrent.CountDownLatch latch =
                                                        new java.util.concurrent.CountDownLatch(1);
                                                final String[] out = new String[1];
                                                final String jsF = js;
                                                new android.os.Handler(android.os.Looper.getMainLooper())
                                                        .post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        // 主线程状态机：probe 各实例 → 选 goofish 页 → 执行 EVAL
                                                        final android.os.Handler h =
                                                                new android.os.Handler(android.os.Looper.getMainLooper());
                                                        final java.util.List<Object> cands =
                                                                new java.util.ArrayList<Object>(allWebViews);
                                                        if (cands.isEmpty() && wv != null) cands.add(wv);
                                                        final int[] idx = {0};
                                                        final Object[] chosen = {null};
                                                        final String[] probeUrl = {null};
                                                        Runnable[] step = new Runnable[1];
                                                        final boolean[] probing = {true};
                                                        // 单实例 probe：注入标记 → 400ms 后读 title
                                                        step[0] = new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                if (probing[0]) {
                                                                    if (idx[0] >= cands.size()) {
                                                                        // 全部 probe 完：没有 goofish 页就退回 lastWebView
                                                                        probing[0] = false;
                                                                        chosen[0] = (wv != null) ? wv
                                                                                : (cands.isEmpty() ? null
                                                                                : cands.get(cands.size() - 1));
                                                                        h.post(this);
                                                                        return;
                                                                    }
                                                                    Object cur = cands.get(idx[0]);
                                                                    String mark = "__RP" + idx[0] + "__";
                                                                    try {
                                                                        invokeEvalOne(cur,
                                                                                "document.title='" + mark
                                                                                        + "'+location.href.slice(0,160)");
                                                                    } catch (Throwable t) {
                                                                        out[0] = "ERR probe " + t;
                                                                        latch.countDown();
                                                                        return;
                                                                    }
                                                                    final String mk = mark;
                                                                    final Object cw = cur;
                                                                    h.postDelayed(new Runnable() {
                                                                        @Override
                                                                        public void run() {
                                                                            try {
                                                                                String t = (String) cw.getClass()
                                                                                        .getMethod("getTitle")
                                                                                        .invoke(cw);
                                                                                if (t != null && t.contains(mk)) {
                                                                                    String href = t.substring(
                                                                                            t.indexOf(mk) + mk.length());
                                                                                    if (href.contains("goofish")
                                                                                            || href.contains("h5.m.")) {
                                                                                        chosen[0] = cw;
                                                                                        probeUrl[0] = href;
                                                                                        probing[0] = false;
                                                                                        h.post(step[0]);
                                                                                        return;
                                                                                    }
                                                                                }
                                                                            } catch (Throwable ignored) {
                                                                            }
                                                                            idx[0]++;
                                                                            h.post(step[0]);
                                                                        }
                                                                    }, 400);
                                                                } else {
                                                                    // 执行真正的 EVAL
                                                                    Object target = chosen[0];
                                                                    if (target == null) {
                                                                        out[0] = "NO_TARGET";
                                                                        latch.countDown();
                                                                        return;
                                                                    }
                                                                    try {
                                                                        invokeEvalOne(target, wrapped);
                                                                    } catch (Throwable t) {
                                                                        out[0] = "ERR eval " + t;
                                                                        latch.countDown();
                                                                        return;
                                                                    }
                                                                    final long deadline =
                                                                            System.currentTimeMillis() + 10000;
                                                                    Runnable[] poll = new Runnable[1];
                                                                    final Object tw = target;
                                                                    poll[0] = new Runnable() {
                                                                        @Override
                                                                        public void run() {
                                                                            try {
                                                                                String t = (String) tw.getClass()
                                                                                        .getMethod("getTitle")
                                                                                        .invoke(tw);
                                                                                if (t != null && t.contains("__RH__")) {
                                                                                    out[0] = t.substring(
                                                                                            t.indexOf("__RH__") + 6);
                                                                                    latch.countDown();
                                                                                    return;
                                                                                }
                                                                            } catch (Throwable ignored) {
                                                                            }
                                                                            if (System.currentTimeMillis() < deadline
                                                                                    && out[0] == null) {
                                                                                h.postDelayed(poll[0], 250);
                                                                            } else if (out[0] == null) {
                                                                                out[0] = "EVAL_TIMEOUT";
                                                                                latch.countDown();
                                                                            }
                                                                        }
                                                                    };
                                                                    h.postDelayed(poll[0], 300);
                                                                }
                                                            }
                                                        };
                                                        h.post(step[0]);
                                                    }
                                                });
                                                latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
                                                body = out[0] == null ? "EVAL_TIMEOUT" : out[0];
                                            } catch (Throwable t) {
                                                body = "ERR " + t;
                                            }
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
     * @return true = 本次调用实际落盘（respHook 依赖它做去重登记）
     */
    private static boolean handleMtopFinishEvent(Class<?> finishEvtCls, Object resp) throws Throwable {
        // 确认 resp 是 MtopResponse（fireEvent 分发时参数可能混入其他事件类型）
        // 7.28.60 起类从 mtopsdk.mtop.common 迁到 mtopsdk.mtop.domain：按类名尾匹配
        if (resp == null) {
            logLimit("[resp-diag] handleMtopFinishEvent got null resp (getMtopResponse 反射失败或未填充)");
            return false;
        }
        Class<?> respCls = resp.getClass();
        boolean isMtopResp = false;
        for (Class<?> k = respCls; k != null && k != Object.class; k = k.getSuperclass()) {
            String kn = k.getName();
            if (kn.endsWith(".MtopResponse")) {
                isMtopResp = true;
                break;
            }
        }
        if (!isMtopResp) {
            logLimit("[resp-diag] finishEvent arg not MtopResponse: " + respCls.getName());
            return false;
        }

        Object api = Reflect.callMethod(resp, "getApi");
        // v1.0.8 🎣 详情媒体提取（不依赖接口名：含 imageInfos 键才真正解析）
        // v1.0.8 🎣 详情媒体提取（不依赖接口名：含 imageInfos 键才真正解析）
        if (api == null) {
            // 静默黑洞诊断：getApi 反射失败（方法被混淆改名）时响应将被整条丢弃
            logLimit("[resp-diag] getApi null on " + respCls.getName() + "（方法名混淆？响应丢弃）");
            return false;
        }
        String a = String.valueOf(api);
        String la = a.toLowerCase();
        // v1.4.4 ApiProtocol 网关全量响应落盘：不受下面 api 前缀 filter 限制，
        // 7.29.x 游戏任务接口走 ApiProtocol 网关时 api 名不匹配老前缀，先全文兜住
        try {
            ApiProtoDump.dumpResp(resp, respBody(resp, 200000));
        } catch (Throwable ignored) {
        }
        try {
        // v1.0.8 🎣 详情媒体提取（不依赖接口名：含 imageInfos 键才真正解析）
        // v1.0.8 🎣 详情媒体提取（不依赖接口名：含 imageInfos 键才真正解析）
            RhFishMedia.parseResponse(respBody(resp, 200000));
        } catch (Throwable ignored) {
        }
        // 闲鱼业务接口全量落盘（探测新链路需要）；排除高频噪音接口防刷盘
        // 注意 "mtop.taobao.idle" 不带点：兼容 mtop.taobao.idlemtopsearch.*（搜索域）
        // 11-10 补 "mtop.idle."：圈数挑战三接口（multi.day.challenge / challenge.idleCoinSign / interact.cycle.challenge）
        // 用这个短命名空间，之前响应全被丢弃导致只有请求没报文
        if ((la.startsWith("mtop.taobao.idle") || la.startsWith("mtop.idle."))
                && !la.contains("device.report")
                && !la.contains("ut.log")
                && !la.contains("highway.upload")
                && !la.contains("comment.list")
                && !la.contains("item.recommend")
                && !la.contains("bubble.show")) {
            String body = respBody(resp, -1);
            boolean written = writeTaskResp(a, body);
            if (written) {
                log("[task-resp] captured " + a);
                // v1.0.8 流量配对：请求-响应闭环 + elapsed + 状态
                String pairLine = RhTrafficPair.onResponse(a, body);
                if (pairLine != null) log(pairLine);
                // v1.0.8 token 捞取（响应体扫风控字段）
                RhTokenVault.scanResponse(a, body);
            }
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
            return written;
        }
        return false;
    }

    /**
     * v1.5.1 直调 Proxy callback 旁路入口：ApiDirect 万能 callback 的所有参数统一喂这里。
     * 内部走 handleMtopFinishEvent 的 isMtopResp 类名校验，非 MtopResponse 对象
     * （request/progress/字符串等）静默丢弃——callback 参数类型未知，靠校验兜底。
     * 弱网去重由 respHook 的 WeakHashMap 承担（此处为旁路，无 seen 登记，落盘幂等由
     * writeCacheFile 覆盖写保证——重复落盘只多写一次文件，无副作用）。
     */
    static void feedRespCandidate(Object resp, String api) {
        if (resp == null) return;
        try {
            boolean fed = handleMtopFinishEvent(null, resp);
            if (fed) {
                log("[direct-cb] captured via proxy callback: " + api);
            }
        } catch (Throwable t) {
            logLimit("[direct-cb] feed fail: " + t);
        }
    }

    /**
     * 方法级 fallback：构造 hook 失效的 ROM 上从消费端兜住响应。
     * 路径 A（原）：遍历 mtopsdk.mtop.infra.EventCenter 全部方法，凡参数表含
     *   MtopFinishEvent 的全部 hook，after 里扫描参数调 handleMtopFinishEvent。
     * 路径 B（7.28.60+，EventCenter 类名被混淆移除后新增）：直接 hook
     *   MtopResponse 的消费端方法（isApiSuccess/getBytedata/getStringData/
     *   getDataJsonObject/getRetCode）——业务解析响应必调其一，方法级 hook 与
     *   setApi/build 同机制已验证存活；after 里以 thisObject 喂
     *   handleMtopFinishEvent（WeakHashMap 引用级 3s 去重防同响应重复落盘）。
     */
    private static void armFallbackRespHook() {
        try {
            // findClass 是 Class.forName 直抛 CNFE（Reflect.java:19），EventCenter 在 7.28.60
            // 被混淆移除时曾把整个 fallback 炸进外层 catch、路径 B 永远挂不上（外部用户
            // OnePlus/Android17 实证）——统一改 findClassIfExists，各段独立 try 不互相阻断
            final Class<?> finishEvtCls = Reflect.findClassIfExists("mtopsdk.mtop.common.MtopFinishEvent", hostCl);
            if (finishEvtCls == null) {
                log("[compat] fallback target not found (MtopFinishEvent), give up");
                return;
            }
            int armed = 0;
            try {
                Class<?> ecCls = Reflect.findClassIfExists("mtopsdk.mtop.infra.EventCenter", hostCl);
                if (ecCls != null) {
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
                }
            } catch (Throwable ec) {
                logLimit("[compat] EventCenter fallback arm skipped: " + ec);
            }
            fallbackArmed = true;
            log("[compat] fallback armed on EventCenter: " + armed + " method(s) hooked");
            // 路径 B：MtopResponse 消费端方法级钩子（无论 EventCenter 是否存在都补挂，
            // 双保险；7.28.60 类迁包 common→domain，新旧包名双尝试）
            try {
                Class<?> respCls = null;
                for (String cn : new String[]{"mtopsdk.mtop.common.MtopResponse",
                        "mtopsdk.mtop.domain.MtopResponse"}) {
                    respCls = Reflect.findClassIfExists(cn, hostCl);
                    if (respCls != null) {
                        log("[compat] MtopResponse fallback target: " + cn);
                        break;
                    }
                }
                if (respCls != null) {
                    final java.util.Map<Object, Long> seen =
                            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Long>());
                    XC_MethodHook respHook = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object resp = param.thisObject;
                                if (resp == null) return;
                                long now = android.os.SystemClock.uptimeMillis();
                                Long last = seen.get(resp);
                                if (last != null && now - last < 3000) return;
                                // 去重只在成功落盘后登记：响应生命周期早期消费端方法
                                // （isApiSuccess 等）被调时 bytedata 可能尚未填充，若此时
                                // 占坑会把响应对象钉死在坏状态（外部用户 parse fail 根因）
                                boolean written = handleMtopFinishEvent(finishEvtCls, resp);
                                if (written) seen.put(resp, now);
                            } catch (Throwable t) {
                                logLimit("resp-fallback error: " + t);
                            }
                        }
                    };
                    int mArmed = 0;
                    for (String mn : new String[]{"isApiSuccess", "getBytedata", "getStringData",
                            "getDataJsonObject", "getRetCode"}) {
                        try {
                            Reflect.hookAllMethods(respCls, mn, respHook);
                            mArmed++;
                        } catch (Throwable ignored) {
                        }
                    }
                    log("[compat] fallback armed on MtopResponse: " + mArmed + " method(s) hooked");
                } else {
                    log("[compat] MtopResponse fallback: class not found in both packages");
                }
            } catch (Throwable t) {
                log("[compat] MtopResponse fallback arm FAILED: " + t);
            }
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

    /** 任务响应落盘：rh_resp.json 总线 + rh_resp_<api尾段>.json 分文件（避免 PC 端清空竞争）
     *  内容校验：非 { [ 开头（对象 toString/空串等坏内容）一律丢弃不落盘——
     *  坏文件被 callApi 读到会 parse fail 报「无响应」，宁缺勿坏让轮询等好内容。
     *  @return true = 实际落盘 */
    private static boolean writeTaskResp(String api, String body) {
        if (body == null || body.isEmpty()
                || (body.charAt(0) != '{' && body.charAt(0) != '[')) {
            // v1.5.5 改进 A：discard 行内联原文前 80 字符——日志里即可定性限流页/会话失效页，
            // 不再只有 len 要去设备上翻 raw 旁档
            String head = body == null ? "" : body.substring(0, Math.min(80, body.length()));
            logLimit("[task-resp] discard non-json body for " + api
                    + " (len=" + (body == null ? -1 : body.length()) + ") head=" + head.replace('\n', ' '));
            // v1.5.1 非 JSON 原文落 raw 旁档（+stamp）：2026-11-11 日志实证网关拒绝响应
            // （discard len=224）被丢弃后调用方只能看到「无响应」，拒绝原因无法定性。
            // raw 文件让 callAndWait 立即感知并把原文带回报告/日志。
            if (body != null && !body.isEmpty()) {
                String last = api == null ? "unknown" : api;
                int dot = last.lastIndexOf('.');
                if (dot >= 0) last = last.substring(dot + 1);
                writeCacheFile("rh_resp_" + last + ".json.raw", body);
                // v1.5.5 改进 C：固定名 .bad 旁档（不带 stamp，专供 callApi 短路）。
                // callApi 轮询见到它立即返回 GATEWAY_REJECT 归因，不再空等 30s——
                // (2) 号日志实测 30 次 timeout×30s≈15 分钟纯等待。正常响应到达时删除。
                writeCacheFile("rh_resp_" + last + ".json.bad",
                        body.substring(0, Math.min(200, body.length())));
            }
            return false;
        }
        writeCacheFile("rh_resp.json", body);
        String last = api == null ? "unknown" : api;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        writeCacheFile("rh_resp_" + last + ".json", body);
        // v1.5.5 改进 C：正常响应到达即清 .bad，防止旧拒绝档被下一次请求误配
        try {
            java.io.File bad = cacheFile("rh_resp_" + last + ".json.bad");
            if (bad != null && bad.exists()) bad.delete();
        } catch (Throwable ignored) {
        }
        return true;
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

    /** report-server 引擎类命令：依赖主进程协议栈（XModuleCenter 只在主进程注册），渲染进程一律拒绝 */
    private static boolean isEngineCmd(String req) {
        if (req == null) return false;
        String r = req.trim();
        String[] p = {"RUN", "COIN", "GAME", "POND", "OLIVER", "CASH"};
        for (int i = 0; i < p.length; i++) {
            if (r.startsWith(p[i])) return true;
        }
        return false;
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

    /** 报告类文件住持久区 files/：宿主的 cache/ 冷启动会被系统清空，报告跟着丢。
     *  读顺序 files/ -> cache/（后者兼容 v1.2.7 及以前留在 cache 的老文件）。 */
    private static String readReportFile(String name) {
        Context c = appCtx;
        if (c != null) {
            try {
                java.io.File dir = c.getExternalFilesDir(null);
                if (dir != null) {
                    java.io.File f = new java.io.File(dir, name);
                    if (f.exists() && f.length() > 0) {
                        byte[] b = new byte[(int) f.length()];
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        in.read(b);
                        in.close();
                        return new String(b, "UTF-8");
                    }
                }
            } catch (Throwable t) {
                log("readReportFile " + name + " FAIL: " + t);
            }
        }
        return readCacheFile(name);
    }

    private static void writeCacheFile(String name, String content) {
        try {
            java.io.File f = cacheFile(name);
            if (f == null) return;
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(content);
            w.close();
            // 同写一个毫秒戳旁路文件：/sdcard 的 mtime 只有 1~2s 精度，
            // 等待方（RhPondTask.callAndWait）靠它判断「这是本轮响应」而不是旧文件
            java.io.File s = cacheFile(name + ".stamp");
            if (s != null) {
                java.io.FileWriter sw = new java.io.FileWriter(s, false);
                sw.write(String.valueOf(System.currentTimeMillis()));
                sw.close();
            }
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

    /** 宿主 cache 目录（优先外部 cache）；v1.2.x 起供 FishTaskAuto 落盘用 */
    static java.io.File cacheDir() {
        java.io.File f;
        android.content.Context c = appCtx;
        if (c == null) return null;
        try {
            f = c.getExternalCacheDir();
            if (f != null && f.exists()) return f;
        } catch (Throwable ignored) {
        }
        try {
            f = c.getCacheDir();
            if (f == null) return null;
            if (!f.exists()) f.mkdirs();
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 持久目录（外部 files，退内部 files）。
     *  任务状态与报告都落这里：外部 cache 会随装包/清缓存整目录消失
     *  （v1.2.9 实测：一次 pm install -r 后 rh_cash_claim.txt 就没了，
     *  面板状态从「今日已开红包」退回「今日未执行」）。 */
    static java.io.File persistDir(android.content.Context ctx) {
        android.content.Context c = (ctx != null) ? ctx : appCtx;
        if (c == null) return null;
        try {
            java.io.File f = c.getExternalFilesDir(null);
            if (f == null) f = c.getFilesDir();
            if (f != null && !f.exists()) f.mkdirs();
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
