package com.piggyhand.fish.hook;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.config.RhTokenVault;
import com.piggyhand.fish.core.Diag;
import com.piggyhand.fish.core.EngineHook;
import com.piggyhand.fish.core.Reflect;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;
import com.piggyhand.fish.net.ApiDirect;
import com.piggyhand.fish.net.ApiProtoDump;
import com.piggyhand.fish.net.RhTrafficPair;
import com.piggyhand.fish.ui.MainActivity;

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

    /** 引擎事件回调（Phase 2b 依赖倒置）：task 分发经 core.EngineHook 接口，
     *  MainHook 装配时 setEngineHook(TaskRegistry.get())——hook 包不再 import task 包。 */
    private static volatile EngineHook engineHook;

    /** MainHook（root 装配层）调用，进程内一次 */
    public static void setEngineHook(EngineHook h) {
        engineHook = h;
    }

    private static EngineHook hookOrNull() {
        return engineHook;
    }

    /** 命令转发：经 EngineHook 交 TaskRegistry 分发（Phase 2b），装配缺失时回退 fallback */
    static String dispatch(String cmd, String arg, String fallback) {
        EngineHook h = hookOrNull();
        String r = h != null ? h.onCommand(cmd, appCtx, arg) : null;
        return r != null ? r : fallback;
    }


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
    public static volatile Context appCtx; // 跨包共享：task/core 模块取宿主上下文（模块化 2026-11-15）
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
        // Phase 2a-2 依赖倒置：net 层经 RespSink 接口路由响应事件，ApiDirect 不再 import hook 包
        com.piggyhand.fish.net.RespChannel.install(new com.piggyhand.fish.net.RespSink() {
            @Override
            public void feedRespCandidate(Object resp, String api) {
                FishMtopHook.feedRespCandidate(resp, api);
            }

            @Override
            public void noteDirectSent(String api) {
                FishMtopHook.noteDirectSent(api);
            }
        });
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
                    // asac/token 等会话凭证脱敏后再落盘（2026-11-26，凭据不得明文进 rh_file.log）
                    log("[mtop-req] data = " + com.piggyhand.fish.core.RhSecret.maskJson(d));
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
            // Phase 2a 依赖倒置：core/task 经 RhRuntime 取上下文，不再引 hook 包（单写点同步）
            com.piggyhand.fish.core.RhRuntime.appCtx = appCtx;
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
                    // 全自动任务引擎入口（Phase 2b:task 分发经 EngineHook 接口）
                    if ("__RUN_TASKS__".equals(api)) {
                        EngineHook h = hookOrNull();
                        if (h != null) h.onCommand("LAUNCH_TASKS", appCtx, intent.getStringExtra("extras"));
                        log("[tasks] engine launched via broadcast");
                        return;
                    }
                    // 报告回传：把上次钓鱼结果经广播发回模块 UI（免跨 App 存储权限）
                    if ("__GET_RESULT__".equals(api)) {
                        final String rep = RespStore.readReportFile("rh_tasks_result.txt");
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
                        data = RespStore.readCacheFile("rh_data.json");
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
            // 每日自动跑（免电脑值守）：Phase 2b 起 10 条自启线整体迁入 TaskRegistry.onEngineReady，
            // hook 层只发就绪事件——就绪时序、各引擎内部延迟与开关判断零变化
            EngineHook h0 = hookOrNull();
            if (h0 != null) {
                h0.onEngineReady(appCtx);
            } else {
                // 防御：装配缺失时跳过自启（正常时序下 MainHook 先于 Application attach 装配）
                log("[autorun] engine hook not installed, daily auto-run skipped");
            }
            // 报告 Socket 服务（仅主进程）：模块 UI 经 127.0.0.1:27715 免权限读取钓鱼报告，
            // 绕过 MIUI 跨 App 广播拦截与 scoped storage
            ReportServer.start();
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
    static String wvResumeAll() {
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
                            RespStore.writeCacheFile("rh_wv_res.txt", out.toString());
                        }
                    });
                    out.append("POSTED url=").append(urlOf(wv)).append('\n');
                } catch (Throwable t2) {
                    out.append("ERR ").append(t2).append('\n');
                    log("[wv-resume] post fail: " + t2);
                }
            }
        }
        RespStore.writeCacheFile("rh_wv_res.txt", out.toString());
        return "OK started";
    }

    /** v1.5.1 强制重载所有捕获的 WebView 当前 URL：重建页面 JS 上下文，
     *  U4 renderer 挂起导致 evaluate 无响应时的兜底。结果落盘 rh_wv_res.txt。 */
    static String wvReloadAll() {
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
                        RespStore.writeCacheFile("rh_wv_res.txt", out.toString());
                    }
                });
                out.append("RELOAD-POSTED url=").append(urlOf(wv)).append('\n');
            } catch (Throwable t2) {
                out.append("ERR ").append(t2).append('\n');
            }
        }
        RespStore.writeCacheFile("rh_wv_res.txt", out.toString());
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
    static String wvEnableDebug() {
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
                        RespStore.writeCacheFile("rh_wv_res.txt", out.toString());
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
                RespStore.writeCacheFile("rh_wv_res.txt", snap);
            }
        }, "rh-wv-debug-sys").start();
        return "OK started";
    }

    /** v1.5.1 遍历捕获的 WebView 实例：evaluateJavascript（直调，UC 内核不做线程检查；
     *  每实例返回值落盘 rh_wv_eval.txt，多实例以 ---- 分隔，1.5s 后快照覆盖写）。 */
    static String wvEvalAll(final String js) {
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
                RespStore.writeCacheFile("rh_wv_eval.txt", snap);
            }
        }, "rh-wv-eval-write").start();
        RespStore.writeCacheFile("rh_wv_eval.txt", out.toString());
        return "OK started";
    }


    /** v1.4.5 主线程专用：对单个 WebView 反射执行 evaluateJavascript（回调丢弃，结果走 title 桥） */
    static void invokeEvalOne(Object wv, String js) throws Exception {
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

    // 报告/命令回环服务已迁 ReportServer（优化6），hook() 内改 ReportServer.start()

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
        // 11-26 补 "mtop.taobao.pentaprism"：天天红包「更多红包」任务场景四接口
        // （scene.query / trigger / receive / award），原白名单不含该域名 → 响应只进请求侧不进落盘
        if ((la.startsWith("mtop.taobao.idle") || la.startsWith("mtop.idle.")
                || la.startsWith("mtop.taobao.pentaprism"))
                && !la.contains("device.report")
                && !la.contains("ut.log")
                && !la.contains("highway.upload")
                && !la.contains("comment.list")
                && !la.contains("item.recommend")
                && !la.contains("bubble.show")) {
            String body = respBody(resp, -1);
            // P1-D：mini.game 响应树 appkey 收割（channel/center/detail 全覆盖，宿主+模块两侧）
            if (la.contains("mini.game")) {
                try {
                    com.piggyhand.fish.net.RhAppkeyIndex.harvestTree(
                            new org.json.JSONObject(body).optJSONObject("data"));
                } catch (Throwable ignored) {
                }
            }
            boolean written = RespStore.writeTaskResp(a, body);
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
                    // Phase 2b:金币记录经 EngineHook 下发,parse/record 细节在 TaskRegistry
                    EngineHook hCoin = hookOrNull();
                    if (hCoin != null) hCoin.onCoinLevel(cv);
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
    public static void feedRespCandidate(Object resp, String api) { // 跨包共享：net.ApiDirect 响应通道（模块化 2026-11-15）
        if (resp == null) return;
        try {
            // 非响应载体直接丢弃，别进 resp-diag 噪音：回调里混着 Context/枚举/字符串参数
            if (resp instanceof String || resp instanceof Number || resp instanceof Boolean) return;
            // com.taobao.idlefish.protocol.net.api.Phase 是枚举（request/response/callback 阶段标记），
            // ApiCallBack.onProcess 的参数，曾被当成"响应但类名不认"刷屏诊断日志
            if (resp.getClass().getName().endsWith(".Phase")) return;
            // ① 闲鱼自有协议栈响应（ApiCallBack.onSuccess 参数）
            if (feedProtoResp(resp)) return;
            // ② 标准 mtopsdk 响应
            boolean fed = handleMtopFinishEvent(null, resp);
            if (fed) {
                log("[direct-cb] captured via proxy callback: " + api);
            }
        } catch (Throwable t) {
            logLimit("[direct-cb] feed fail: " + t);
        }
    }

    /**
     * 闲鱼自有协议栈响应落盘（2026-11-26）。
     * ApiCallBack.onSuccess 的唯一参数类型 com.taobao.idlefish.protocol.net.ResponseParameter，
     * 字段 api/version/code/msg/ret/data/mtopBaseReturn——不是 MtopResponse 子类，
     * 原 handleMtopFinishEvent 的类名校验会整条丢弃，导致 scene.* 直调"发了没收"。
     * 归一成 mtop 风格 body（{"api","v","ret":[code::msg],"data"}）写 rh_resp_<crc>.json，
     * 引擎 callApi 与 CALL 命令不用改读法。
     * @return true = 命中 ResponseParameter（无论落盘成败，调用方不再走 mtopsdk 分支）
     */
    private static boolean feedProtoResp(Object resp) {
        boolean isRP = false;
        for (Class<?> k = resp.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().endsWith(".ResponseParameter")) {
                isRP = true;
                break;
            }
        }
        if (!isRP) return false;
        Object apiO = null, verO = null, codeO = null, msgO = null, dataO = null, retO = null, baseO = null;
        try { apiO = Reflect.callMethod(resp, "getApi"); } catch (Throwable ignored) {}
        try { verO = Reflect.callMethod(resp, "getVersion"); } catch (Throwable ignored) {}
        try { codeO = Reflect.callMethod(resp, "getCode"); } catch (Throwable ignored) {}
        try { msgO = Reflect.callMethod(resp, "getMsg"); } catch (Throwable ignored) {}
        try { dataO = Reflect.callMethod(resp, "getData"); } catch (Throwable ignored) {}
        try { retO = Reflect.callMethod(resp, "getRet"); } catch (Throwable ignored) {}
        try { baseO = Reflect.callMethod(resp, "getMtopBaseReturn"); } catch (Throwable ignored) {}
        String api = apiO == null ? "unknown" : String.valueOf(apiO);
        String code = codeO == null ? "" : String.valueOf(codeO);
        String msg = msgO == null ? "" : String.valueOf(msgO);
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("api", api);
            root.put("v", verO == null ? "1.0" : String.valueOf(verO));
            org.json.JSONArray ret = new org.json.JSONArray();
            if (retO instanceof Object[]) {
                for (Object r : (Object[]) retO) ret.put(String.valueOf(r));
            }
            // scene.* 的成功判定在 JS 侧是 data.errorCode==="success"，
            // code/msg 也要以 mtop ret 形态留给引擎：ret[0] = code::msg
            if (ret.length() == 0 && (code.length() > 0 || msg.length() > 0)) {
                ret.put(code + "::" + msg);
            }
            root.put("ret", ret);
            Object payload = dataO != null ? dataO : baseO;
            if (payload != null) root.put("data", jsonOf(payload, 0));
            root.put("proto", Boolean.TRUE); // 标记来源，便于与 mtopsdk 落盘区分
            String body = root.toString();
            boolean written = RespStore.writeTaskResp(api, body);
            if (written) {
                log("[task-resp] captured(proto) " + api + " code=" + code);
                try {
                    RhTrafficPair.onResponse(api, body);
                } catch (Throwable ignored) {
                }
            } else {
                logLimit("[task-resp] proto discard " + api + " code=" + code + " msg=" + msg);
            }
        } catch (Throwable t) {
            logLimit("[task-resp] proto fail " + api + ": " + t);
        }
        return true;
    }

    /**
     * 把任意响应载荷转成 org.json 可写的结构。
     * fastjson 的 JSONObject/JSONArray 分别实现 Map/Iterable，这里靠接口类型递归，
     * 不引第三方依赖（模块只 compileOnly Xposed API）。depth 上限防自引用递归。
     */
    private static Object jsonOf(Object o, int depth) {
        if (o == null) return org.json.JSONObject.NULL;
        if (depth > 8) return String.valueOf(o);
        if (o instanceof String || o instanceof Number || o instanceof Boolean) return o;
        if (o instanceof java.util.Map) {
            org.json.JSONObject j = new org.json.JSONObject();
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) o).entrySet()) {
                try {
                    j.put(String.valueOf(e.getKey()), jsonOf(e.getValue(), depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return j;
        }
        if (o instanceof Iterable) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Object v : (Iterable<?>) o) a.put(jsonOf(v, depth + 1));
            return a;
        }
        if (o.getClass().isArray()) {
            org.json.JSONArray a = new org.json.JSONArray();
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) {
                a.put(jsonOf(java.lang.reflect.Array.get(o, i), depth + 1));
            }
            return a;
        }
        String s = String.valueOf(o);
        if (s.startsWith("{") || s.startsWith("[")) {
            try {
                return new org.json.JSONTokener(s).nextValue();
            } catch (Throwable ignored) {
            }
        }
        return s;
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
    public static void noteDirectSent(final String api) { // 跨包共享：net.ApiDirect（模块化 2026-11-15）
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

    // 响应/报告文件通道与 writeTaskResp 已迁 RespStore（优化6）——
    // FishMtopHook 与 ReportServer、task 执行器共用；logLimit 留本地（resp-diag 限频窗口）。

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
                                RespStore.writeTaskResp(api == null ? "hijack" : String.valueOf(api),
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
    public static java.io.File cacheDir() { // 跨包共享已下沉 core.RhRuntime（Phase 2a），此处保留包内便捷委托
        return com.piggyhand.fish.core.RhRuntime.cacheDir();
    }

    /** 持久目录（外部 files，退内部 files）。
     *  任务状态与报告都落这里：外部 cache 会随装包/清缓存整目录消失
     *  （v1.2.9 实测：一次 pm install -r 后 rh_cash_claim.txt 就没了，
     *  面板状态从「今日已开红包」退回「今日未执行」）。 */
    public static java.io.File persistDir(android.content.Context ctx) { // 已下沉 core.RhRuntime（Phase 2a），此处保留包内便捷委托
        return com.piggyhand.fish.core.RhRuntime.persistDir(ctx);
    }
}
