package com.piggyhand.fish.task;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;
import com.piggyhand.fish.core.RhRuntime;
import com.piggyhand.fish.net.RhReport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import de.robv.android.xposed.XposedBridge;

/**
 * 现金签到（天天红包「更多红包」任务面板）——2026-11-26。
 *
 * 两条线，接口全部来自 H5 包 pages/home/index.js：
 *  ① 自动领红包：scene.query 拉清单 → 按 status 推进
 *       INIT     → scene.receive(taskParams 全量)
 *       ACCEPTED → scene.trigger({deliveryId, implId, sceneId})
 *       AWARDING → scene.award({sceneId, deliveryId, implId, awardIndex})
 *     奖励是「红包个数」（rewards[].result.finalCount / ownCount，unit=个），不是金额。
 *  ② 自动开红包：signin.cash.main 取 redEnvNum → 循环 signin.cash.open 直到归零。
 *     main/open 都要求 data.asac（会话内从闲鱼自产请求里捕获，见 RhLocationContext），
 *     缺了恒报 FAIL_SYS_BIZPARAM_MISSED::缺少业务参数asac。包内不放任何固定 asac 字面量。
 *
 * LVL 门（LvlLedger，rh_task_lvl.json）：每个 deliveryId 当日最多推进 2 次，
 * 跨天 ensure 自动清零；失败也计数，避免反复打同一任务把账号钉进风控窗口。
 *
 * 触发实例：TaskRegistry 的 CASHSIGN / CASHOPEN 命令，或引擎就绪自启（开关见 ConfigManager）。
 */
public final class RhCashSignTask {

    /* ---------------- 接口名（外置：活动改名只动这里） ---------------- */
    static final String API_QUERY = "mtop.taobao.pentaprism.scene.query";
    static final String API_RECEIVE = "mtop.taobao.pentaprism.scene.receive";
    static final String API_TRIGGER = "mtop.taobao.pentaprism.scene.trigger";
    static final String API_AWARD = "mtop.taobao.pentaprism.scene.award";
    static final String API_MAIN = "mtop.idle.play.signin.cash.main";
    static final String API_OPEN = "mtop.idle.play.signin.cash.open";
    /** 广告任务：ad.query 由闲鱼自己的商城页发（拿 token），ad.event.push 由我们补 */
    static final String API_AD_QUERY = "mtop.taobao.idle.play.ad.query";
    static final String API_AD_PUSH = "mtop.taobao.idle.play.ad.event.push";

    /** 开红包单轮上限：redEnvNum 异常大时防死循环（正常十几到几十） */
    private static final int OPEN_MAX = 120;
    /** 单接口等待上限 */
    private static final long T_QUERY = 9000L;
    private static final long T_ACTION = 9000L;
    /** 等商城页自己发 ad.query（观察值 1~3s，给到 12s 防慢网） */
    private static final long T_AD_WAIT = 12000L;
    /** 商城停留秒数上下限：rewardDuration 由回包给（15/20），夹在区间内防脏值 */
    private static final int AD_BROWSE_MIN = 12;
    private static final int AD_BROWSE_MAX = 32;

    private static final Random RND = new Random();
    /** 进程内防重入：UI 连点 / 自启与手动撞车 */
    private static volatile boolean claiming = false;
    private static volatile boolean opening = false;

    private RhCashSignTask() {
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }

    /* ---------------- 引擎就绪自启 ---------------- */

    /** 引擎就绪调用：主进程 + 开关打开 + 当日未跑过才启动（异步，不阻塞自启线） */
    public static void autoRunDaily(final Context ctx) {
        try {
            String proc = null;
            try {
                proc = (String) Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return; // 只在主进程跑

            ConfigManager cfg = ConfigManager.load();
            final boolean doClaim = cfg == null || cfg.cashSignClaim;
            final boolean doOpen = cfg == null || cfg.cashSignOpen;
            if (!doClaim && !doOpen) {
                log("[cashsign] auto-run disabled by config");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(8000L); // 让自启线上其它引擎先起，避免同时抢响应通道
                        if (doClaim) runClaim(ctx);
                        if (doOpen) runOpen(ctx);
                    } catch (Throwable t) {
                        log("[cashsign] autorun fail: " + t);
                    }
                }
            }, "rh-cashsign-auto").start();
        } catch (Throwable t) {
            log("[cashsign] autoRunDaily err: " + t);
        }
    }

    /* ---------------- ① 自动领红包 ---------------- */

    /**
     * 全量推进任务面板：query → (receive|trigger|award)*，逐任务受 LVL 门限次。
     * @return 报告全文（同时落盘 rh_tasks_cashsign.txt）
     */
    public static String runClaim(Context ctx) {
        if (claiming) return "SKIP busy";
        claiming = true;
        long t0 = System.currentTimeMillis();
        RhReport rep = new RhReport();
        try {
            String scene = sceneOf();
            rep.append("现金签到·自动领红包  scene=").append(scene).append('\n');

            JSONObject q = call(ctx, API_QUERY, "{\"sceneId\":\"" + scene + "\"}", T_QUERY);
            if (q == null) {
                rep.append("查询任务清单超时（响应未落盘）\n");
                return finish(ctx, rep, t0, "rh_tasks_cashsign.txt", "[cashsign] claim");
            }
            JSONObject qd = q.optJSONObject("data");
            if (qd == null || !"success".equals(qd.optString("errorCode"))) {
                rep.append("查询失败: ").append(briefRet(q)).append('\n');
                return finish(ctx, rep, t0, "rh_tasks_cashsign.txt", "[cashsign] claim");
            }
            JSONArray list = qd.optJSONArray("model");
            int n = list == null ? 0 : list.length();
            rep.append("任务共 ").append(n).append(" 条，今日已完成 ")
                    .append(qd.optString("finishCount", "?")).append('\n');

            int done = 0, skip = 0, fail = 0;
            for (int i = 0; i < n; i++) {
                JSONObject task = list.optJSONObject(i);
                if (task == null) continue;
                JSONObject tp = task.optJSONObject("taskParams");
                String title = optTitle(task);
                String deliveryId = tp == null ? "" : tp.optString("deliveryId", "");
                String status = task.optString("status", "");
                if (deliveryId.length() == 0) continue;

                if ("COMPLETE".equals(status)) {
                    skip++;
                    continue;
                }
                String lvlKey = "cashsign:" + deliveryId;
                if (LvlLedger.skipShared(ctx, lvlKey)) {
                    rep.append("· ").append(title).append(" 当日已推进 2 次，跳过\n");
                    skip++;
                    continue;
                }
                String msg = advanceOne(ctx, task, tp, scene);
                // 无论成败都计数：失败重打只会加深风控，留给次日/手动
                LvlLedger.bumpShared(ctx, lvlKey);
                if (msg.startsWith("OK")) {
                    done++;
                    rep.append("✓ ").append(title).append(' ').append(msg).append('\n');
                } else {
                    fail++;
                    rep.append("✗ ").append(title).append(' ').append(msg).append('\n');
                }
                sleep(1100 + RND.nextInt(900)); // 任务间节奏抖动，避免固定间隔特征
            }
            rep.append("小结：推进 ").append(done).append(" · 跳过 ").append(skip)
                    .append(" · 失败 ").append(fail).append('\n');
            return finish(ctx, rep, t0, "rh_tasks_cashsign.txt", "[cashsign] claim");
        } catch (Throwable t) {
            rep.append("异常: ").append(t).append('\n');
            return finish(ctx, rep, t0, "rh_tasks_cashsign.txt", "[cashsign] claim");
        } finally {
            claiming = false;
        }
    }

    /** 单任务推进一轮：按 status 决定调哪个接口，最多推进到 AWARDING 并领掉 */
    private static String advanceOne(Context ctx, JSONObject task, JSONObject tp, String scene) {
        try {
            JSONObject p = tp == null ? new JSONObject() : tp;
            String status = task.optString("status", "");

            if ("INIT".equals(status)) {
                JSONObject r = call(ctx, API_RECEIVE, p.toString(), T_ACTION);
                String e = errOf(r);
                if (!"success".equals(e)) return "领取失败 " + e;
                status = "ACCEPTED";
                sleep(900 + RND.nextInt(600));
            }

            if ("ACCEPTED".equals(status)) {
                AdSpec spec = AdSpec.parse(task);
                if (spec != null) {
                    // 广告类任务：trigger 恒被 REQUEST_SOURCE_REJECT 拒，只能走 ad.event.push
                    String e = adComplete(ctx, spec);
                    if (e != null) return e;
                    sleep(1200L + RND.nextInt(600));
                    JSONObject t2 = findTask(ctx, scene, p.optString("deliveryId", ""));
                    if (t2 != null) {
                        JSONObject np = t2.optJSONObject("taskParams");
                        if (np != null) p = np;
                        status = t2.optString("status", "AWARDING");
                    } else {
                        status = "AWARDING";
                    }
                } else {
                    // trigger 只认三字段：带 fromToken/samplingRate 会被服务端按参数非法拒
                    JSONObject body = new JSONObject();
                    body.put("deliveryId", p.optString("deliveryId", ""));
                    body.put("implId", p.optString("implId", ""));
                    body.put("sceneId", p.optString("sceneId", scene));
                    JSONObject r = call(ctx, API_TRIGGER, body.toString(), T_ACTION);
                    String e = errOf(r);
                    if (!"success".equals(e)) return "完成上报失败 " + e;
                    JSONObject model = r.optJSONObject("data") == null ? null
                            : r.optJSONObject("data").optJSONObject("model");
                    if (model != null) {
                        // 回包 taskParams.implId 末段 +1（_0 → _1），领奖必须用新值
                        JSONObject np = model.optJSONObject("taskParams");
                        if (np != null) p = np;
                        status = model.optString("status", "AWARDING");
                    } else {
                        status = "AWARDING";
                    }
                }
                sleep(900 + RND.nextInt(600));
            }

            if ("AWARDING".equals(status)) {
                JSONObject body = new JSONObject();
                body.put("sceneId", p.optString("sceneId", scene));
                body.put("deliveryId", p.optString("deliveryId", ""));
                body.put("implId", p.optString("implId", ""));
                body.put("awardIndex", p.optString("awardIndex", "1"));
                JSONObject r = call(ctx, API_AWARD, body.toString(), T_ACTION);
                String e = errOf(r);
                String em = errMsgOf(r);
                if ("success".equals(e) || em.indexOf("AWARD_REPEAT") >= 0) {
                    return "OK 红包+" + awardCount(r) + "个";
                }
                return "领红包失败 " + e + " " + em;
            }
            if ("COMPLETE".equals(status)) return "OK 已完成";
            return "状态未识别 " + status;
        } catch (Throwable t) {
            return "异常 " + t;
        }
    }

    /* ---------------- ①-b 广告类任务（视频 / 电商商城） ---------------- */

    /**
     * 广告任务完成上报。两类，判据全在 assets.action 里：
     *  ① 视频类：action 内嵌 adEventPush（%7B...%7D），原样 push 即可，countdown 是契约字段；
     *  ② 电商商城类：action 只有 adPositionCode/scenarioId/sourceType，token 必须由闲鱼自己的
     *     CsjMallActivity 发 ad.query 领取——协议直发的 token 一律 FAIL_BIZ_SERVER_ERROR
     *     （服务端校验广告曝光会话）。所以这里先起 activity，再从响应落盘里取它的 token，
     *     等 rewardDuration 秒后补 push。
     *
     * @return null 成功；非 null 为失败文案
     */
    private static String adComplete(Context ctx, AdSpec spec) {
        if (spec.inlinePush != null) {
            JSONObject r = call(ctx, API_AD_PUSH, spec.inlinePush.toString(), T_ACTION);
            String e = errOf(r);
            // 已到上限/重复上报 = 服务端已认账（商城页自己的 reporter 可能刚推过），不算失败
            if (!"success".equals(e) && !alreadyCounted(e)) return "视频上报失败 " + e;
            return null;
        }
        long t0 = System.currentTimeMillis();
        if (!launchAds(ctx, spec.action)) return "商城页未拉起（需前台）";
        JSONObject push = waitMallPush(spec.code, t0);
        if (push == null) return "商城未取到 token（未曝光）";
        int dur = push.optInt("_dur", 20);
        if (dur < AD_BROWSE_MIN) dur = AD_BROWSE_MIN;
        if (dur > AD_BROWSE_MAX) dur = AD_BROWSE_MAX;
        // 停留时长是服务端校验项（rewardDuration），少一秒都可能被判未浏览
        sleep(dur * 1000L + 1500L + RND.nextInt(800));
        push.remove("_dur");
        JSONObject r = call(ctx, API_AD_PUSH, push.toString(), T_ACTION);
        String e = errOf(r);
        if (!"success".equals(e) && !alreadyCounted(e)) {
            return "商城上报失败 " + e + " " + errMsgOf(r);
        }
        return null;
    }

    /**
     * 起商城页。Deep link 直接 startActivity 落不到 CsjMallActivity：
     * 该 Activity 需要 extra fmfa_csj_mall_session_key，而会话（含 ad.query 发 token）
     * 是闲鱼路由拦截器建的。所以先调 AdsUtil.interceptRouterShowAds 走它自己的路由，
     * 失败再退回 deep link。
     */
    private static boolean launchAds(Context ctx, String action) {
        // 路由拦截器只认 Activity 上下文（内部先做 instance-of Activity 判定），拿到才调得动
        final android.app.Activity act = com.piggyhand.fish.core.RhTopActivity.get();
        if (act != null) {
            // 它内部 new Handler()：必须在有 Looper 的线程（主线程）调用，否则直接抛
            final boolean[] ok = new boolean[]{false};
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            Runnable call = new Runnable() {
                @Override
                public void run() {
                    try {
                        ClassLoader cl = act.getClassLoader();
                        Class<?> util = Class.forName("com.taobao.idlefish.ads.util.AdsUtil", false, cl);
                        Class<?> dlg = Class.forName("com.taobao.idlefish.protocol.ui.ILoadingDialog",
                                false, cl);
                        Class<?> cb = Class.forName(
                                "com.taobao.idlefish.ads.util.AdsUtil$InterceptShowAdsCallback", false, cl);
                        java.lang.reflect.Method m = util.getMethod("interceptRouterShowAds",
                                Context.class, String.class, dlg, cb);
                        Object r = m.invoke(null, act, action, null, null);
                        log("[cashsign] interceptRouterShowAds -> " + r);
                        ok[0] = Boolean.TRUE.equals(r);
                    } catch (Throwable t) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        log("[cashsign] interceptRouterShowAds fail: " + c);
                    } finally {
                        latch.countDown();
                    }
                }
            };
            try {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    call.run();
                } else {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(call);
                    latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Throwable t) {
                log("[cashsign] router post fail: " + t);
            }
            if (ok[0]) return true;
        } else {
            log("[cashsign] no top activity, skip router");
        }
        try {
            Intent it = Intent.parseUri(action, Intent.URI_INTENT_SCHEME);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            log("[cashsign] launchAds fail: " + t);
            return false;
        }
    }

    /**
     * 等商城 activity 自己写出的 ad.query 响应，取走其中的 adEventPush（含一次性 token）。
     * 只认 since 之后落盘的文件，避免拿到上一个任务的 token。
     */
    private static JSONObject waitMallPush(String code, long since) {
        long end = System.currentTimeMillis() + T_AD_WAIT;
        while (System.currentTimeMillis() < end) {
            try {
                if (com.piggyhand.fish.hook.FishMtopHook.respStamp(API_AD_QUERY) >= since - 1500L) {
                    String body = com.piggyhand.fish.hook.FishMtopHook.readRespText(API_AD_QUERY);
                    JSONObject o = body == null ? null : new JSONObject(body);
                    JSONObject pb = pickPush(o, code);
                    if (pb != null) return pb;
                }
            } catch (Throwable ignored) {
            }
            sleep(400);
        }
        return null;
    }

    /** 从 ad.query 回包挑出 adPositionCode 匹配的那条 adEventPush，附 _dur（停留秒数） */
    private static JSONObject pickPush(JSONObject resp, String code) {
        try {
            JSONObject d = resp == null ? null : resp.optJSONObject("data");
            if (d == null) return null;
            String attrs = d.optString("attributes", "");
            if (attrs.length() == 0) return null;
            JSONObject a = new JSONObject(attrs);
            JSONObject tc = a.optJSONObject("taskConfig");
            if (tc == null) return null;
            Iterator<String> it = tc.keys();
            while (it.hasNext()) {
                JSONObject blk = tc.optJSONObject(it.next());
                if (blk == null) continue;
                JSONObject pb = blk.optJSONObject("adEventPush");
                if (pb == null) continue;
                if (!code.equals(pb.optString("adPositionCode", ""))) continue;
                if (pb.optString("token", "").length() == 0) continue;
                // 服务端只认四字段：adPositionCode / eventType / token / implId
                JSONObject out = new JSONObject();
                out.put("adPositionCode", pb.optString("adPositionCode", ""));
                out.put("eventType", pb.optString("eventType", "award"));
                out.put("token", pb.optString("token", ""));
                out.put("implId", pb.optString("implId", ""));
                out.put("_dur", blk.optInt("rewardDuration", 20));
                return out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 上报返回这些错说明服务端已经记过这笔：商城页自身的 reporter / 上一次重试 */
    private static boolean alreadyCounted(String err) {
        return err != null && (err.indexOf("REACH_UPPER_LIMIT") >= 0
                || err.indexOf("AWARD_REPEAT") >= 0);
    }

    /** 按 deliveryId 重查单任务（推完拿新的 taskParams.implId，或确认已 AWARDING） */
    private static JSONObject findTask(Context ctx, String scene, String deliveryId) {
        try {
            JSONObject q = call(ctx, API_QUERY, "{\"sceneId\":\"" + scene + "\"}", T_QUERY);
            JSONObject d = q == null ? null : q.optJSONObject("data");
            JSONArray list = d == null ? null : d.optJSONArray("model");
            if (list == null) return null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                JSONObject tp = t == null ? null : t.optJSONObject("taskParams");
                if (tp != null && deliveryId.equals(tp.optString("deliveryId", ""))) return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 拆 fleamarket://showAds?... 的 query 参数（值做 URL 解码） */
    private static Map<String, String> parseAction(String action) {
        Map<String, String> m = new HashMap<>();
        if (action == null) return m;
        int q = action.indexOf('?');
        if (q < 0) return m;
        for (String kv : action.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            try {
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignored) {
            }
            m.put(k, v);
        }
        return m;
    }

    /** 广告任务规格：视频类带 inlinePush；商城类带 code + action */
    private static final class AdSpec {
        String code;
        String action;
        JSONObject inlinePush;

        static AdSpec parse(JSONObject task) {
            try {
                JSONObject assets = task.optJSONObject("assets");
                String action = assets == null ? "" : assets.optString("action", "");
                if (action.indexOf("showAds") < 0) return null;
                Map<String, String> q = parseAction(action);
                AdSpec s = new AdSpec();
                s.action = action;
                String inline = q.get("adEventPush");
                if (inline != null && inline.length() > 0) {
                    s.inlinePush = new JSONObject(inline); // 视频类：countdown 是契约字段，原样带上
                    return s;
                }
                String code = q.get("adPositionCode");
                if (code == null || code.length() == 0) return null;
                s.code = code;
                return s;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /* ---------------- ② 自动开红包 ---------------- */
    /**
     * 查 redEnvNum → 循环 open 直到归零。
     * 每 5 次重查一次 main 校准（open 回包不带剩余数，本地递减会与服务端漂移）。
     * @return 报告全文（落盘 rh_tasks_cashopen.txt）
     */
    public static String runOpen(Context ctx) {
        if (opening) return "SKIP busy";
        opening = true;
        long t0 = System.currentTimeMillis();
        RhReport rep = new RhReport();
        try {
            rep.append("现金签到·自动开红包\n");
            // asac 必须是本次会话捕获值：包内没有固定 asac，缺了就直说，不发缺参请求
            String asac = asacOf();
            if (asac == null || asac.length() == 0) {
                rep.append("未捕获 asac：先在闲鱼里打开一次天天红包页（或让它跑一次请求），再执行\n");
                return finish(ctx, rep, t0, "rh_tasks_cashopen.txt", "[cashsign] open");
            }
            int left = queryRedEnvNum(ctx);
            if (left < 0) {
                rep.append("查询剩余红包个数失败（main 超时）\n");
                return finish(ctx, rep, t0, "rh_tasks_cashopen.txt", "[cashsign] open");
            }
            rep.append("剩余红包 ").append(left).append(" 个\n");
            if (left == 0) {
                rep.append("无需开红包\n");
                return finish(ctx, rep, t0, "rh_tasks_cashopen.txt", "[cashsign] open");
            }

            int opened = 0, failed = 0;
            long total = 0L;
            String openBody = "{\"asac\":\"" + asac + "\"}"; // 复用同一份 body，避免循环里重复拼串
            int guard = Math.min(left, OPEN_MAX);
            for (int i = 0; i < guard; i++) {
                JSONObject r = call(ctx, API_OPEN, openBody, T_ACTION);
                String e = errOf(r);
                if (!"success".equals(e)) {
                    failed++;
                    rep.append("✗ 第 ").append(i + 1).append(" 次失败: ").append(briefRet(r)).append('\n');
                    // 频控/网络类失败退避一次再试；连续 2 次失败收手，别把风控打穿
                    if (failed >= 2) break;
                    sleep(2600L + RND.nextInt(1200));
                    continue;
                }
                opened++;
                JSONObject d = r.optJSONObject("data");
                if (d != null) {
                    long amt = parseLong(d.optString("amount", "0"));
                    total += amt;
                    rep.append("· 开出 ").append(d.optString("amount", "?"))
                            .append("（账户 ").append(d.optString("accountNo", "?")).append("）\n");
                }
                if ((i + 1) % 5 == 0) {
                    int now = queryRedEnvNum(ctx);
                    if (now >= 0) {
                        rep.append("  ↻ 校准剩余 ").append(now).append(" 个\n");
                        if (now == 0) break;
                    }
                }
                sleep(1200L + RND.nextInt(900));
            }

            int tail = queryRedEnvNum(ctx);
            rep.append("已开 ").append(opened).append(" 个，合计 ").append(total)
                    .append("，剩余 ").append(tail < 0 ? "?" : String.valueOf(tail)).append(" 个\n");
            return finish(ctx, rep, t0, "rh_tasks_cashopen.txt", "[cashsign] open");
        } catch (Throwable t) {
            rep.append("异常: ").append(t).append('\n');
            return finish(ctx, rep, t0, "rh_tasks_cashopen.txt", "[cashsign] open");
        } finally {
            opening = false;
        }
    }

    /** main 接口取 redEnvNum；失败返回 -1 */
    static int queryRedEnvNum(Context ctx) {
        try {
            JSONObject r = call(ctx, API_MAIN, "{\"asac\":\"" + asacOf() + "\"}", T_QUERY);
            if (r == null) return -1;
            JSONObject d = r.optJSONObject("data");
            if (d == null) return -1;
            if (d.has("redEnvNum")) return d.optInt("redEnvNum", 0);
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 状态条文案：读最近一次落盘报告的末行（UI 面板用，不产生任何请求） */
    public static String stateText(Context ctx, boolean openLine) {
        try {
            String name = openLine ? "rh_tasks_cashopen.txt" : "rh_tasks_cashsign.txt";
            String txt = readReport(ctx, name);
            if (txt == null || txt.length() == 0) return openLine ? "今日未开" : "今日未领";
            String[] lines = txt.split("\n");
            // 报告的末行恒是「耗时 Ns」，对面板没意义，按优先级挑真正有信息的那一行：
            // 领红包看"小结：推进…"，开红包看"已开 N 个…剩余 X 个"
            String fallback = null;
            for (int i = lines.length - 1; i >= 0; i--) {
                String s = lines[i].trim();
                if (s.length() == 0) continue;
                int sp = s.indexOf(' ');
                String body = sp > 0 && sp <= 9 ? s.substring(sp + 1) : s;
                if (body.startsWith("耗时")) continue;
                if (fallback == null) fallback = body;
                boolean hit = openLine
                        ? (body.startsWith("已开") || body.startsWith("剩余红包")
                            || body.startsWith("无需开红包") || body.startsWith("查询剩余"))
                        : (body.startsWith("小结：") || body.startsWith("任务共")
                            || body.startsWith("查询失败") || body.startsWith("查询任务清单超时"));
                if (hit) return clip(body);
            }
            return fallback == null ? "今日未执行" : clip(fallback);
        } catch (Throwable t) {
            return "状态未知";
        }
    }

    /** 面板一行放不下，超长截断（保留尾部更关键的"剩余 X 个"） */
    private static String clip(String body) {
        if (body.length() <= 30) return body;
        return body.substring(0, 29) + "…";
    }


    /* ---------------- 通道与工具 ---------------- */

    /**
     * 走标准 mtopsdk（ApiMtop.fire）+ 响应文件轮询。
     * asac 同时进 data 与 header：H5 是 ext_headers.asac + data.asac 双份，缺任一份都可能被拒。
     */
    private static JSONObject call(Context ctx, String api, String dataJson, long timeout) {
        try {
            Map<String, String> hdr = new HashMap<>();
            String asac = asacOf();
            if (asac != null && asac.length() > 0) hdr.put("asac", asac);
            return RhCashClaim.callMtop(ctx, api, "1.0", dataJson, hdr, null, timeout);
        } catch (Throwable t) {
            log("[cashsign] call " + api + " fail: " + t);
            return null;
        }
    }

    /** 场景 ID：配置优先，空则用默认 7066 */
    static String sceneOf() {
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg != null && cfg.cashSignScene != null && cfg.cashSignScene.trim().length() > 0) {
                return cfg.cashSignScene.trim();
            }
        } catch (Throwable ignored) {
        }
        return "7066";
    }

    /**
     * asac：配置 > 运行时捕获（RhLocationContext，来自闲鱼自产请求并持久化到 rh_loc.json）。
     * 都没有 → 返回 null，由调用方判空后放弃，不发缺参请求（缺参请求会记账进风控）。
     */
    static String asacOf() {
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg != null && cfg.cashSignAsac != null && cfg.cashSignAsac.trim().length() > 0) {
                return cfg.cashSignAsac.trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            String a = RhLocationContext.asac();
            if (a != null && a.trim().length() > 0) return a.trim();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 会话 asac 是否可用（面板/报告文案用） */
    static boolean hasAsac() {
        String a = asacOf();
        return a != null && a.length() > 0;
    }

    private static String errOf(JSONObject r) {
        if (r == null) return "NO_RESP";
        JSONObject d = r.optJSONObject("data");
        if (d != null && d.has("errorCode")) return d.optString("errorCode", "");
        String ret = briefRet(r);
        return ret.startsWith("SUCCESS") ? "success" : ret;
    }

    private static String errMsgOf(JSONObject r) {
        if (r == null) return "";
        JSONObject d = r.optJSONObject("data");
        return d == null ? "" : d.optString("errorMsg", "");
    }

    /** 领奖回包里的红包个数：rewards[0].result.ownCount，退 finalCount */
    private static String awardCount(JSONObject r) {
        try {
            JSONObject m = r.optJSONObject("data").optJSONObject("model");
            JSONArray rw = m == null ? null : m.optJSONArray("rewards");
            if (rw == null || rw.length() == 0) return "?";
            JSONObject res = rw.optJSONObject(0).optJSONObject("result");
            if (res == null) return "?";
            String c = res.optString("ownCount", "");
            if (c.length() == 0) c = res.optString("finalCount", "?");
            return c;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String optTitle(JSONObject task) {
        JSONObject a = task.optJSONObject("assets");
        String t = a == null ? "" : a.optString("title", "");
        return t.length() == 0 ? ("任务" + task.optString("id", "")) : t;
    }

    private static String briefRet(JSONObject r) {
        if (r == null) return "NO_RESP";
        JSONArray ret = r.optJSONArray("ret");
        if (ret == null || ret.length() == 0) return "NO_RET";
        return ret.optString(0, "");
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 报告落盘：持久区（files）为主，cache 一份给 UI 老读取路径兜底 */
    private static String finish(Context ctx, RhReport rep, long t0, String name, String tag) {
        rep.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000L).append("s\n");
        String all = rep.toString();
        boolean ok = false;
        try {
            File dir = RhRuntime.persistDir(ctx);
            if (dir != null) {
                FileWriter w = new FileWriter(new File(dir, name), false);
                w.write(all);
                w.close();
                ok = true;
            }
        } catch (Throwable t) {
            log(tag + " write persist fail: " + t);
        }
        try {
            File c = RhRuntime.cacheDir();
            if (c != null) {
                FileWriter w = new FileWriter(new File(c, name), false);
                w.write(all);
                w.close();
            }
        } catch (Throwable ignored) {
        }
        if (!ok) log(tag + " REPORT NOT PERSISTED: " + name);
        log(all);
        return all;
    }

    private static String readReport(Context ctx, String name) {
        try {
            File dir = RhRuntime.persistDir(ctx);
            if (dir != null) {
                File f = new File(dir, name);
                if (f.exists() && f.length() > 0) return readAll(f);
            }
        } catch (Throwable ignored) {
        }
        try {
            File c = RhRuntime.cacheDir();
            if (c != null) {
                File f = new File(c, name);
                if (f.exists()) return readAll(f);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            in.read(b);
            in.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
