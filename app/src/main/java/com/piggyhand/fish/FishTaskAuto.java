package com.piggyhand.fish;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 闲鱼币任务全自动引擎（接口链路定位自闲鱼 7.25.31 dex 静态分析）。
 * 进程内串行执行：direct 发请求 → 轮询响应分文件 → 解析 → 下一步。
 *
 * 流程：
 *  1. queryolivertasklist {"sceneId":496} 拉任务列表
 *  2. 分桶：领奖(COMPLETE+needBenefit / AWARD) / ecMall广告(showAds+adType=ecMall → query拿token→push award)
 *           / 触发(ACCEPTED+clickFinish|EXCUTE|PLAINVISIT|VISIT|SYSTEM_SWITCH)
 *           / 搜一搜(xianyu/search.html → trigger 带 URL 指纹)
 *           / 浏览(ACCEPTED+idleCoinFeesAnchor → nextfresh 拉商品逐个 trigger(implId))
 *  3. 可选：签到 coin.signin / 擦亮 item.polish v2.0 / 卖鱼 coin.fishing.sold / 小店 daily.benefit
 *  4. 结果写 rh_tasks_result.json
 *
 * 触发：am broadcast -a com.piggyhand.fish.MTOP_CALL --es api __RUN_TASKS__
 *       [--es extras '{"signin":true,"polish":false,"sold":false,"shop":false,"browse":true}']
 */
public final class FishTaskAuto {

    private static final String TASK_LIST_API = "mtop.taobao.idle.task.queryolivertasklist";
    private static final String TASK_BENEFIT_API = "mtop.taobao.idle.task.getolivertaskbenefit";
    private static final String TASK_TRIGGER_API = "mtop.taobao.idle.task.uploadolivertasktrigger";
    private static final String NEXT_FRESH_API = "mtop.taobao.idlehome.idle.coin.nextfresh";
    private static final String SEARCH_API = "mtop.taobao.idlemtopsearch.search";
    private static final String AD_QUERY_API = "mtop.taobao.idle.play.ad.query";
    private static final String AD_EVENT_API = "mtop.taobao.idle.play.ad.event.push";
    private static final String AD_EVENT_QUERY_API = "mtop.taobao.idle.play.ad.event.query";
    private static final String SIGNIN_API = "mtop.taobao.idle.coin.signin";
    // 玩一玩/圈数挑战面板的 7 天签到（与任务面板 coin.signin 是两个独立入口）。
    // 1.1.2：asac/fishAdCode/gps 全部动态取自本机捕获（RhLocationContext），缺则省略字段
    private static final String PLAY_SIGN_API = "mtop.taobao.idle.play.sign.in";
    // 圈数挑战寻宝地图：init 查状态（location/remainStep/mapVersion），roll 掷骰前进（沿途奖励自动结算）。
    // 10-17 两次真实点击抓包：version=3 固定、outId=客户端毫秒时间戳、locationBefore=上次位置
    private static final String MAP_INIT_API = "mtop.taobao.idle.treasure.hunt.map.init";
    private static final String MAP_ROLL_API = "mtop.taobao.idle.treasure.hunt.map.roll";
    // 背包道具（10-20 协议定案）：list 拉背包；use=权益类直兑（刮刮卡）；骰子用 map.roll 掷（diceId=configId）
    private static final String PROP_LIST_API = "mtop.taobao.idle.play.prop.list";
    private static final String PROP_USE_API = "mtop.taobao.idle.play.prop.use";
    private static final String POLISH_API = "mtop.taobao.idle.item.polish";
    private static final String SHOP_BENEFIT_API = "mtop.taobao.idle.startup.shop.daily.benefit";
    private static final String LOTTERY_FEEDS_API = "mtop.taobao.idle.coin.lottery.feeds";
    private static final String LOTTERY_BET_API = "mtop.taobao.idle.coin.lottery.bet";

    /** 玩一玩签到请求体动态拼装（1.1.2）：asac/fishAdCode/gps 均为本机捕获值，缺则省略字段 */
    private static String playSignData() {
        StringBuilder sb = new StringBuilder("{");
        String asac = RhLocationContext.asac();
        if (asac != null) sb.append("\"asac\":\"").append(asac).append("\",");
        sb.append(RhLocationContext.jsonPair());
        sb.append("\"playId\":4,\"usertoken\":\"\"}");
        return sb.toString();
    }

    private static final int REQ_TIMEOUT_S = 30;
    private static final int BROWSE_MAX_STEPS = 60;   // 浏览任务单次最多上报次数保护
    private static final long BROWSE_GAP_MS = 5000;   // 浏览上报基础间隔

    private final ClassLoader cl;
    private final String cacheDir;
    private final Set<String> processed = new HashSet<>();
    private final StringBuilder report = new StringBuilder();
    private volatile boolean abort = false;
    private static android.content.Context appCtx;
    /** 每任务 Toast 气泡开关（config.toastNotify，默认开） */
    private static volatile boolean toastOn = true;
    /** 每日擦亮开关（配置页 polish；extras 显式传参优先） */
    private static volatile boolean polishOn = true;

    private FishTaskAuto(ClassLoader cl, String cacheDir) {
        this.cl = cl;
        this.cacheDir = cacheDir;
    }

    /** 防重入：一轮未跑完时忽略新触发（自动跑与手动广播可能撞车） */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /* -------- 探测调度（B+C 方案 10-17）：轻量拉列表按需钓鱼，替代小时级盲跑 -------- */
    /** 常规探测间隔：15min 一次只读列表请求 */
    private static final long PROBE_INTERVAL_MS = 15 * 60_000L;
    /** 钓鱼后加密探测间隔（紧跟服务端补发节奏） */
    private static final long PROBE_BURST_MS = 5 * 60_000L;
    /** 探测失败退避（mtop 未热/网络抖动，宁慢勿漏） */
    private static final long PROBE_FAIL_MS = 3 * 60_000L;
    /** 当日云控拒绝缓存：PARAM_ILLEGAL/FROM_APP_REJECT 的任务当日不再重试，次日清零 */
    private static final java.util.Set<String> rejectedToday =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String rejectedDay = "";

    /** 记录当日被云控拒绝的任务（跨轮次有效，日期翻转自动清空） */
    private static void markRejected(String did) {
        try {
            String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
            if (!today.equals(rejectedDay)) {
                rejectedDay = today;
                rejectedToday.clear();
            }
            rejectedToday.add(did);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 轻量探测：拉一次任务列表，统计可跑数量（可领取 + 未被当日拒绝的可触发任务）。
     * 返回 -1 表示探测失败（通道未热/网络），0=空池，>0=可跑数。单次仅 1 个只读请求。
     */
    private static int probeRunnableCount(Context ctx) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            java.io.File ext = ctx.getExternalCacheDir();
            String dir = ext != null ? ext.getAbsolutePath()
                    : "/storage/emulated/0/Android/data/com.taobao.idlefish/cache";
            FishTaskAuto probe = new FishTaskAuto(cl, dir);
            org.json.JSONObject resp = probe.callApi(TASK_LIST_API, "2.0",
                    "{\"sceneId\":496}", "探测");
            List<org.json.JSONObject> tasks = probe.extractTasks(resp);
            if (tasks == null) return -1;
            int n = 0;
            for (org.json.JSONObject t : tasks) {
                String did = str(t, "deliveryId");
                if (did == null) continue;
                String status = str(t, "idleTaskStatus");
                if ("AWARD".equals(status)) { n++; continue; }
                if ("COMPLETE".equals(status)) {
                    org.json.JSONObject tb = t.optJSONObject("taskBenefitDTO");
                    if (tb != null && "true".equals(optStr(tb, "needBenefit"))) n++;
                    continue;
                }
                if ("ACCEPTED".equals(status) && !rejectedToday.contains(did)) n++;
            }
            return n;
        } catch (Throwable t) {
            log("[probe] error: " + t);
            return -1;
        }
    }


    /** 广播入口（receiver 线程调用，内部自起工作线程） */
    public static void launch(Context ctx, String extrasJson) {
        if (!RUNNING.compareAndSet(false, true)) {
            log("[tasks] engine already running, trigger ignored");
            return;
        }
        appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            ConfigManager tcfg = ConfigManager.load();
            toastOn = tcfg == null || tcfg.toastNotify;
            polishOn = tcfg == null || tcfg.polish;
        } catch (Throwable ignored) {
        }
        final ClassLoader cl = ctx.getClassLoader();
        java.io.File ext = ctx.getExternalCacheDir();
        final String dir = ext != null ? ext.getAbsolutePath()
                : "/storage/emulated/0/Android/data/com.taobao.idlefish/cache";
        new Thread(() -> {
            try {
                new FishTaskAuto(cl, dir).run(extrasJson);
            } finally {
                RUNNING.set(false);
            }
        }, "rh-tasks").start();
    }

    /** 主线程 Toast 气泡（任务完成逐项提示）；全程 try-catch，任何异常不影响宿主 */
    private static void toast(String msg) {
        if (!toastOn || appCtx == null) return;
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(appCtx, msg,
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 从签到/小店等通用响应中挖 rewards JSON 串（供 coinSuffix 用），找不到返回空 */
    private static String signinRewards(org.json.JSONObject resp) {
        try {
            if (resp == null) return "";
            org.json.JSONObject data = resp.optJSONObject("data");
            if (data == null) return "";
            org.json.JSONArray rw = data.optJSONArray("rewards");
            if (rw != null) return rw.toString();
            org.json.JSONObject res = data.optJSONObject("result");
            if (res != null) {
                rw = res.optJSONArray("rewards");
                if (rw != null) return rw.toString();
            }
            rw = data.optJSONArray("rewardList");
            return rw == null ? "" : rw.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从领奖 rewards JSON 提取金币合计，返回 " +N币" 后缀（解析失败返回空串） */
    private static String coinSuffix(String rewardsJson) {
        try {
            if (rewardsJson == null || rewardsJson.isEmpty()) return "";
            org.json.JSONArray arr = new org.json.JSONArray(rewardsJson);
            int total = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String[] keys = {"coinNum", "coin", "amount", "value"};
                for (String k : keys) {
                    if (r.has(k)) {
                        try {
                            total += Integer.parseInt(
                                    String.valueOf(r.get(k)).replaceAll("[^0-9-]", ""));
                        } catch (Throwable ignored) {
                        }
                        break;
                    }
                }
            }
            return total > 0 ? " +" + total + "币" : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 每日自动跑（免电脑值守）：每日首次进入闲鱼主进程后延迟 45s 触发一轮引擎。
     * 日期标记存 rh_pref.json 的 autorun_date；延迟等首页 mtop 流量起来（direct 通道借壳需要载体）。
     * 调用点：FishMtopHook.registerInvoker（Application.attach 后，各进程都会进，内部做主进程过滤）。
     */
    public static void autoRunDaily(Context ctx) {
        try {
            // 仅主进程执行（子进程没有 mtop SDK）
            String proc = null;
            try {
                proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return;

            // 配置开关：enabled / autoRun 关闭则不自动跑（模块 UI 可切）
            int intervalMin = 240;
            try {
                ConfigManager cfg = ConfigManager.load();
                if (cfg == null || !cfg.enabled || !cfg.autoRun) {
                    log("[auto-run] disabled by config (enabled="
                            + (cfg != null && cfg.enabled) + ", autoRun="
                            + (cfg != null && cfg.autoRun) + "), skip");
                    return;
                }
                intervalMin = cfg.autoRunIntervalMin;
            } catch (Throwable t) {
                log("[auto-run] config check fail, continue: " + t);
            }

            // 时间戳标记：距上次自动跑 >= autoRunIntervalMin 分钟才触发。
            // 标记存 internal files（external cache 会被闲鱼清理导致标记丢失——10-16 实测）。
            long now = System.currentTimeMillis();
            java.io.File mark;
            try {
                java.io.File dir = ctx.getFilesDir();
                mark = new java.io.File(dir != null ? dir
                        : new java.io.File("/data/data/com.taobao.idlefish/files"), "rh_autorun.txt");
            } catch (Throwable t) {
                mark = new java.io.File("/data/data/com.taobao.idlefish/files", "rh_autorun.txt");
            }
            long last = 0;
            try {
                last = Long.parseLong(new String(java.nio.file.Files.readAllBytes(
                        mark.toPath()), "UTF-8").trim());
            } catch (Throwable ignored) {
                // 首次运行无标记，视为 0
            }
            if (last == 0) {
                // 兼容旧 external 标记：autorun_date=今天 视为刚跑过（防升级当天重复跑）
                try {
                    java.io.File ext = ctx.getExternalCacheDir();
                    String body = new String(java.nio.file.Files.readAllBytes(
                            new java.io.File(ext, "rh_pref.json").toPath()), "UTF-8");
                    String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                            .format(new java.util.Date());
                    if (body.contains("\"autorun_date\":\"" + today + "\"")) {
                        last = now;
                    }
                } catch (Throwable ignored) {
                }
            }
            long gapMs = now - last;
            // 分钟制：0 = 不限间隔（探测节拍内能跑就跑），上限 1440min
            final long intervalMs = Math.max(0, intervalMin) * 60_000L;
            // 打开闲鱼即探测（用户语义 10-17）：45s 等 mtop 通道热 → 首探 → 有货就跑、没货静默。
            // intervalMs 仅作兜底 force 周期（防统计漏判），不再做启动节流
            long wait = 45000L;
            log("[auto-run] app start -> first probe in 45s (last run "
                    + (last == 0 ? "never" : gapMs / 60000 + " min ago") + ")");
            final long waitMs = wait;
            final long lastLaunch0 = last == 0 ? now : last;
            // 常驻探测循环（B+C 方案）：每 15min 轻量拉任务列表（1 次只读请求），
            // 有可跑任务才触发完整钓鱼；钓鱼后 3 次加密探测（5min，紧跟服务端补发节奏）；
            // 探测失败 3min 退避重试；另有 autoRunIntervalMin 兜底强制跑（防统计漏判）。
            // 进程死线程亡，下次启动 autoRunDaily 重新拉起——链路自愈
            new Thread(new Runnable() {
                @Override
                public void run() {
                    long wait = waitMs;
                    int burst = 0;             // 完成后加密探测余量
                    long lastLaunch = lastLaunch0;
                    while (true) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            return; // 进程退出/线程被打断即结束
                        }
                        int n = probeRunnableCount(ctx);
                        if (n < 0) {
                            // 探测失败：短退避重试（mtop 未热/网络抖动）
                            log("[probe] fail, retry in 3min");
                            wait = PROBE_FAIL_MS;
                            continue;
                        }
                        boolean runnable = n > 0;
                        boolean force = System.currentTimeMillis() - lastLaunch >= intervalMs;
                        if (runnable || force) {
                            // 有货或兜底到期：写标记（防重启重复）+ 触发完整钓鱼
                            try {
                                java.io.File dir2 = ctx.getFilesDir();
                                java.io.File m2 = new java.io.File(dir2 != null ? dir2
                                        : new java.io.File("/data/data/com.taobao.idlefish/files"),
                                        "rh_autorun.txt");
                                java.nio.file.Files.write(m2.toPath(), String.valueOf(
                                        System.currentTimeMillis()).getBytes("UTF-8"));
                            } catch (Throwable ignored) {
                            }
                            log("[probe] runnable=" + n + (force && !runnable ? " (force)" : "")
                                    + " -> launch");
                            lastLaunch = System.currentTimeMillis();
                            launch(ctx, "{}");
                            burst = 3;           // 跑完后紧跟补发节奏加密探测
                            wait = PROBE_BURST_MS;
                        } else {
                            if (burst > 0) burst--;
                            wait = burst > 0 ? PROBE_BURST_MS : PROBE_INTERVAL_MS;
                        }
                    }
                }
            }, "rh-autorun").start();
        } catch (Throwable t) {
            log("[auto-run] schedule FAILED: " + t);
        }
    }

    /* ============================ 主流程 ============================ */

    private void run(String extrasJson) {
        long t0 = System.currentTimeMillis();
        log("=== task auto run start ===");
        org.json.JSONObject extras = parseObj(extrasJson);
        boolean doSignin = extras.has("signin") ? extras.optBoolean("signin") : true; // 默认开启：任务面板签到按钮同接口（coin.signin），10-17 前默认 false 从未自动执行
        boolean doPolish = extras.has("polish") ? extras.optBoolean("polish") : polishOn;
        boolean doSold = opt(extras, "sold", false);
        boolean doShop = opt(extras, "shop", false);
        boolean doBrowse = opt(extras, "browse", true);
        boolean claimOnly = opt(extras, "claim_only", false);
        if (claimOnly) doBrowse = false;

        try {
            /* 0. 附带日常：签到 / 擦亮 / 卖鱼 / 小店 */
            if (doSignin) {
                org.json.JSONObject sgn = callApi(SIGNIN_API, "1.0", "{}", "签到");
                String sret = firstRet(sgn);
                boolean sok = sret != null && sret.contains("SUCCESS");
                // 落报告：此前只 toast，钓鱼报告里看不到签到结果（10-17 补）
                report.append("[签到] ").append(sok ? "SUCCESS " + coinSuffix(signinRewards(sgn))
                        : (sret == null ? "无响应" : sret)).append('\n');
                if (sok) toast("✓ 每日签到" + coinSuffix(signinRewards(sgn)));
            }
            if (doSignin) {
                // 圈数挑战 7 天签到（独立入口，+50/80/100 递进奖励）
                org.json.JSONObject psn = callApi(PLAY_SIGN_API, "1.0", playSignData(), "玩一玩签到");
                String pret = firstRet(psn);
                boolean pok = pret != null && pret.contains("SUCCESS");
                boolean awarded = psn != null && psn.optJSONObject("data") != null
                        && psn.optJSONObject("data").optBoolean("signInAwarded", false);
                report.append("[玩一玩签到] ").append(pok ? "SUCCESS" + (awarded ? " 今日已签+奖" : " 幂等(今日已签)")
                        : (pret == null ? "无响应" : pret)).append('\n');
                if (pok) toast("✓ 玩一玩签到");
            }
            if (opt(extras, "mapRoll", true)) doMapRoll(); // 圈数挑战自动掷骰前进（10-17 抓包接入，默认开）
            if (opt(extras, "bagUse", true)) doBagUse(); // 🎒 背包自动使用（10-20 协议链路：骰子 map.roll / 刮刮卡 prop.use，配置全关时零开销）
            if (doPolish) runPolish();
            if (doSold) report.append("[卖鱼] 跳过：钓场玩法在闲鱼 7.25.31 已移除（APK 内无 fishing 接口，旧版玩法已下线）\n");
            if (doShop) {
                org.json.JSONObject shp = callApi(SHOP_BENEFIT_API, "1.0", "{}", "小店日福利");
                String shret = firstRet(shp);
                if (shret != null && shret.contains("SUCCESS")) toast("✓ 小店日福利");
            }

            /* 1. 拉任务列表 */
            org.json.JSONObject resp = callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "任务列表");
            List<org.json.JSONObject> tasks = extractTasks(resp);
            if (tasks == null) {
                report.append("任务列表获取失败\n");
                finish(t0);
                return;
            }
            report.append("任务总数: ").append(tasks.size()).append('\n');

            List<String[]> claimList = new ArrayList<>();   // {deliveryId, title}
            List<String[]> triggerList = new ArrayList<>(); // {deliveryId, title}
            List<String[]> adEventList = new ArrayList<>(); // {deliveryId, adEventPushJson}
            List<String[]> searchList = new ArrayList<>();  // {deliveryId, title, action, fromToken, groupId}
            List<String[]> ecMallList = new ArrayList<>();  // {deliveryId, title, action}
            List<String[]> treasureList = new ArrayList<>(); // {deliveryId, title} 好物夺宝
            String[] browseTask = null;                     // {deliveryId, sceneId}
            List<String> claimDone = new ArrayList<>();
            List<String> claimFail = new ArrayList<>();
            // 分桶去重专用（仅本轮）。processed 语义=已领取，绝不能在分桶时无差别 add——
            // 否则收尾复核(:6)会被 processed 卡死，触发完成的任务永远无法补领（10-17 bug 修复）
            java.util.Set<String> bucketed = new java.util.HashSet<>();

            /* 2. 分桶 */
            for (org.json.JSONObject t : tasks) {
                String did = str(t, "deliveryId");
                if (did == null || bucketed.contains(did)) continue;
                if (rejectedToday.contains(did)) continue; // 当日云控拒绝过的不再重试（10-17）
                bucketed.add(did);
                org.json.JSONObject m = t.optJSONObject("materialDTO");
                org.json.JSONObject tb = t.optJSONObject("taskBenefitDTO");
                String status = str(t, "idleTaskStatus");
                String actionType = m == null ? "" : optStr(m, "actionType");
                String title = m == null ? "?" : optStr(m, "title");
                String action = m == null ? "" : optStr(m, "action");
                String scene = str(t, "sceneId");
                boolean needBenefit = tb != null && "true".equals(optStr(tb, "needBenefit"));

                if ("COMPLETE".equals(status) && needBenefit || "AWARD".equals(status)) {
                    claimList.add(new String[]{did, title});
                } else if ("ACCEPTED".equals(status) && action.contains("showAds") && action.contains("adType=ecMall")) {
                    // ecMall 广告任务（10-15 纯协议破防 104637：query 拿服务端 token → 等 rewardDuration
                    // → event.query 预检 → event.push 提交 award。伪造 token 报 CSJ_TOKEN_MISMATCH，
                    // token 必须取自 play.ad.query 响应）
                    ecMallList.add(new String[]{did, title, action});
                } else if ("ACCEPTED".equals(status) && action.contains("showAds")) {
                    // 激励视频任务（协议可完成：play.ad.event.push 自报 complete，实测有效）
                    String ev = extractAdEventPush(action);
                    if (ev != null) adEventList.add(new String[]{did, ev, optStr(m, "subTitle")});
                    else report.append("[广告] ").append(title).append(" 无 adEventPush 参数，跳过\n");
                } else if ("ACCEPTED".equals(status) && action.contains("xianyu/search.html")) {
                    // 搜一搜任务（协议可完成：trigger 携带 action URL 任务指纹，10-14 实测破防 79495）
                    searchList.add(new String[]{did, title, action, str(t, "fromToken"), str(t, "groupId")});
                } else if ("ACCEPTED".equals(status) && action.contains("bbdbindex.html")) {
                    // 好物夺宝任务（10-16 纯协议破防 3137：feeds 拿当日 signUpId → bet 投一注 100 币
                    // → AWARD。signUpId 从 feeds 响应 cardList 自举，gps/fishAdCode 复用抓包值）
                    treasureList.add(new String[]{did, title});
                } else if ("ACCEPTED".equals(status)
                        && ("clickFinish".equals(actionType) || "EXCUTE".equals(actionType)
                        || "PLAINVISIT".equals(actionType) || "VISIT".equals(actionType)
                        || "SYSTEM_SWITCH".equals(actionType))) {
                    // T1 打法：did+fromToken+groupId（10-14 实测外跳 7 任务破防，纯 did 则 ILLEGAL）
                    // 10-15 扩容：VISIT/SYSTEM_SWITCH 也收——昨日 attack_outjump 全量扫 SUCCESS 组
                    // 大量 VISIT（99053/103086/93949/81424），旧分桶漏掉导致 VISIT 任务空转
                    triggerList.add(new String[]{did, title, str(t, "fromToken"), str(t, "groupId")});
                } else if ("ACCEPTED".equals(status) && "idleCoinFeesAnchor".equals(actionType)) {
                    browseTask = new String[]{did, scene == null ? "496" : scene};
                }
            }
            report.append("可领:").append(claimList.size())
                    .append(" 可触发:").append(triggerList.size())
                    .append(" 视频任务:").append(adEventList.size())
                    .append(" 搜一搜:").append(searchList.size())
                    .append(" ecMall:").append(ecMallList.size())
                    .append(" 夺宝:").append(treasureList.size())
                    .append(" 浏览:").append(browseTask != null ? 1 : 0).append('\n');

            /* 3. 先领已完成的 */
            for (String[] c : claimList) {
                if (abort) break;
                claimOne(c[0], c[1], claimDone, claimFail);
                processed.add(c[0]);
                sleep(1200);
            }

            /* 3.5 激励视频任务：play.ad.event.push 自报 complete（服务端无广告凭证校验，实测有效）
             *     循环 maxTimes 次，每次间隔 5s，完成即领奖 */
            for (String[] ad : adEventList) {
                if (abort) break;
                runAdEventTask(ad[0], ad[1], claimDone, claimFail);
                sleep(2000);
            }

            /* 3.6 搜一搜任务：trigger 携带 action URL 任务指纹（10-14 实测 79495 破防：
             *     纯 did ILLEGAL，带 URL 参数组 SUCCESS 且 times 0→1） */
            for (String[] s : searchList) {
                if (abort || claimOnly) break;
                runSearchTask(s[0], s[1], s[2], s[3], s[4], claimDone, claimFail);
                sleep(2000);
            }

            /* 3.7 ecMall 广告任务：纯协议链（10-15 实测 104637 ACCEPTED→AWARD） */
            for (String[] ec : ecMallList) {
                if (abort || claimOnly) break;
                runEcMallTask(ec[0], ec[1], ec[2], claimDone, claimFail);
                sleep(2000);
            }

            /* 3.8 好物夺宝：feeds 拿 signUpId → bet 投一注（10-16 实测 3137 AWARD→领奖） */
            for (String[] tt : treasureList) {
                if (abort || claimOnly) break;
                runTreasureBetTask(tt[0], tt[1], claimDone, claimFail);
                sleep(2000);
            }

            /* 4. 触发桶：uploadolivertasktrigger 带 T1 参数（did+fromToken+groupId）。
             *    10-14 实测：纯 did ILLEGAL；带 fromToken+groupId 后外跳类 7 任务 SUCCESS 推进
             *    （芭芭农场/头条/蚂蚁森林/大众点评/美团/消消乐/百度极速版）。
             *    仍 ILLEGAL 的（服务端云控逐任务判定）标记跳过，不重试不降级。
             *    claim_only 模式整段跳过。 */
            for (String[] tg : triggerList) {
                if (abort || claimOnly) break;
                org.json.JSONObject p = new org.json.JSONObject();
                try {
                    p.put("sceneId", 496);
                    p.put("deliveryId", tg[0]);
                    if (tg[2] != null && tg[2].length() > 0) p.put("fromToken", tg[2]);
                    if (tg[3] != null && tg[3].length() > 0) {
                        try { p.put("groupId", Long.parseLong(tg[3])); } catch (Throwable e) { p.put("groupId", tg[3]); }
                    }
                } catch (Throwable ignored) {
                }
                org.json.JSONObject tr = callApi(TASK_TRIGGER_API, "1.0", p.toString(),
                        "触发任务 " + tg[1]);
                String ret = firstRet(tr);
                if (tr == null || ret == null) {
                    report.append("[触发] ").append(tg[1]).append(" -> 无响应，跳过\n");
                } else if (ret.contains("SUCCESS")) {
                    report.append("[触发] ").append(tg[1]).append(" -> ").append(ret).append('\n');
                    // loop 任务补打（如点击频道好物 10/10）。10-15 优化：先复核再补打——
                    // 盲补对"一次即完成"任务浪费 10 轮（新设备首跑 300s 超时的主因）。
                    // 每轮：复核状态 → AWARD/COMPLETE 即停；times 不动即停（幂等无意义）
                    int prevTimes = -1;
                    for (int k = 0; k < 10 && !abort; k++) {
                        sleep(2000);
                        String st = null;
                        int times = -1, max = -1;
                        List<org.json.JSONObject> tl = extractTasks(
                                callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "补打复核 " + tg[1]));
                        if (tl != null) {
                            for (org.json.JSONObject t : tl) {
                                if (tg[0].equals(str(t, "deliveryId"))) {
                                    st = str(t, "idleTaskStatus");
                                    org.json.JSONObject pd = t.optJSONObject("progressDTO");
                                    if (pd != null) {
                                        try { times = Integer.parseInt(str(pd, "times")); } catch (Throwable ig) {}
                                        try { max = Integer.parseInt(str(pd, "maxTimes")); } catch (Throwable ig) {}
                                    }
                                    break;
                                }
                            }
                        }
                        if ("AWARD".equals(st) || "COMPLETE".equals(st)) {
                            // 任务已推进为可领：就地领取（对齐搜一搜 runner 的闭环模式）。
                            // 10-17 修复：此前仅 break，依赖收尾复核补领，而复核被分桶时的
                            // processed 误标记卡死 → 触发完成的奖励全部滞留待手动领取
                            claimOne(tg[0], tg[1], claimDone, claimFail);
                            processed.add(tg[0]);
                            break;
                        }
                        if (max > 0 && times >= max) break;
                        if (k > 0 && times == prevTimes) break; // times 没动：SUCCESS 幂等，停
                        prevTimes = times;
                        org.json.JSONObject tr2 = callApi(TASK_TRIGGER_API, "1.0", p.toString(),
                                "补打 " + tg[1] + "#" + (k + 2));
                        String r2 = firstRet(tr2);
                        if (r2 == null || !r2.contains("SUCCESS")) {
                            if (r2 != null) report.append("[触发] ").append(tg[1])
                                    .append(" 补打#").append(k + 2).append(" -> ").append(r2).append('\n');
                            break;
                        }
                    }
                } else {
                    report.append("[触发] ").append(tg[1]).append(" -> ").append(ret)
                            .append("（云控判定需真实行为，协议跳过）\n");
                    markRejected(tg[0]); // 当日不再重试（探测统计与分桶同步跳过）
                }
                sleep(1200);
            }

            /* 5. 浏览任务：nextfresh 拉商品 → 逐个 trigger(implId) */
            if (doBrowse && browseTask != null && !abort) {
                browseForTask(browseTask[0], browseTask[1], claimDone, claimFail);
            }

            /* 6. 收尾再领（触发/浏览完成后的奖励）。
             *    10-15 实测：trigger SUCCESS 后服务端异步翻 AWARD 有数秒延迟，
             *    单次立即复核会漏领 → 等待 + 双轮复核 */
            if (!abort) {
                for (int round = 0; round < 2; round++) {
                    sleep(6000); // 等服务端异步记账
                    resp = callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}",
                            "任务列表复核" + (round + 1));
                    List<org.json.JSONObject> tasks2 = extractTasks(resp);
                    if (tasks2 == null) continue;
                    for (org.json.JSONObject t : tasks2) {
                        String did = str(t, "deliveryId");
                        if (did == null || processed.contains(did)) continue;
                        org.json.JSONObject tb = t.optJSONObject("taskBenefitDTO");
                        String status = str(t, "idleTaskStatus");
                        org.json.JSONObject m = t.optJSONObject("materialDTO");
                        String title = m == null ? "?" : optStr(m, "title");
                        boolean needBenefit = tb != null && "true".equals(optStr(tb, "needBenefit"));
                        if (("COMPLETE".equals(status) && needBenefit) || "AWARD".equals(status)) {
                            processed.add(did);
                            claimOne(did, title, claimDone, claimFail);
                            sleep(800);
                        }
                    }
                }
            }

            report.append("领取成功 ").append(claimDone.size()).append(": ").append(claimDone).append('\n');
            report.append("领取失败 ").append(claimFail.size()).append(": ").append(claimFail).append('\n');
        } catch (Throwable t) {
            report.append("引擎异常: ").append(t).append('\n');
            log("task auto FAIL: " + t);
        }
        finish(t0);
    }

    /* ============================ 激励视频任务 ============================ */

    /** 从 action URL 提取 adEventPush 参数（urlencode JSON），eventType 改写为 complete */
    private static String extractAdEventPush(String action) {
        try {
            int k = action.indexOf("adEventPush=");
            if (k < 0) return null;
            String v = action.substring(k + "adEventPush=".length());
            int amp = v.indexOf('&');
            if (amp >= 0) v = v.substring(0, amp);
            String json = java.net.URLDecoder.decode(v, "UTF-8");
            org.json.JSONObject j = new org.json.JSONObject(json);
            j.put("eventType", "complete");
            return j.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void runAdEventTask(String deliveryId, String adEventJson, List<String> done, List<String> fail) {
        // 10-15 新设备实测（7.28.30）：play.ad.event.push 请求生效但响应不走落盘通道
        // （callApi 全 TIMEOUT 而 times 照样推进）→ 以"任务列表 times 复核"为判定，
        // 无响应也继续打，打满 maxTimes 次后复核领奖
        int maxTimes = 4; // 单任务上报上限保护
        int ok = 0, noResp = 0;
        for (int i = 0; i < maxTimes && !abort; i++) {
            org.json.JSONObject resp = callApi(AD_EVENT_API, "1.0", adEventJson, "广告事件 " + (i + 1));
            String ret = firstRet(resp);
            if (resp == null) {
                noResp++;
                report.append("[广告] 第 ").append(i + 1).append(" 次 无响应（7.28.30 响应不落盘，继续）\n");
                sleep(5000);
                continue; // 无响应≠失败：以最终 times 复核判定
            }
            if (ret != null && ret.contains("SUCCESS")) {
                ok++;
                report.append("[广告] 第 ").append(i + 1).append(" 次 complete -> ").append(ret).append('\n');
            } else {
                report.append("[广告] 第 ").append(i + 1).append(" 次 -> ").append(ret).append('\n');
                break; // 非成功（如已达上限/参数问题）即停
            }
            sleep(5000);
        }
        // 复核 times 决定领奖
        int times = -1, max = -1;
        String st = null;
        List<org.json.JSONObject> tl = extractTasks(
                callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "广告复核"));
        if (tl != null) {
            for (org.json.JSONObject t : tl) {
                if (deliveryId.equals(str(t, "deliveryId"))) {
                    st = str(t, "idleTaskStatus");
                    org.json.JSONObject pd = t.optJSONObject("progressDTO");
                    if (pd != null) {
                        try { times = Integer.parseInt(str(pd, "times")); } catch (Throwable ig) {}
                        try { max = Integer.parseInt(str(pd, "maxTimes")); } catch (Throwable ig) {}
                    }
                    break;
                }
            }
        }
        report.append("[广告] 复核: status=").append(st).append(" times=").append(times)
                .append('/').append(max).append(" (respOk=").append(ok).append(" noResp=").append(noResp).append(")\n");
        if ("AWARD".equals(st) || "COMPLETE".equals(st) || (max > 0 && times >= max) || ok > 0) {
            claimOne(deliveryId, "视频任务", done, fail);
        }
    }

    /* ============================ 浏览任务 ============================ */

    /** 商品池（多任务共享一次 nextfresh 拉取） */
    private List<String> itemPool = null;

    private List<String> itemPool() {
        if (itemPool != null) return itemPool;
        List<String> list = new ArrayList<>();
        org.json.JSONObject nf = callApi(NEXT_FRESH_API, "1.0",
                "{\"enableDx\":true,\"lastResponseCount\":0,\"pageNumber\":1}", "浏览任务商品源");
        if (nf != null) {
            org.json.JSONObject data = nf.optJSONObject("data");
            org.json.JSONArray cards = data == null ? null : data.optJSONArray("cardList");
            if (cards != null) {
                for (int i = 0; i < cards.length(); i++) {
                    org.json.JSONObject card = cards.optJSONObject(i);
                    org.json.JSONObject cd = card == null ? null : card.optJSONObject("cardData");
                    if (cd == null) continue;
                    // 7.25.31: itemId 在 trackParams 里（老版在 cardData 顶层）；广告卡(bizType=AD)排除
                    String bizType = optStr(cd, "bizType");
                    if ("AD".equalsIgnoreCase(bizType)) continue;
                    String itemId = optStr(cd, "itemId");
                    if (itemId.isEmpty()) {
                        org.json.JSONObject tp = cd.optJSONObject("trackParams");
                        if (tp != null) itemId = optStr(tp, "itemId");
                    }
                    if (itemId != null && itemId.length() > 0) list.add(itemId);
                }
            }
        }
        itemPool = list;
        return list;
    }

    /** 搜一搜任务：trigger 携带 action URL 全参数（任务指纹）+ 真实搜索行为支撑，完成即领奖 */
    private void runSearchTask(String deliveryId, String title, String action, String fromToken, String groupId,
                               List<String> done, List<String> fail) {
        org.json.JSONObject tr = new org.json.JSONObject();
        try {
            tr.put("sceneId", 496);
            tr.put("deliveryId", deliveryId);
            // action URL 查询参数全量透传（implId=xyb001/channelSrp=xybrw/adPositionCode/refpid...）
            android.net.Uri u = android.net.Uri.parse(action);
            for (String k : new String[]{"implId", "channelSrp", "adPositionCode", "adOrderPositionCode", "refpid", "k_usetanx"}) {
                String v = u.getQueryParameter(k);
                if (v != null) tr.put(k, v);
            }
            if (fromToken != null && fromToken.length() > 0) tr.put("fromToken", fromToken);
            if (groupId != null && groupId.length() > 0) {
                try { tr.put("groupId", Long.parseLong(groupId)); } catch (Throwable e) { tr.put("groupId", groupId); }
            }
        } catch (Throwable t) {
            report.append("[搜一搜] ").append(title).append(" 参数组装失败: ").append(t).append('\n');
        }
        callApi(TASK_TRIGGER_API, "1.0", tr.toString(), "搜一搜触发 " + title);
        // 行为支撑：真实搜索一次（非必需，双保险）
        callApi(SEARCH_API, "1.0", "{\"q\":\"闲鱼\",\"pageNumber\":1,\"pageSize\":10}", "搜一搜行为 " + title);
        // 复核并领奖
        org.json.JSONObject resp = callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "搜一搜复核");
        String status = null;
        List<org.json.JSONObject> tasks2 = extractTasks(resp);
        if (tasks2 != null) {
            for (org.json.JSONObject t : tasks2) {
                if (deliveryId.equals(str(t, "deliveryId"))) { status = str(t, "idleTaskStatus"); break; }
            }
        }
        if ("AWARD".equals(status) || "COMPLETE".equals(status)) {
            claimOne(deliveryId, title, done, fail);
        } else {
            report.append("[搜一搜] ").append(title).append(" 未推进（status=").append(status).append("）\n");
        }
    }

    /* ============================ ecMall 广告任务 ============================ */

    /**
     * ecMall 广告任务纯协议链（10-15 实测 104637 ACCEPTED→AWARD，真实链路逆向自 CsjMallActivity 会话）：
     * 1. play.ad.query {code: adPositionCode, extra:{scenarioId, sourceType}} —— 服务端签发会话 token
     *    （伪造 token 报 FAIL_BIZ_CSJ_TOKEN_MISMATCH"广告奖励会话已失效"；响应 data 顶层含
     *     token/implId，attributes.taskConfig.browse.rewardDuration 为要求浏览秒数）
     * 2. 等 rewardDuration 秒（默认 20，服务端校验时间间隔）
     * 3. play.ad.event.query 预检（data.used=false 为可提交）
     * 4. play.ad.event.push {adPositionCode, eventType:award, token, implId} —— SERVER_ERROR 可重试
     *    （实测同 token 第 2 次成功；TOKEN_MISMATCH 则会话失效重试无意义）
     * 5. 复核任务列表 → AWARD 领奖。
     * 注意：100 币档领奖可能被当日 RMB 等值限额拦（FAIL_BIZ_IDL_COIN_TASK_RMB_CHECK_ERROR，
     * 客户端自带 asac 同样被拦），AWARD 状态留给下一轮运行补领。
     */
    private void runEcMallTask(String deliveryId, String title, String action, List<String> done, List<String> fail) {
        String poscode = null, scenarioId = null, sourceType = null;
        try {
            android.net.Uri u = android.net.Uri.parse(action);
            poscode = u.getQueryParameter("adPositionCode");
            scenarioId = u.getQueryParameter("scenarioId");
            sourceType = u.getQueryParameter("sourceType");
        } catch (Throwable ignored) {
        }
        if (poscode == null || poscode.length() == 0) {
            report.append("[ecMall] ").append(title).append(" 无 adPositionCode，跳过\n");
            return;
        }
        String extra = objJson("scenarioId", scenarioId == null ? "" : scenarioId,
                "sourceType", sourceType == null ? "FEEDS_SLOT" : sourceType);
        org.json.JSONObject q = callApi(AD_QUERY_API, "1.0",
                objJson("code", poscode, "extra", extra), "ecMall ad.query " + title);
        String ret = firstRet(q);
        if (q == null || ret == null || !ret.contains("SUCCESS")) {
            report.append("[ecMall] ").append(title).append(" ad.query 失败: ")
                    .append(ret == null ? "无响应" : ret).append('\n');
            return;
        }
        org.json.JSONObject d = q.optJSONObject("data");
        String token = d == null ? null : optStr(d, "token");
        String implId = d == null ? null : optStr(d, "implId");
        int duration = 20; // rewardDuration 默认 20s
        if (d != null) {
            String attr = optStr(d, "attributes");
            if (attr.length() > 0) {
                try {
                    org.json.JSONObject a = new org.json.JSONObject(attr);
                    org.json.JSONObject tc = a.optJSONObject("taskConfig");
                    org.json.JSONObject browse = tc == null ? null : tc.optJSONObject("browse");
                    if (browse != null) {
                        int dr = parseInt(optStr(browse, "rewardDuration"), 20);
                        if (dr > 0) duration = dr;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (token == null || token.length() == 0) {
            report.append("[ecMall] ").append(title).append(" 未下发 token，跳过\n");
            return;
        }
        report.append("[ecMall] ").append(title).append(" token=").append(token)
                .append(" 浏览时长=").append(duration).append("s，等待中\n");
        sleep(duration * 1000L + 2000);
        // 预检（失败不阻断，仅记录）
        org.json.JSONObject pre = callApi(AD_EVENT_QUERY_API, "1.0",
                objJson("adPositionCode", poscode, "eventType", "award", "token", token, "implId", implId),
                "ecMall 预检 " + title);
        String preRet = firstRet(pre);
        report.append("[ecMall] 预检 -> ").append(preRet == null ? "无响应" : preRet).append('\n');
        // 提交 award（首发基本必 SERVER_ERROR，同 token 间隔 30s 重试稳定成功——
        // 5s 短间隔×3 实测全败，30s 间隔第 2 发起稳定过；TOKEN_MISMATCH 则会话失效停止）
        String pushRet = null;
        for (int i = 0; i < 5 && !abort; i++) {
            org.json.JSONObject push = callApi(AD_EVENT_API, "1.0",
                    objJson("adPositionCode", poscode, "eventType", "award", "token", token, "implId", implId),
                    "ecMall push " + title + (i > 0 ? "#" + (i + 1) : ""));
            pushRet = firstRet(push);
            if (pushRet != null && (pushRet.contains("SUCCESS") || pushRet.contains("TOKEN_MISMATCH"))) break;
            sleep(30000);
        }
        report.append("[ecMall] push -> ").append(pushRet == null ? "无响应" : pushRet).append('\n');
        // 复核领奖
        sleep(3000);
        org.json.JSONObject resp = callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "ecMall 复核");
        String status = null;
        List<org.json.JSONObject> tasks2 = extractTasks(resp);
        if (tasks2 != null) {
            for (org.json.JSONObject t : tasks2) {
                if (deliveryId.equals(str(t, "deliveryId"))) {
                    status = str(t, "idleTaskStatus");
                    break;
                }
            }
        }
        if ("AWARD".equals(status) || "COMPLETE".equals(status)) {
            claimOne(deliveryId, title, done, fail);
        } else {
            report.append("[ecMall] ").append(title).append(" 未推进（status=").append(status).append("）\n");
        }
    }

    /* ============================ 好物夺宝 ============================ */

    /**
     * 好物夺宝纯协议链（10-16 实测 3137：bet SUCCESS → AWARD → 领奖）：
     * feeds(fishAdCode+gps) 响应 cardList 含当日各场次 signUpId →
     * bet({asac,attentionSuc:false,betNumber:1,fishAdCode,gps,signUpId}) 投一注（100 币）→
     * 复核任务 AWARD → 领奖。signUpId 每日场次不同，从 feeds 自举。
     */
    private void runTreasureBetTask(String deliveryId, String title, List<String> done, List<String> fail) {
        org.json.JSONObject feeds = callApi(LOTTERY_FEEDS_API, "1.0",
                objJson("fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()), "夺宝 feeds " + title);
        String signUpId = null;
        String fRet = firstRet(feeds);
        if (feeds != null && fRet != null && fRet.contains("SUCCESS")) {
            org.json.JSONObject d = feeds.optJSONObject("data");
            org.json.JSONArray cards = d == null ? null : d.optJSONArray("cardList");
            if (cards != null && cards.length() > 0) {
                org.json.JSONObject c0 = cards.optJSONObject(0);
                signUpId = c0 == null ? null : optStr(c0, "signUpId");
            }
        }
        if (signUpId == null || signUpId.length() == 0) {
            report.append("[夺宝] ").append(title).append(" 未取到 signUpId（ret=")
                    .append(fRet == null ? "无响应" : fRet).append("），跳过\n");
            return;
        }
        // 投一注（100 闲鱼币）。bet 首发如 SERVER_ERROR 可同参重试（同 ecMall push 经验）
        String betRet = null;
        for (int i = 0; i < 3 && !abort; i++) {
            org.json.JSONObject bet = callApi(LOTTERY_BET_API, "1.0",
                    objJson("asac", RhLocationContext.asac(), "attentionSuc", "false", "betNumber", "1",
                            "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps(), "signUpId", signUpId),
                    "夺宝 bet " + title + (i > 0 ? "#" + (i + 1) : ""));
            betRet = firstRet(bet);
            if (betRet != null && betRet.contains("SUCCESS")) break;
            sleep(8000);
        }
        report.append("[夺宝] ").append(title).append(" bet(").append(signUpId).append(") -> ")
                .append(betRet == null ? "无响应" : betRet).append('\n');
        if (betRet == null || !betRet.contains("SUCCESS")) return;
        // 复核领奖
        sleep(3000);
        org.json.JSONObject resp = callApi(TASK_LIST_API, "2.0", "{\"sceneId\":496}", "夺宝复核");
        String status = null;
        List<org.json.JSONObject> tasks2 = extractTasks(resp);
        if (tasks2 != null) {
            for (org.json.JSONObject t : tasks2) {
                if (deliveryId.equals(str(t, "deliveryId"))) {
                    status = str(t, "idleTaskStatus");
                    break;
                }
            }
        }
        if ("AWARD".equals(status) || "COMPLETE".equals(status)) {
            claimOne(deliveryId, title, done, fail);
        } else {
            report.append("[夺宝] ").append(title).append(" 未推进（status=").append(status).append("）\n");
        }
    }

    /* ============================ 擦亮（接口链路定位自闲鱼 dex 静态分析） ============================ */

    /* ==================== 闲鱼币余额日志（差分法「今日领取」，10-17 定稿） ====================
     * 思路：总余额是白送字段（map.init → coinNum；coin.signin → data.idleCoinAmount，10-17 抓包实锤 54510）。
     * 每分钟取值追加当日日志 zhushou_YYYY.MM.DD.log（行格式 yyyy.MM.dd HH:mm:ss 余额），
     * 闲鱼自己请求这两个接口时 hook 也顺手记一笔（被动，打开闲鱼即刻有值）。
     * App 读昨日最后一行 + 今日全部行，相邻行差分：涨了累加、跌了（兑换支出）跳过 = 今日领取
     * （用户语义「只记增加的，不记支出」）。跨天：新一天首行与昨日末行差分自动衔接，无需显式基础值。
     * 每天清理 3 天前旧文件（闲鱼自己 cache 目录 File.delete 免 root）。
     */
    private static final Object COIN_LOG_LOCK = new Object();
    private static volatile long coinLogLastVal = -1;
    private static volatile long coinLogLastAt = 0;
    private static String coinLogLastDay = "";
    private static String coinLogCleanedDay = "";

    /** 主进程 hook 初始化后调用：定时线程每 30s 借壳 map.init 读总余额写日志（首探 20s 等 mtop 通道热） */
    public static void startCoinBalanceLogger(final Context ctx) {
        try {
            String proc = (String) Class.forName("android.app.Application")
                    .getMethod("getProcessName").invoke(null);
            if (proc != null && proc.contains(":")) return; // 仅主进程
        } catch (Throwable ignored) {
        }
        java.util.concurrent.ScheduledExecutorService ses =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ses.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    coinBalanceSnapshot(ctx);
                } catch (Throwable ignored) {
                }
            }
        }, 20, 30, java.util.concurrent.TimeUnit.SECONDS);
        log("[coinlog] balance logger armed (every 30s, first in 20s)");
    }

    /** 主动取值：借壳 map.init（纯查询无副作用）读 coinNum → 记日志 */
    public static void coinBalanceSnapshot(Context ctx) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            java.io.File ext = ctx.getExternalCacheDir();
            String dir = ext != null ? ext.getAbsolutePath()
                    : "/storage/emulated/0/Android/data/com.taobao.idlefish/cache";
            FishTaskAuto inst = new FishTaskAuto(cl, dir);
            org.json.JSONObject r = inst.callApi(MAP_INIT_API, "1.0",
                    "{" + RhLocationContext.jsonPair() + "\"version\":4}",
                    "币余额");
            org.json.JSONObject d = r == null ? null : r.optJSONObject("data");
            if (d == null) return;
            long v = parseCoinNum(d.optString("coinNum", ""));
            if (v <= 0) {
                org.json.JSONObject ui = d.optJSONObject("userInfo");
                if (ui != null) v = parseCoinNum(ui.optString("coinNum", ""));
            }
            if (v > 0) recordCoinBalance(ctx, v);
        } catch (Throwable t) {
            log("[coinlog] snapshot error: " + t);
        }
    }

    /** 记一笔余额（主动/被动共用）：同值 5 分钟去重防刷盘，跨天必写（新文件首行=当日基准） */
    public static void recordCoinBalance(Context ctx, long v) {
        if (v <= 0) return;
        try {
            synchronized (COIN_LOG_LOCK) {
                long now = System.currentTimeMillis();
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US);
                String day = df.format(new java.util.Date(now));
                boolean newDay = !day.equals(coinLogLastDay);
                if (v == coinLogLastVal && now - coinLogLastAt < 300000L && !newDay) return;
                // files 目录（非 cache）：force-stop/清缓存不会清掉 files，账本必须持久
                java.io.File ext = ctx.getExternalFilesDir(null);
                String dir = ext != null ? ext.getAbsolutePath()
                        : "/storage/emulated/0/Android/data/com.taobao.idlefish/files";
                java.io.File dirF = new java.io.File(dir);
                if (!dirF.exists()) dirF.mkdirs();
                java.io.File f = new java.io.File(dirF, "zhushou_" + day + ".log");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                String line = day + " "
                        + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(now))
                        + " " + v + "\n";
                fos.write(line.getBytes("UTF-8"));
                fos.close();
                coinLogLastVal = v;
                coinLogLastAt = now;
                coinLogLastDay = day;
                log("[coinlog] " + line.trim());
                if (!day.equals(coinLogCleanedDay)) {   // 每天首次写盘时清理 3 天前旧文件
                    coinLogCleanedDay = day;
                    cleanOldCoinLogs(dirF, df);
                }
            }
        } catch (Throwable t) {
            log("[coinlog] record error: " + t);
        }
    }

    /** 保留今天/昨天/前天，删除更早的 zhushou_*.log（自己 cache 目录，File.delete 免 root） */
    private static void cleanOldCoinLogs(java.io.File dirF, java.text.SimpleDateFormat df) {
        try {
            java.io.File[] fs = dirF.listFiles();
            if (fs == null) return;
            String keepDay = df.format(new java.util.Date(System.currentTimeMillis() - 2 * 86400000L));
            java.util.Date kd = df.parse(keepDay);
            for (java.io.File f : fs) {
                String n = f.getName();
                if (!n.startsWith("zhushou_") || !n.endsWith(".log")) continue;
                try {
                    java.util.Date fd = df.parse(n.substring(8, n.length() - 4));
                    if (fd != null && fd.before(kd)) f.delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("[coinlog] clean error: " + t);
        }
    }

    /** 宽松解析余额（服务端字符串/数值不定型，如 "54510" / 54510 / 54510.0） */
    public static long parseCoinNum(String s) {
        if (s == null) return -1;
        try {
            String t = s.trim().replace("\"", "");
            if (t.length() == 0) return -1;
            int dot = t.indexOf('.');
            if (dot >= 0) t = t.substring(0, dot);
            t = t.replaceAll("[^0-9]", "");
            if (t.length() == 0) return -1;
            return Long.parseLong(t);
        } catch (Throwable tt) {
            return -1;
        }
    }


    /**
     * 圈数挑战自动掷骰前进（10-17 抓包接入）：
     * 1. map.init v1.0 {fishAdCode,gps,version:4} → data.userInfo.location / data.remainStep / mapResource[0].mapVersion
     * 2. 循环 map.roll v1.0 {diceType:"FORWARD_STEP",fishAdCode,gps,locationBefore,mapVersion,outId:当前毫秒戳,version:3}
     *    沿途 contentList 收币、落格 settleList 结算均由服务端自动完成，无需额外领取
     * 3. 步数耗尽 / isNeedRefresh / 异常即停；上限 60 次防死循环
     */
    private void doMapRoll() {
        try {
            org.json.JSONObject init = callApi(MAP_INIT_API, "1.0",
                    "{" + RhLocationContext.jsonPair() + "\"version\":4}",
                    "地图状态");
            org.json.JSONObject d = init == null ? null : init.optJSONObject("data");
            org.json.JSONObject ui = d == null ? null : d.optJSONObject("userInfo");
            if (ui == null) {
                report.append("[前进钓鱼] 地图状态获取失败，跳过\n");
                return;
            }
            long location = parseNum(ui.optString("location", "0"));
            // 步数位置：init 响应在 data.userInfo.remainStep（服务端字符串/数值不定型，parseNum 宽松解），
            // roll 响应在 data 顶层——读取统一走 stepOf 兜底两种结构
            int remain = parseNum(ui.optString("remainStep", "0"));
            String mapVersion = "11";
            org.json.JSONArray mr = d.optJSONArray("mapResource");
            if (mr != null && mr.length() > 0) {
                mapVersion = mr.optJSONObject(0).optString("mapVersion", "11");
            }
            if (remain <= 0) {
                report.append("[前进钓鱼] 无剩余步数，跳过\n");
                return;
            }

            int rolls = 0, steps = 0, coins = 0, reInits = 0;
            String lastErr = null;
            while (remain > 0 && rolls < 60) {
                String data = "{\"diceType\":\"FORWARD_STEP\","
                        + RhLocationContext.jsonPair()
                        + "\"locationBefore\":" + location
                        + ",\"mapVersion\":\"" + mapVersion + "\",\"outId\":" + System.currentTimeMillis()
                        + ",\"version\":3}";
                org.json.JSONObject r = callApi(MAP_ROLL_API, "1.0", data, "前进掷骰");
                String ret = firstRet(r);
                if (ret == null || !ret.contains("SUCCESS")) {
                    lastErr = ret == null ? "无响应" : ret;
                    break;
                }
                rolls++;
                org.json.JSONObject rd = r.optJSONObject("data");
                if (rd == null) {
                    lastErr = "响应无 data";
                    break;
                }
                steps += parseNum(rd.optString("diceResult", "0"));
                // 收益统计：沿途 coin + 落格结算 treasureMapPoint
                org.json.JSONArray cl = rd.optJSONArray("contentList");
                if (cl != null) {
                    for (int i = 0; i < cl.length(); i++) {
                        org.json.JSONObject c = cl.optJSONObject(i);
                        if (c != null && "coin".equals(c.optString("contentType"))) {
                            coins += parseNum(c.optString("rewardNum", "0"));
                        }
                    }
                }
                org.json.JSONArray sl = rd.optJSONArray("settleList");
                if (sl != null) {
                    for (int i = 0; i < sl.length(); i++) {
                        org.json.JSONObject s = sl.optJSONObject(i);
                        if (s != null && "treasureMapPoint".equals(s.optString("contentType"))) {
                            coins += parseNum(s.optString("rewardNum", "0"));
                        }
                    }
                }
                long newLoc = rd.optJSONObject("userInfo") == null
                        ? location : parseNum(rd.optJSONObject("userInfo").optString("location", String.valueOf(location)));
                remain = stepOf(rd);
                // isNeedRefresh 布尔/字符串不定型，optString 统一比对
                boolean needRefresh = "true".equalsIgnoreCase(rd.optString("isNeedRefresh", "false"));
                if (newLoc == location && remain > 0) {
                    // 服务端处于待刷新/风控态时 roll SUCCESS 但不推进位置：re-init 后重试（上限 5 次）
                    if (reInits >= 5) {
                        lastErr = "位置未推进";
                        break;
                    }
                    reInits++;
                    org.json.JSONObject ir = callApi(MAP_INIT_API, "1.0",
                            "{" + RhLocationContext.jsonPair() + "\"version\":4}",
                            "地图重试");
                    org.json.JSONObject dr = ir == null ? null : ir.optJSONObject("data");
                    org.json.JSONObject ur = dr == null ? null : dr.optJSONObject("userInfo");
                    if (ur == null) {
                        lastErr = "重试初始化失败";
                        break;
                    }
                    location = parseNum(ur.optString("location", String.valueOf(location)));
                    remain = stepOf(ir);
                    continue;
                }
                location = newLoc;
                if (!needRefresh) continue;
                // 跨屏掷骰后服务端要求重拉地图：就地 re-init 更新位置/步数/版本，继续掷（上限 5 次防死循环）
                if (reInits >= 5) {
                    lastErr = "刷新超限";
                    break;
                }
                reInits++;
                org.json.JSONObject i2 = callApi(MAP_INIT_API, "1.0",
                        "{" + RhLocationContext.jsonPair() + "\"version\":4}",
                        "地图刷新");
                org.json.JSONObject d2 = i2 == null ? null : i2.optJSONObject("data");
                org.json.JSONObject u2 = d2 == null ? null : d2.optJSONObject("userInfo");
                if (u2 == null) {
                    lastErr = "刷新地图失败";
                    break;
                }
                location = parseNum(u2.optString("location", String.valueOf(location)));
                remain = stepOf(i2);
                org.json.JSONArray mr2 = d2.optJSONArray("mapResource");
                if (mr2 != null && mr2.length() > 0) {
                    mapVersion = mr2.optJSONObject(0).optString("mapVersion", mapVersion);
                }
            }

            if (rolls == 0) {
                report.append("[前进钓鱼] ").append(lastErr == null ? "掷骰失败" : lastErr).append('\n');
            } else {
                report.append("[前进钓鱼] 掷骰 ").append(rolls).append(" 次 · 前进 ").append(steps)
                        .append(" 步 · 收币 ").append(coins)
                        .append(" · 剩余步数 ").append(remain)
                        .append(lastErr != null ? "（中断:" + lastErr + "）" : "").append('\n');
                toast("✓ 前进钓鱼 +" + coins + "币");
                log("[map-roll] rolls=" + rolls + " steps=" + steps + " coins=" + coins + " remain=" + remain);
            }
        } catch (Throwable t) {
            report.append("[前进钓鱼] 异常 ").append(t).append('\n');
        }
    }

    /**
     * 🎒 背包自动使用（10-20 协议链路定案）：骰子类走 map.roll 地图掷骰（diceId=configId，
     * 服务端一把梭：扣道具+按点数行进+沿途收币）；刮刮卡走 prop.use 权益直兑（benefitResult）。
     * 数量 N=用 N 个，-1=用到 0。幂等设计：以背包当前 amount 与目标差值驱动，无需进度持久化——
     * 每次跑批只看「还剩多少、还要用多少」，新获得的道具也会被 -1 配置持续清掉。
     */
    private void doBagUse() {
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg == null) return;
            Object[][] plans = {
                    {"LUCKY_DICE", cfg.bagLuckOn, cfg.bagLuckNum, "好运骰"},
                    {"SURFACE8_DICE", cfg.bag8On, cfg.bag8Num, "八面骰"},
                    {"SURFACE12_DICE", cfg.bag12On, cfg.bag12Num, "十二面骰"},
                    {"ROCKET_DICE", cfg.bagRocketOn, cfg.bagRocketNum, "火箭骰子"},
                    {"DOUBLE_DICE", cfg.bagDoubleOn, cfg.bagDoubleNum, "双倍骰子"},
                    {"BENEFIT_GGK", cfg.bagGgkOn, cfg.bagGgkNum, "刮刮卡"},
            };
            boolean anyOn = false;
            for (Object[] p : plans) if ((Boolean) p[1]) { anyOn = true; break; }
            if (!anyOn) return;

            // 1. 拉背包（同 propType 可能多条记录：骰子取首个 configId，刮刮卡取首个记录 id）
            org.json.JSONObject list = callApi(PROP_LIST_API, "1.0",
                    objJson("bizCode", "coinMap", "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()), "背包列表");
            org.json.JSONObject ld = list == null ? null : list.optJSONObject("data");
            org.json.JSONArray infos = ld == null ? null : ld.optJSONArray("propInfos");
            if (infos == null) {
                report.append("[背包] 列表获取失败，跳过\n");
                return;
            }

            // 2. 地图状态（骰子掷骰需要 location/mapVersion；无地图响应时骰子跳过、刮刮卡不受影响）
            long location = 0;
            String mapVersion = "11";
            boolean mapOk = false;
            org.json.JSONObject mi = callApi(MAP_INIT_API, "1.0",
                    "{" + RhLocationContext.jsonPair() + "\"version\":4}",
                    "背包地图");
            org.json.JSONObject md = mi == null ? null : mi.optJSONObject("data");
            org.json.JSONObject mu = md == null ? null : md.optJSONObject("userInfo");
            if (mu != null) {
                location = parseNum(mu.optString("location", "0"));
                mapOk = true;
            }
            org.json.JSONArray mres = md == null ? null : md.optJSONArray("mapResource");
            if (mres != null && mres.length() > 0) {
                mapVersion = mres.optJSONObject(0).optString("mapVersion", mapVersion);
            }

            // 3. 逐道具执行
            for (Object[] p : plans) {
                String type = (String) p[0];
                boolean on = (Boolean) p[1];
                int num = (Integer) p[2];
                String name = (String) p[3];
                if (!on) continue;
                int amount = 0;
                String configId = null;
                String recId = null;
                for (int i = 0; i < infos.length(); i++) {
                    org.json.JSONObject it = infos.optJSONObject(i);
                    if (it == null || !type.equals(it.optString("propType"))) continue;
                    amount += parseNum(it.optString("amount", "0"));
                    if (configId == null) configId = it.optString("configId", null);
                    if (recId == null) recId = it.optString("id", null);
                }
                if (configId == null || amount <= 0) {
                    report.append("[背包] ").append(name).append(" 背包无货，跳过\n");
                    continue;
                }
                int target = (num < 0) ? 0 : Math.max(0, amount - num);
                if (amount <= target) {
                    report.append("[背包] ").append(name).append(" 已达目标（剩 ").append(amount).append("）\n");
                    continue;
                }
                boolean isDice = !"BENEFIT_GGK".equals(type);
                if (isDice && !mapOk) {
                    report.append("[背包] ").append(name).append(" 地图状态不可用，跳过\n");
                    continue;
                }
                int use = amount - target;
                int used = 0, steps = 0, coins = 0;
                String lastErr = null;
                int guard = 0;
                while (used < use && guard++ < 120) {
                    org.json.JSONObject r;
                    if (isDice) {
                        r = callApi(MAP_ROLL_API, "1.0",
                                "{\"diceId\":" + configId + ",\"diceType\":\"" + type + "\","
                                        + RhLocationContext.jsonPair() + "\"locationBefore\":" + location
                                        + ",\"mapVersion\":\"" + mapVersion
                                        + "\",\"outId\":" + System.currentTimeMillis() + ",\"version\":3}",
                                "背包掷骰");
                        String ret = firstRet(r);
                        if (ret == null || !ret.contains("SUCCESS")) {
                            lastErr = ret == null ? "无响应" : ret;
                            break;
                        }
                        org.json.JSONObject rd = r.optJSONObject("data");
                        int dr = rd == null ? 0 : parseNum(rd.optString("diceResult", "0"));
                        steps += dr;
                        location += dr; // 服务端按点数推进（10-20 三掷实测 12059+6=12065 连续吻合）
                        coins += rollCoins(rd);
                    } else {
                        r = callApi(PROP_USE_API, "1.0",
                                objJson("id", recId, "bizCode", "coinMap", "configId", configId,
                                        "outId", "rh" + System.currentTimeMillis(),
                                        "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
                                "背包刮卡");
                        String ret = firstRet(r);
                        if (ret == null || !ret.contains("SUCCESS")) {
                            lastErr = ret == null ? "无响应" : ret;
                            break;
                        }
                        coins += ggkReward(r.optJSONObject("data"));
                        sleep(400);
                    }
                    used++;
                    sleep(1200); // 防频控节流
                }
                StringBuilder sb = new StringBuilder("[背包] ").append(name).append(" 使用 ").append(used)
                        .append("/").append(use);
                if (isDice) sb.append(" · 前进 ").append(steps).append(" 步");
                sb.append(" · 收益 ").append(coins);
                if (lastErr != null) sb.append(" · 中断:").append(lastErr);
                report.append(sb).append('\n');
                if (used > 0) toast("✓ 背包 " + name + " 用 " + used + " 个");
                log("[bag-use] " + name + " used=" + used + "/" + use
                        + (isDice ? " steps=" + steps : "") + " coins=" + coins
                        + (lastErr != null ? " err=" + lastErr : ""));
            }
        } catch (Throwable t) {
            report.append("[背包] 异常 ").append(t).append('\n');
        }
    }

    /** roll 响应沿途收币合计（contentList coin + settleList treasureMapPoint；宝箱类延迟项不计入实收） */
    private static int rollCoins(org.json.JSONObject rd) {
        if (rd == null) return 0;
        int c = 0;
        org.json.JSONArray cl = rd.optJSONArray("contentList");
        if (cl != null) for (int i = 0; i < cl.length(); i++) {
            org.json.JSONObject o = cl.optJSONObject(i);
            if (o != null && "coin".equals(o.optString("contentType")))
                c += parseNum(o.optString("rewardNum", "0"));
        }
        org.json.JSONArray sl = rd.optJSONArray("settleList");
        if (sl != null) for (int i = 0; i < sl.length(); i++) {
            org.json.JSONObject o = sl.optJSONObject(i);
            if (o != null && "treasureMapPoint".equals(o.optString("contentType")))
                c += parseNum(o.optString("rewardNum", "0"));
        }
        return c;
    }

    /** prop.use 刮刮卡收益（attributes.benefitResult.extraData.amount；无 benefitResult=0） */
    private static int ggkReward(org.json.JSONObject rd) {
        if (rd == null) return 0;
        org.json.JSONObject at = rd.optJSONObject("attributes");
        org.json.JSONObject br = at == null ? null : at.optJSONObject("benefitResult");
        if (br == null) return 0;
        org.json.JSONObject ex = br.optJSONObject("extraData");
        return ex == null ? 0 : parseNum(ex.optString("amount", "0"));
    }

    /** 步数读取兜底：roll 响应在 data 顶层，init 响应在 data.userInfo；字段字符串/数值不定型，parseNum 宽松解 */
    private static int stepOf(org.json.JSONObject data) {
        int top = parseNum(data.optString("remainStep", ""));
        if (top > 0) return top;
        org.json.JSONObject ui = data.optJSONObject("userInfo");
        return ui == null ? 0 : parseNum(ui.optString("remainStep", "0"));
    }

    /** 宽松数值解析："16" / "1,000" / "3.5枚" → int（取连续数字段拼接失败则 0） */
    private static int parseNum(String s) {
        if (s == null) return 0;
        try {
            StringBuilder sb = new StringBuilder();
            for (char ch : s.toCharArray()) {
                if (ch >= '0' && ch <= '9') sb.append(ch);
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 擦亮全流程：
     * 1. 每日一次标记（存 rh_pref.json 的 polish_date，防每日重复调用）
     * 2. 一次调用 mtop.taobao.idle.item.polish v2.0 {"type":"originaljson"}，
     *    服务端自动擦亮全部在售宝贝，响应 data.exposureItems 返回擦亮的宝贝列表
     * 3. 失败码：FAIL_BIZ_IDLE_USER_POLISH_AGAIN（今日已擦过）/
     *    FAIL_BIZ_ITEM_POLISH_FAILED3（今日次数用完）→ 命中失败码也写日期标记防反复重试
     */
    private void runPolish() {
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        String prefPath = cacheDir + "/rh_pref.json";
        org.json.JSONObject pref = parseObj(readFile(prefPath).trim().isEmpty() ? "{}" : readFile(prefPath));
        if (today.equals(pref.optString("polish_date"))) {
            report.append("[擦亮] 今日已擦亮（每日一次标记），跳过\n");
            return;
        }
        org.json.JSONObject resp = callApi(POLISH_API, "2.0", "{\"type\":\"originaljson\"}", "擦亮");
        String ret = firstRet(resp);
        int n = 0;
        if (resp != null) {
            org.json.JSONObject data = resp.optJSONObject("data");
            org.json.JSONArray ei = data == null ? null : data.optJSONArray("exposureItems");
            if (ei != null) n = ei.length();
        }
        if (ret != null && ret.contains("SUCCESS")) {
            // 成功即写标记（含 n=0：服务端受理但无 exposureItems——当日已擦过/无可擦宝贝），
            // 不依赖服务端判重兜底，省每日重复调用
            report.append(n > 0 ? "[擦亮] SUCCESS：已擦亮 " + n + " 个宝贝\n"
                    : "[擦亮] SUCCESS：服务端受理（今日已擦过或无可擦宝贝）\n");
            if (n > 0) toast("✓ 擦亮 " + n + " 个宝贝");
            markPolished(prefPath, pref, today);
        } else if (ret != null && ret.contains("POLISH_AGAIN")) {
            report.append("[擦亮] 今日已擦亮过（服务端判重）\n");
            markPolished(prefPath, pref, today);
        } else if (ret != null && ret.contains("POLISH_FAILED3")) {
            report.append("[擦亮] 今日擦亮次数已用完（服务端限制）\n");
            markPolished(prefPath, pref, today);
        } else {
            // 网络失败/无响应：不写标记，下次运行重试
            report.append("[擦亮] ").append(ret == null ? "无响应，下次重试" : ret).append('\n');
        }
    }

    /** 写每日擦亮标记（成功与"已擦过"都写，防反复重试） */
    private void markPolished(String prefPath, org.json.JSONObject pref, String today) {
        try {
            pref.put("polish_date", today);
            writeFile(prefPath, pref.toString());
        } catch (Throwable t) {
            log("markPolished fail: " + t);
        }
    }

    private void browseForTask(String deliveryId, String sceneId, List<String> done, List<String> fail) {
        List<String> idList = itemPool();
        if (idList.isEmpty()) {
            report.append("[浏览] 商品源为空，跳过\n");
            return;
        }
        report.append("[浏览] 商品源 ").append(idList.size()).append(" 个\n");

        // needTimes：从任务响应无法稳定拿到时按 10 兜底（"浏览10次推荐"）
        int needTimes = 10;
        int okCount = 0;      // 本地成功计数（服务端 times 解析不到时兜底）
        int idx = 0;          // 当前商品索引
        int illegalRun = 0;   // 连续 ILLEGAL 计数：>=3 判定任务语义不匹配（如买/卖宝贝），中止
        long gap = BROWSE_GAP_MS;

        for (int step = 0; step < BROWSE_MAX_STEPS && !abort; step++) {
            if (idx >= idList.size()) {
                report.append("[浏览] 商品列表用尽\n");
                break;
            }
            String implId = idList.get(idx);
            org.json.JSONObject tr = callApi(TASK_TRIGGER_API, "1.0",
                    objJson("sceneId", sceneId, "implId", implId, "deliveryId", deliveryId),
                    "浏览上报 " + (idx + 1) + "/" + idList.size());
            String ret = firstRet(tr);
            if (tr == null) {
                report.append("[浏览] 上报无响应，中止\n");
                break;
            }
            if (ret != null && ret.contains("SUCCESS")) {
                illegalRun = 0;
                okCount++;
                org.json.JSONObject data = tr.optJSONObject("data");
                org.json.JSONObject dto = data == null ? null : data.optJSONObject("taskDTO");
                org.json.JSONObject pg = dto == null ? null : dto.optJSONObject("progressDTO");
                int times = pg == null ? -1 : parseInt(optStr(pg, "times"), -1);
                int need = pg == null ? -1 : parseInt(optStr(pg, "needTimes"), -1);
                if (need > 0) needTimes = need;
                report.append("[浏览] step ").append(idx).append(" ok okCount=").append(okCount)
                        .append(" times=").append(times).append("/").append(need).append('\n');
                if ((need > 0 && times >= need) || okCount >= needTimes) {
                    report.append("[浏览] 任务完成（").append(okCount).append("次），开始领奖\n");
                    claimOne(deliveryId, "浏览任务", done, fail);
                    return;
                }
                idx = times > idx ? times : idx + 1; // 以服务端 times 为下一索引
                gap = BROWSE_GAP_MS;                  // 成功后恢复基础间隔
            } else if (ret != null && ret.contains("ILLEGAL")) {
                illegalRun++;
                if (illegalRun >= 3) {
                    // 语义不匹配（idleCoinFeesAnchor 也被"买/卖宝贝"等真实行为任务复用），
                    // 商品上报对它永远无效，立即中止避免空转
                    report.append("[浏览] 连续 ").append(illegalRun)
                            .append(" 次 ILLEGAL，任务需真实行为，中止\n");
                    return;
                }
                gap = Math.min(gap + 4000, 16000);
                report.append("[浏览] step ").append(idx).append(" ILLEGAL ").append(illegalRun)
                        .append("/3，退避 ").append(gap / 1000).append("s\n");
                sleep(gap);
                continue;
            } else {
                report.append("[浏览] step ").append(idx).append(" -> ").append(ret).append("，换下一个\n");
                idx++;
            }
            sleep(gap);
        }
        // 循环结束：若本地成功数足够也领一次
        if (okCount >= needTimes) {
            report.append("[浏览] 达到次数（兜底判定），领奖\n");
            claimOne(deliveryId, "浏览任务", done, fail);
        }
    }

    /* ============================ 领奖 ============================ */

    private void claimOne(String deliveryId, String title, List<String> done, List<String> fail) {
        org.json.JSONObject resp = callApi(TASK_BENEFIT_API, "1.0",
                objJson("sceneId", "496", "deliveryId", deliveryId,
                        "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
                "领奖 " + title);
        String ret = firstRet(resp);
        String rewardDesc = "";
        if (resp != null) {
            org.json.JSONObject data = resp.optJSONObject("data");
            org.json.JSONObject dto = data == null ? null : data.optJSONObject("taskDTO");
            if (dto != null) {
                org.json.JSONArray rewards = dto.optJSONArray("rewards");
                rewardDesc = rewards == null ? "" : rewards.toString();
            }
        }
        boolean ok = ret != null && ret.contains("SUCCESS");
        // SUCCESS 即视为成功（rewards 空可能是非币奖励或当日已领的幂等响应）
        if (ok) {
            done.add(title + "(" + deliveryId + ")");
            report.append("[领取] ").append(title).append(" -> ").append(ret)
                    .append(" rewards=").append(rewardDesc).append('\n');
            toast("✓ " + title + coinSuffix(rewardDesc));
        } else {
            fail.add(title + "(" + deliveryId + ")");
            report.append("[领取] ").append(title).append(" -> ").append(ret == null ? "无响应" : ret).append('\n');
        }
    }

    /* ============================ 请求与文件通道 ============================ */

    /** direct 发请求 + 轮询响应分文件。返回解析后的 JSON 或 null */
    private org.json.JSONObject callApi(String api, String ver, String paramsJson, String tag) {
        String f = respFile(api);
        sleep(300); // 让指纹稳定
        String fp0 = fileFp(f);
        boolean sent = ApiDirect.send(cl, api, ver, paramsJson);
        if (!sent) {
            log("[tasks] direct send failed: " + api);
            return null;
        }
        long deadline = System.currentTimeMillis() + REQ_TIMEOUT_S * 1000L;
        while (System.currentTimeMillis() < deadline && !abort) {
            sleep(400);
            String fp = fileFp(f);
            if (fp != null && !fp.equals(fp0)) {
                String body = readFile(f);
                try {
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    String ret = firstRet(j);
                    log("[tasks] " + tag + " -> " + ret);
                    return j;
                } catch (Throwable e) {
                    log("[tasks] resp parse fail " + api + ": " + e);
                    return null;
                }
            }
        }
        log("[tasks] timeout: " + tag);
        return null;
    }

    private String respFile(String api) {
        String last = api;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        return cacheDir + "/rh_resp_" + last + ".json";
    }

    private String fileFp(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists() || f.length() == 0) return null;
            return f.length() + ":" + f.lastModified();
        } catch (Throwable t) {
            return null;
        }
    }

    private String readFile(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void writeFile(String path, String content) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(path, false);
            w.write(content);
            w.close();
        } catch (Throwable t) {
            log("writeFile fail: " + t);
        }
    }

    /* ============================ JSON 工具 ============================ */

    private List<org.json.JSONObject> extractTasks(org.json.JSONObject resp) {
        if (resp == null) return null;
        org.json.JSONObject data = resp.optJSONObject("data");
        org.json.JSONArray arr = data == null ? null : data.optJSONArray("taskList");
        if (arr == null) return null;
        List<org.json.JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject t = arr.optJSONObject(i);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static String firstRet(org.json.JSONObject resp) {
        if (resp == null) return null;
        org.json.JSONArray ret = resp.optJSONArray("ret");
        return ret != null && ret.length() > 0 ? ret.optString(0) : String.valueOf(resp.opt("ret"));
    }

    private static String objJson(String... kv) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) j.put(kv[i], kv[i + 1]);
            return j.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static org.json.JSONObject parseObj(String s) {
        try {
            return s == null ? new org.json.JSONObject() : new org.json.JSONObject(s);
        } catch (Throwable t) {
            return new org.json.JSONObject();
        }
    }

    private static boolean opt(org.json.JSONObject j, String k, boolean def) {
        return j == null ? def : j.optBoolean(k, def);
    }

    private static String str(org.json.JSONObject j, String k) {
        return j == null ? null : j.optString(k, null);
    }

    private static String optStr(org.json.JSONObject j, String k) {
        return j.optString(k, "");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /* ============================ 收尾 ============================ */

    private void finish(long t0) {
        // 收尾补掷：主流程前段的掷骰覆盖不到浏览/游戏任务执行期间新入账的步数
        //（10-17 实测：开头掷到 remain=0，跑完任务后步数池又剩 13），收口处再掷一次
        doMapRoll();
        report.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
        String out = report.toString();
        writeFile(cacheDir + "/rh_tasks_result.txt", out);
        log("=== task auto done ===\n" + out);
        notifyDone(out);
        // 收尾气泡：本轮共领多少项
        int done = 0, failed = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("领取成功")) done = parseCount(line);
            else if (line.startsWith("领取失败")) failed = parseCount(line);
        }
        toast("钓鱼完成 · 成功 " + done + (failed > 0 ? " · 失败 " + failed : "")
                + " · 用时 " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    /** 钓鱼结果通知栏推送（免连电脑可见） */
    private void notifyDone(String out) {
        try {
            android.content.Context c = appCtx;
            if (c == null) return;
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channelId = "reward_hook";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        channelId, "任务引擎结果", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            }
            int done = 0, failed = 0;
            for (String line : out.split("\n")) {
                if (line.startsWith("领取成功")) done = parseCount(line);
                else if (line.startsWith("领取失败")) failed = parseCount(line);
            }
            String summary = done > 0 || failed > 0
                    ? "领取 " + done + " 笔" + (failed > 0 ? "，失败 " + failed + " 笔" : "")
                    : "钓鱼完成（无领取动作）";
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, channelId)
                    : new android.app.Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_send)
                    .setContentTitle("咸鱼小猪手 · " + summary)
                    .setContentText(summary)
                    .setStyle(new android.app.Notification.BigTextStyle()
                            .bigText(out.length() > 3500 ? out.substring(0, 3500) : out))
                    .setAutoCancel(true);
            nm.notify(1001, b.build());
            log("notification sent: " + summary);
        } catch (Throwable t) {
            log("notify FAILED: " + t);
        }
    }

    /** 解析 "领取成功 2: [a, b]" 行中的数字 */
    private static int parseCount(String line) {
        try {
            int colon = line.indexOf(':');
            return Integer.parseInt(line.substring("领取成功".length(), colon).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            // 外层循环通过 abort 无法感知 interrupt；此处保持安静
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }
}
