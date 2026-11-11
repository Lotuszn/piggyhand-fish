package com.piggyhand.fish;

import android.content.Context;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.4.1 鱼塘引擎（RhPondTask）——「神奇鱼塘」一级菜单的两个子功能都在这里：
 *
 * 【自动赚饲料】run(ctx)：
 *   进程内直调（复用 ApiDirect 通道 + 全局 MtopFinishEvent 落盘），全自动完成「赚饲料」可纯接口部分：
 *     1. oliver.show 拉任务列表（activityId=104042，taskInstanceId 即领奖 taskId）
 *     2. v1.5.1 触发 ACCEPTED 任务：主路径 mtop.taobao.idle.cube.task.trigger v1.0
 *        （activityId + taskInstanceId + 毫秒幂等键），2026-11-11 实测 16/16 一发全中
 *        ——CSJ 浏览类/跨端引流/H5 小游戏/桌面访问全部直接转 AWARD，服务端对
 *        104042 清单不做真实行为校验；cube 失败时兜底 play.ad.event.push 广告位推送；
 *        多轮任务按 progressDTO 剩余轮次连发（单任务上限 5 发防呆），触发后重拉列表
 *     3. 过滤 idleTaskStatus==AWARD 的任务逐个 joy.community.activity.task.award 领取
 *        —— v1.4.1 多轮修正：awardIndexList 不再写死 "1"，从任务自身 awardInfoDTOMap 里挑
 *        未领取（status!=2）的档位；多轮任务（看视频 3 轮）第 2/3 轮 awardIndex="1" 会报
 *        FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD（2026-11-10 实测）
 *     4. forest.energy.list 拉能量/签到气泡 → 逐个 forest.energy.issue 领取
 *     5. 复核状态，输出报告（rh_tasks_pond.txt + 返回值）
 *
 *   【自动投喂】runFeed(ctx)：
 *   纯协议投喂 forest.animal.feed v1.0，body={feedId,fishAdCode,gps}：
 *     - feedId 是「投喂活动记录 ID」（多肉盆栽盲盒 FISH_POND_FEED_DUOROU，2026-11-30 过期），
 *       不是一次性幂等号 —— 同一 body 重发即连续投喂，每次服务端扣 10g 饲料（2026-11-10 实测）。
 *     - feedId 解析链：上次投喂响应缓存 rh_resp_feed.json → 本引擎持久档 rh_pond_feedid.txt。
 *       都没有时报告提示先在鱼塘页投喂一次（页面动作会触发 mtop 带出记录），不猜不硬编码。
 *     - 固定语义：一直投到饲料余额不足 10g 为止（v1.4.2 起，无次数自定义）；
 *       连续 2 发失败止损；200 发硬上限防余额字段异常时死循环。
 *
 * 行为类任务（浏览/视频/搜索等）服务端有行为校验的部分用户手动/页面完成后变 AWARD，
 * 本引擎下次运行自动收割。
 */
public final class RhPondTask {

    private RhPondTask() {}

    /** 共享 LVL 键：自动赚饲料 */
    static final String LVL_KEY_TASK = "@pondTask";
    /** 共享 LVL 键：自动投喂 */
    static final String LVL_KEY_FEED = "@pondFeed";
    /** feedId 持久档：投喂成功后写一次，冷启动后仍可用（external files 不被闲鱼清理） */
    private static final String FEEDID_FILE = "rh_pond_feedid.txt";

    /**
     * 打开闲鱼自动跑一次（用户语义：开启状态打开闲鱼就自动跑）。
     * 当日标记存闲鱼 internal files（external cache 会被闲鱼清理）；
     * 手动 socket POND 触发不受本标记与开关限制。
     */
    public static void autoRunDaily(final Context ctx) {
        try {
            // 仅主进程
            String proc = null;
            try {
                proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return;

            boolean on;
            try {
                ConfigManager cfg = ConfigManager.load();
                on = cfg != null && cfg.pond;
            } catch (Throwable t) {
                on = true; // 配置读取失败按默认开
            }
            if (!on) {
                RhFileLog.write("[pond] auto-run disabled by config (pond=false), skip");
                return;
            }

            // 当日标记
            String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File dir = ctx.getFilesDir();
            java.io.File mark = new java.io.File(dir != null ? dir
                    : new java.io.File("/data/data/com.taobao.idlefish/files"), "rh_pond_date.txt");
            String last = "";
            try {
                last = new String(java.nio.file.Files.readAllBytes(mark.toPath()), "UTF-8").trim();
            } catch (Throwable ignored) {
            }
            if (today.equals(last)) {
                RhFileLog.write("[pond] auto-run already done today, skip");
                return;
            }

            // v1.4.1 LVL 门：当日 @pondTask 计满 2 跳过自动跑（手动 POND 不受此限）
            if (FishTaskAuto.lvlSkipShared(ctx, LVL_KEY_TASK)) {
                RhFileLog.write("[pond] auto-run skip: LVL " + LVL_KEY_TASK + " reached daily cap");
                return;
            }

            // 40s 等 mtop 通道热 + 闲鱼初始化完成
            RhFileLog.write("[pond] auto-run scheduled in 40s");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(40000);
                        RhPondTask.run(ctx);
                        // v1.4.1 LVL 计数：本轮调度无论成败都计一次（与签到类引擎语义一致）
                        FishTaskAuto.lvlBumpShared(ctx, LVL_KEY_TASK);
                        // 落当日标记
                        try {
                            String td = new java.text.SimpleDateFormat("yyyyMMdd",
                                    java.util.Locale.US).format(new java.util.Date());
                            java.io.File d2 = ctx.getFilesDir();
                            java.io.File m2 = new java.io.File(d2 != null ? d2
                                    : new java.io.File("/data/data/com.taobao.idlefish/files"),
                                    "rh_pond_date.txt");
                            java.io.FileWriter w = new java.io.FileWriter(m2, false);
                            w.write(td);
                            w.close();
                        } catch (Throwable ignored) {
                        }
                        RhFileLog.write("[pond] auto-run finished, mark saved");
                    } catch (Throwable t) {
                        RhFileLog.write("[pond] auto-run fail: " + t);
                    }
                }
            }, "rh-pond-auto").start();
        } catch (Throwable t) {
            RhFileLog.write("[pond] autoRunDaily err: " + t);
        }
    }

    /** 城市区号 + 定位：默认走 RhLocationContext 动态捕获（ConfigManager pondFishAdCode/pondGps 可手动覆盖） */

    /* ==================== 自动投喂（v1.4.1） ==================== */

    /**
     * 打开闲鱼自动投喂一次：开关 pondFeed=true 且当日未跑时延迟 46s 触发
     * （与赚饲料 40s / 会员 45s 错开，避免同时抢 mtop 通道）。
     */
    public static void autoRunFeedDaily(final Context ctx) {
        try {
            String proc = null;
            try {
                proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return;

            boolean on;
            try {
                ConfigManager cfg = ConfigManager.load();
                on = cfg != null && cfg.pondFeed;
            } catch (Throwable t) {
                on = false; // 配置读失败按默认关（投喂直接消耗余额，宁可少做不错做）
            }
            if (!on) {
                RhFileLog.write("[pondfeed] auto-run disabled by config (pondFeed=false), skip");
                return;
            }

            // 当日标记（与赚饲料的 rh_pond_date.txt 分档）
            String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File dir = ctx.getFilesDir();
            java.io.File mark = new java.io.File(dir != null ? dir
                    : new java.io.File("/data/data/com.taobao.idlefish/files"), "rh_pondfeed_date.txt");
            String last = "";
            try {
                last = new String(java.nio.file.Files.readAllBytes(mark.toPath()), "UTF-8").trim();
            } catch (Throwable ignored) {
            }
            if (today.equals(last)) {
                RhFileLog.write("[pondfeed] auto-run already done today, skip");
                return;
            }

            // LVL 门：当日触发次数达上限时跳过自动跑（手动 PONDFEED 不受此限）
            if (FishTaskAuto.lvlSkipShared(ctx, LVL_KEY_FEED)) {
                RhFileLog.write("[pondfeed] auto-run skip: " + LVL_KEY_FEED + " reached daily cap");
                return;
            }

            RhFileLog.write("[pondfeed] auto-run scheduled in 46s (until drained)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(46000);
                        RhPondTask.runFeed(ctx);
                        FishTaskAuto.lvlBumpShared(ctx, LVL_KEY_FEED);
                        // 落当日标记
                        try {
                            String td = new java.text.SimpleDateFormat("yyyyMMdd",
                                    java.util.Locale.US).format(new java.util.Date());
                            java.io.File d2 = ctx.getFilesDir();
                            java.io.File m2 = new java.io.File(d2 != null ? d2
                                    : new java.io.File("/data/data/com.taobao.idlefish/files"),
                                    "rh_pondfeed_date.txt");
                            java.io.FileWriter w = new java.io.FileWriter(m2, false);
                            w.write(td);
                            w.close();
                        } catch (Throwable ignored) {
                        }
                        RhFileLog.write("[pondfeed] auto-run finished, mark saved");
                    } catch (Throwable t) {
                        RhFileLog.write("[pondfeed] auto-run fail: " + t);
                    }
                }
            }, "rh-pondfeed-auto").start();
        } catch (Throwable t) {
            RhFileLog.write("[pondfeed] autoRunFeedDaily err: " + t);
        }
    }

    /** 手动/自动统一入口：投喂进行到饲料余额耗尽（v1.4.2 固定语义，无次数自定义） */
    /**
     * 鱼塘「赚饲料」面板的每日「领饲料」。返回一行摘要（写进报告）。
     * 接口：play.sign.in.home（查）+ play.sign.in（领），playId=101。
     */
    static String dailyFeedSign(Context ctx) {
        long pid = pondSignPlayId(ctx);
        JSONObject body = new JSONObject();
        try {
            body.put("playId", pid);
            body.put("usertoken", "");
        } catch (Throwable ignored) {
        }

        String homeRaw = callAndWait(ctx, "mtop.taobao.idle.play.sign.in.home", "1.0",
                body.toString(), "pfsh", 6000);
        JSONObject home = parse(homeRaw);
        String days = "?", awarded = "?";
        if (home != null && hasSuccess(home) && home.optJSONObject("data") != null) {
            JSONObject d = home.optJSONObject("data");
            days = d.optString("signInDays", "?");
            awarded = d.optString("signInAwarded", "?");
        }

        String doRaw = callAndWait(ctx, "mtop.taobao.idle.play.sign.in", "1.0",
                body.toString(), "pfsd", 6000);
        JSONObject so = parse(doRaw);
        String ret = briefRet(doRaw);
        if (so != null && hasSuccess(so)) {
            JSONObject d = so.optJSONObject("data");
            String nd = d == null ? "" : d.optString("signInDays", "");
            return "领饲料 playId=" + pid + " → 成功" + (nd.length() > 0 ? "（连签 " + nd + " 天）" : "")
                    + "，面板剩余饲料 +10g";
        }
        if (ret != null && ret.contains("SIGNED_IN")) {
            return "领饲料 playId=" + pid + " → 今日已领（连签 " + days + " 天，awarded=" + awarded + "）";
        }
        return "领饲料 playId=" + pid + " → 失败 " + ret;
    }

    /** 鱼塘签到 playId：rh_pond_sign_playid.txt 覆盖（可选）→ 内置 101 */
    private static long pondSignPlayId(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, "rh_pond_sign_playid.txt");
            if (f.exists() && f.length() > 0) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(b);
                in.close();
                String s = new String(b, "UTF-8").trim();
                if (s.length() > 0) return Long.parseLong(s);
            }
        } catch (Throwable ignored) {
        }
        return 101L;
    }

    public static String runFeed(Context ctx) {
        return runFeedDrain(ctx);
    }

    /**
     * 协议投喂至饲料余额不足 10g（每发扣 10g）。返回报告全文（同时写 rh_tasks_pondfeed.txt）。
     * feedId 解析链见类注释；解析不到不猜、不硬编码，报告提示先在鱼塘页投喂一次。
     */
    private static String runFeedDrain(Context ctx) {
        long t0 = System.currentTimeMillis();
        RhReport sb = new RhReport();
        String fish = RhLocationContext.fishAdCode(), gps = RhLocationContext.gps();
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg != null) {
                if (cfg.pondFishAdCode != null && cfg.pondFishAdCode.length() > 0)
                    fish = cfg.pondFishAdCode;
                if (cfg.pondGps != null && cfg.pondGps.length() > 0)
                    gps = cfg.pondGps;
            }
        } catch (Throwable ignored) {
        }
        sb.append("🍚 自动投喂报告 ").append(now()).append('\n');

        /* ---------- 1. 解析 feedId ---------- */
        long feedId = resolveFeedId(ctx);
        if (feedId <= 0) {
            sb.append("[1] feedId 未拿到：请先在鱼塘页手动投喂一次（页面动作会把投喂记录带进协议缓存），")
                    .append("之后本功能即可全自动\n");
            finishFeed(ctx, sb, t0);
            return sb.toString();
        }
        sb.append("[1] feedId=").append(feedId).append("（复用投喂记录，同 body 重发即连续投喂）\n");

        /* ---------- 2. 连续投喂至余额耗尽 ---------- */
        int okCnt = 0, failCnt = 0, balance = -1;
        // 200 发硬上限：纯防呆（2000g > 任何真实余额），余额字段异常不降时兜底退出，避免死循环
        final int HARD_CAP = 200;
        for (int i = 0; i < HARD_CAP; i++) {
            String body;
            try {
                JSONObject data = new JSONObject();
                data.put("feedId", feedId);
                data.put("fishAdCode", fish);
                data.put("gps", gps);
                body = callAndWait(ctx,
                        "mtop.taobao.idle.forest.animal.feed", "1.0",
                        data.toString(), "feed", 8000);
            } catch (Throwable t) {
                failCnt++;
                sb.append("  ✗ 第").append(i + 1).append("发 构造请求异常: ").append(t).append('\n');
                break;
            }
            JSONObject rj = parse(body);
            boolean ok = rj != null && hasSuccess(rj);
            if (ok) {
                okCnt++;
                balance = intOf(rj.optJSONObject("data"), "canFeedTime", balance);
                sb.append("  ✓ 第").append(i + 1).append("发 余额 ")
                        .append(balance >= 0 ? String.valueOf(balance) : "?").append("g\n");
                // 余额不足 10g：下一发必失败，提前收手
                if (balance >= 0 && balance < 10) {
                    sb.append("  · 余额不足 10g，停止\n");
                    break;
                }
            } else {
                failCnt++;
                sb.append("  ✗ 第").append(i + 1).append("发 ").append(briefRet(body)).append('\n');
                /* 服务端「服务开小差啦」是瞬时限流：2026-11-11 米9 实测连发 24 发后触发，
                   冷却 15~20s 可继续。旧逻辑「连失败 2 发就停」会把余额永久留在账上，
                   这里改成退避重试，只有连续失败 8 次（≈2 分钟无恢复）才判定真打不动。 */
                if (failCnt >= 8) {
                    sb.append("  · 连续 8 发失败，判定服务端拦截，停止\n");
                    break;
                }
                try {
                    Thread.sleep(15000L);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            try {
                Thread.sleep(1300); // 防频控；实测 1.3s 间隔连发无拦截
            } catch (InterruptedException e) {
                break;
            }
        }
        sb.append("[2] 投喂: 成功").append(okCnt).append(" 发(=−").append(okCnt * 10)
                .append("g) 失败").append(failCnt).append(" 发，当前余额 ")
                .append(balance >= 0 ? String.valueOf(balance) : "?").append("g\n");
        finishFeed(ctx, sb, t0);
        return sb.toString();
    }

    /**
     * feedId 解析链：
     *   ① 缓存 rh_resp_feed.json（任何一次投喂——页面动作或本引擎——都会被全局 hook 落盘）
     *      → data.record.feedInfo.feedId；
     *   ② 本引擎持久档 rh_pond_feedid.txt（上次投喂成功后写入，闲鱼不清理 external files）。
     * 都拿不到返回 -1（报告里引导用户先手动投喂一次），绝不硬编码过期活动 ID。
     */
    private static long resolveFeedId(Context ctx) {
        // ⓿ 动物列表（最权威，2026-11-11 米9 实测）：forest.animal.list 的
        //    data.result[].feedInfo.feedId 就是当前投喂活动记录 ID，
        //    不需要用户先手动投喂一次，也不依赖页面动作留下的缓存。
        try {
            String lb = callAndWait(ctx, "mtop.taobao.idle.forest.animal.list", "1.0",
                    "{}", "animallist", 6000);
            JSONObject lj = parse(lb);
            if (lj != null && hasSuccess(lj)) {
                JSONArray arr = lj.getJSONObject("data").optJSONArray("result");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    JSONObject fi = a.optJSONObject("feedInfo");
                    long id = fi == null ? -1 : fi.optLong("feedId", -1);
                    if (id > 0) {
                        saveFeedId(ctx, id);
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // ① 响应缓存
        try {
            java.io.File cacheDir = ctx.getExternalCacheDir();
            if (cacheDir == null) cacheDir = ctx.getCacheDir();
            java.io.File f = new java.io.File(cacheDir, "rh_resp_feed.json");
            if (f.exists() && f.length() > 0) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(b);
                in.close();
                JSONObject j = new JSONObject(new String(b, "UTF-8"));
                JSONObject rec = j.optJSONObject("data");
                if (rec != null) rec = rec.optJSONObject("record");
                if (rec != null) rec = rec.optJSONObject("feedInfo");
                long id = rec == null ? -1 : rec.optLong("feedId", -1);
                if (id > 0) {
                    saveFeedId(ctx, id);
                    return id;
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 持久档
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            java.io.File f = new java.io.File(dir, FEEDID_FILE);
            if (f.exists() && f.length() > 0) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(b);
                in.close();
                return Long.parseLong(new String(b, "UTF-8").trim());
            }
        } catch (Throwable ignored) {
        }
        // ③ 全量响应缓存兜底：投喂记录（feedId）只要在任意一次响应里出现过就能捡到。
        //    典型来源是用户打开神奇鱼塘页时 H5 拉活动配置的响应，落在 cache/rh_resp_*.json
        //    与旁档 rh_apiproto.jsonl 里；比只认 rh_resp_feed.json 覆盖面大得多。
        long scanned = scanFeedIdFromCaches(ctx);
        if (scanned > 0) {
            saveFeedId(ctx, scanned);
            RhFileLog.write("[pondfeed] feedId 从响应缓存捞到: " + scanned);
            return scanned;
        }
        return -1;
    }

    /** 扫 cache/rh_resp_*.json 与 rh_apiproto.jsonl，取最大的 feedId（同一活动记录，取新值） */
    private static long scanFeedIdFromCaches(Context ctx) {
        long best = -1;
        try {
            java.io.File dir = ctx.getExternalCacheDir();
            if (dir == null) dir = ctx.getCacheDir();
            if (dir == null || !dir.isDirectory()) return -1;
            java.io.File[] fs = dir.listFiles();
            if (fs == null) return -1;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"feedId\"\\s*:\\s*\"?(\\d{4,})");
            for (java.io.File f : fs) {
                String n = f.getName();
                if (!n.startsWith("rh_resp_") && !"rh_apiproto.jsonl".equals(n)) continue;
                if (f.length() <= 0 || f.length() > 8L * 1024 * 1024) continue; // 大文件跳过，防 OOM
                try {
                    byte[] b = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    in.read(b);
                    in.close();
                    java.util.regex.Matcher m = p.matcher(new String(b, "UTF-8"));
                    while (m.find()) {
                        long id = Long.parseLong(m.group(1));
                        if (id > best) best = id;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** 投喂成功后把 feedId 落持久档（原子写：tmp → rename） */
    private static void saveFeedId(Context ctx, long feedId) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, FEEDID_FILE);
            java.io.File tmp = new java.io.File(dir, FEEDID_FILE + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp, false);
            w.write(String.valueOf(feedId));
            w.close();
            if (!tmp.renameTo(f)) {
                java.io.FileWriter w2 = new java.io.FileWriter(f, false);
                w2.write(String.valueOf(feedId));
                w2.close();
                tmp.delete();
            }
        } catch (Throwable t) {
            RhFileLog.write("[pondfeed] saveFeedId fail: " + t);
        }
    }

    private static void finishFeed(Context ctx, RhReport sb, long t0) {
        sb.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
        try {
            java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "rh_tasks_pondfeed.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable t) {
            RhFileLog.write("[pondfeed] write report fail: " + t);
        }
        RhFileLog.write(sb.toString());
    }

    private static int intOf(JSONObject o, String key, int def) {
        if (o == null) return def;
        try {
            String v = String.valueOf(o.get(key));
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 深链 action 解两层 URL 编码（闲鱼把它编码了两遍，2026-11-11 实测） */
    private static String decodeTwice(String s) {
        try {
            String r = java.net.URLDecoder.decode(s == null ? "" : s, "UTF-8");
            return java.net.URLDecoder.decode(r, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }

    /** 从解码后的深链取广告位码；没有返回 null（对应 H5/跨端/无动作任务） */
    private static String extractAdCode(String decoded) {
        if (decoded == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("adPositionCode[^A-Za-z0-9]*([A-Za-z0-9_]+)").matcher(decoded);
        return m.find() ? m.group(1) : null;
    }

    /** 5 位小写随机 token：服务端只校验 token 非空且与 implId 尾缀一致（2026-11-11 实测） */
    private static String newToken() {
        String abc = "abcdef";
        java.util.Random rnd = new java.util.Random();
        StringBuilder b = new StringBuilder(5);
        for (int i = 0; i < 5; i++) b.append(abc.charAt(rnd.nextInt(abc.length())));
        return b.toString();
    }

    /** 后台线程调用；返回报告全文（同时写 rh_tasks_pond.txt） */
    public static String run(Context ctx) {
        long t0 = System.currentTimeMillis();
        RhReport sb = new RhReport(); // 1.1.3 每行带 HH:mm:ss 时间戳
        String fish = RhLocationContext.fishAdCode(), gps = RhLocationContext.gps();
        try {
            ConfigManager cfg = ConfigManager.load();
            if (cfg != null) {
                if (cfg.pondFishAdCode != null && cfg.pondFishAdCode.length() > 0)
                    fish = cfg.pondFishAdCode;
                if (cfg.pondGps != null && cfg.pondGps.length() > 0)
                    gps = cfg.pondGps;
            }
        } catch (Throwable ignored) {
        }
        sb.append("🐟 鱼塘任务报告 ").append(now()).append('\n');
        sb.append("fishAdCode=").append(fish == null ? "(动态缺省)" : fish)
                .append(" gps=").append(gps == null ? "(动态缺省)" : gps).append('\n');

        /* ---------- 0. 「赚饲料」面板的每日「领饲料」（连续签到） ----------
         * 2026-11-11 现场取证：面板里那颗橙色「领饲料」按钮打的是
         *   mtop.taobao.idle.play.sign.in   body={"playId":101}
         * playId=101 是鱼塘玩法实例 ID（游戏中心是 4），服务端固定，不随账号变。
         * 领完面板顶部「剩余饲料」0g → 10g，今天那格变「已领取」、按钮变「明天再来」。
         * 幂等：已签返回 FAIL_BIZ_PLAY_SIGNED_IN，不报错只记账。
         */
        sb.append("[0] ").append(dailyFeedSign(ctx)).append('\n');

        /* ---------- 1. 任务列表 ---------- */
        // show 请求体全程复用一份（触发后重拉、复核都用它）
        final String showReq = "{\"activityId\":\"104042\",\"extend\":{}," + RhLocationContext.jsonPair()
                + "\"needBenefits\":true,\"needDetails\":true,\"needHadWin\":true,"
                + "\"showEmptyInventory\":false,\"withInventoryDetail\":false}";
        String showBody = callAndWait(ctx, "mtop.taobao.idle.oliver.show", "2.0", showReq, "show", 6000);
        JSONObject showJson = parse(showBody);
        JSONArray tasks = null;
        int nAward = 0, nAcc = 0, nComp = 0;
        if (showJson != null) {
            try {
                tasks = showJson.getJSONObject("data").getJSONArray("taskList");
                for (int i = 0; i < tasks.length(); i++) {
                    String st = tasks.getJSONObject(i).optString("idleTaskStatus");
                    if ("AWARD".equals(st)) nAward++;
                    else if ("ACCEPTED".equals(st)) nAcc++;
                    else if ("COMPLETE".equals(st)) nComp++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (tasks == null) {
            sb.append("[1] 任务列表拉取失败 ").append(briefRet(showBody)).append('\n');
            finish(ctx, sb, t0);
            return sb.toString();
        }
        sb.append("[1] 任务列表: ").append(tasks.length()).append(" 个 (AWARD=").append(nAward)
                .append(" ACCEPTED=").append(nAcc).append(" COMPLETE=").append(nComp).append(")\n");

        /* ---------- 2. 触发 ACCEPTED 任务（v1.5.1）----------
         * 主路径（2026-11-11 实测 16/16 一发全中）：
         *   mtop.taobao.idle.cube.task.trigger v1.0
         *   {activityId:"104042", fishAdCode, gps, idempotentKey:毫秒, taskInstanceId}
         * 对全部 ACCEPTED（含 CSJ 浏览类 xyfishpool_*、跨端美团/百度系/快手/菜鸟/
         * 点淘/支付宝/点点、H5 小游戏、桌面访问）一发直接转 AWARD——服务端对
         * 104042 清单不做真实行为校验。响应 data.idleTaskDTO.idleTaskStatus
         * 非 ACCEPTED 即停（多轮任务每发推进一轮）。
         * 兜底（仅 cube 失败时）：play.ad.event.push 广告位推送（v1.5.0 路线，
         * 对 fishpool2026/fishPoolVideoTask01/fishPoolMMSearch01/fishPoolMMGuobu01 有效）。
         * 接口史：uploadolivertasktrigger 是 496 场景专用，对 104042 报参数不合法，勿再试。
         */
        int pushOk = 0, pushFail = 0, pushSkip = 0;
        // 领奖计数提前声明：多轮任务必须「推一轮→立刻领一轮」，领奖结果要在触发循环里累计
        int gain = 0, okCnt = 0, failCnt = 0;
        sb.append("[2] 触发 ACCEPTED 任务:\n");
        for (int i = 0; i < tasks.length(); i++) {
            try {
                JSONObject t = tasks.getJSONObject(i);
                if (!"ACCEPTED".equals(t.optString("idleTaskStatus"))) continue;
                String title = "";
                try {
                    title = t.getJSONObject("materialDTO").optString("title");
                } catch (Throwable ignored) {
                }
                long tidNum = 0;
                String tidStr = t.optString("taskInstanceId");
                try {
                    tidNum = Long.parseLong(tidStr);
                } catch (Throwable ignored) {
                }
                if (tidNum <= 0) {
                    pushSkip++;
                    sb.append("  - ").append(title).append(" taskInstanceId 非数字，跳过\n");
                    continue;
                }
                // 多轮任务按 progressDTO 算剩余轮次，一轮一发；单任务最多推 5 发防呆
                JSONObject prog = t.optJSONObject("progressDTO");
                int times = prog == null ? 0 : intOf(prog, "times", 0);
                int maxTimes = prog == null ? 1 : intOf(prog, "maxTimes", 1);
                if (maxTimes < 1) maxTimes = 1;
                int need = maxTimes - times;
                if (need < 1) need = 1;
                if (need > 5) need = 5;
                int doneThis = 0;
                boolean hardFail = false;
                for (int r = 0; r < need && !hardFail; r++) {
                    // 主路径：cube.task.trigger（毫秒幂等键，间隔 1.5s 保证不重）
                    JSONObject data = new JSONObject();
                    data.put("activityId", "104042");
                    data.put("fishAdCode", fish);
                    data.put("gps", gps);
                    data.put("idempotentKey", System.currentTimeMillis());
                    data.put("taskInstanceId", tidNum);
                    String body = callAndWait(ctx, "mtop.taobao.idle.cube.task.trigger", "1.0",
                            data.toString(), "trigger", 6000);
                    JSONObject rj = parse(body);
                    boolean ok = rj != null && hasSuccess(rj);
                    // 响应带最新状态：非 ACCEPTED 说明轮次已推满，剩余发数省掉
                    String st = "";
                    try {
                        st = rj.getJSONObject("data").getJSONObject("idleTaskDTO")
                                .optString("idleTaskStatus");
                    } catch (Throwable ignored) {
                    }
                    if (!ok) {
                        // 兜底：广告位推送（play.ad.event.push，v1.5.0 路线）
                        String cubeErr = briefRet(body);
                        String action = "";
                        try {
                            action = t.getJSONObject("materialDTO").optString("action");
                        } catch (Throwable ignored) {
                        }
                        // action 是双重 URL 编码的深链，必须解两层再取码，
                        // 否则会把 %22 的 "22" 当成码值（2026-11-11 实测踩坑）
                        String code = extractAdCode(decodeTwice(action));
                        if (code == null) {
                            pushFail++;
                            sb.append("  ✗ ").append(title).append(" cube:").append(cubeErr)
                                    .append("（无广告位码可兜底）\n");
                            hardFail = true;
                        } else {
                            String token = newToken();
                            JSONObject pdata = new JSONObject();
                            pdata.put("adPositionCode", code);
                            pdata.put("eventType", "award");
                            pdata.put("implId", "csj_ad_" + token);
                            pdata.put("token", token);
                            pdata.put("fishAdCode", fish);
                            pdata.put("gps", gps);
                            body = callAndWait(ctx, "mtop.taobao.idle.play.ad.event.push", "1.0",
                                    pdata.toString(), "push", 6000);
                            rj = parse(body);
                            ok = rj != null && hasSuccess(rj);
                            if (!ok) {
                                pushFail++;
                                sb.append("  ✗ ").append(title).append(" cube:").append(cubeErr)
                                        .append(" | push ").append(code).append(':')
                                        .append(briefRet(body)).append('\n');
                                hardFail = true;
                            }
                        }
                    }
                    if (ok) {
                        pushOk++;
                        doneThis++;
                        sb.append("  ✓ ").append(title).append(" trigger#").append(r + 1)
                                .append(st.length() > 0 ? " → " + st : "").append('\n');
                        /* 多轮任务（看视频 3 轮）的正确节奏是「推一轮→立刻领一轮」：
                           推到 AWARD 后不领奖，状态就卡在 AWARD，下一发 trigger 服务端
                           直接拒绝（2026-11-11 米9 实测：1/3 靠连推 2 发只能到 1/3）。
                           这里用 trigger 响应自带的 awardInfoDTOMap 挑本轮未领档位。 */
                        boolean awardedThisRound = false;
                        if ("AWARD".equals(st)) {
                            JSONObject dto = null;
                            try {
                                dto = rj.getJSONObject("data").getJSONObject("idleTaskDTO");
                            } catch (Throwable ignored) {
                            }
                            String idx = dto == null ? "" : pickAwardIndex(dto);
                            int pts = awardRound(ctx, tidNum, tidStr, idx, title, sb, fish, gps);
                            if (pts >= 0) {
                                okCnt++;
                                gain += pts;
                                awardedThisRound = true;
                            } else {
                                failCnt++;
                            }
                        }
                        // 本轮已领 → 下一轮还能再推；未领且状态已离开 ACCEPTED → 到头收手
                        if (!awardedThisRound && !"ACCEPTED".equals(st)) break;
                    }
                    Thread.sleep(1500); // 防频控
                }
                if (doneThis > 0)
                    sb.append("  · ").append(title).append(" 推 ").append(doneThis)
                            .append('/').append(need).append(" 发\n");
            } catch (Throwable t) {
                pushFail++;
                sb.append("  ! 触发异常: ").append(t).append('\n');
            }
        }
        sb.append("  小计: 触发成功").append(pushOk).append(" 失败").append(pushFail)
                .append(" 跳过").append(pushSkip).append('\n');
        // 触发后重拉一次列表：新变 AWARD 的任务交给下一节领奖
        String show1b = callAndWait(ctx, "mtop.taobao.idle.oliver.show", "2.0", showReq, "show", 6000);
        JSONObject sj1b = parse(show1b);
        if (sj1b != null && hasSuccess(sj1b)) {
            try {
                JSONArray tl1b = sj1b.getJSONObject("data").getJSONArray("taskList");
                tasks = tl1b;
                nAward = 0;
                nAcc = 0;
                nComp = 0;
                for (int i = 0; i < tl1b.length(); i++) {
                    String st = tl1b.getJSONObject(i).optString("idleTaskStatus");
                    if ("AWARD".equals(st)) nAward++;
                    else if ("ACCEPTED".equals(st)) nAcc++;
                    else if ("COMPLETE".equals(st)) nComp++;
                }
                sb.append("  触发后列表: AWARD=").append(nAward).append(" ACCEPTED=")
                        .append(nAcc).append(" COMPLETE=").append(nComp).append('\n');
            } catch (Throwable ignored) {
            }
        }

        /* ---------- 3. AWARD 任务逐个领奖 ---------- */
        if (nAward > 0) sb.append("[3] 领奖:\n");
        for (int i = 0; i < tasks.length(); i++) {
            try {
                JSONObject t = tasks.getJSONObject(i);
                if (!"AWARD".equals(t.optString("idleTaskStatus"))) continue;
                String tid = t.optString("taskInstanceId");
                String title = "";
                try {
                    title = t.getJSONObject("materialDTO").optString("title");
                } catch (Throwable ignored) {
                }
                long tidNum = 0;
                try {
                    tidNum = Long.parseLong(tid);
                } catch (Throwable ignored) {
                }
                // v1.4.1 多轮修正：awardIndexList 从任务自身 awardInfoDTOMap 挑未领取档位（status!=2），
                // 写死 "1" 时多轮任务（看视频 3 轮）第 2/3 轮报 FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD
                String awardIdx = pickAwardIndex(t);
                JSONObject data = new JSONObject();
                data.put("activityId", "104042");
                data.put("awardIndexList", awardIdx);
                data.put("fishAdCode", fish);
                data.put("gps", gps);
                if (tidNum > 0) data.put("taskId", tidNum);
                else data.put("taskId", tid);
                String body = callAndWait(ctx,
                        "mtop.taobao.idle.joy.community.activity.task.award", "1.0",
                        data.toString(), "award", 6000);
                JSONObject rj = parse(body);
                boolean ok = rj != null && hasSuccess(rj);
                if (ok) {
                    okCnt++;
                    int pts = 0;
                    try {
                        pts = Integer.parseInt(rj.getJSONObject("data")
                                .getJSONObject("idleJoyTaskDTO")
                                .getJSONObject("extend").optString("pointNum", "0"));
                    } catch (Throwable ignored) {
                    }
                    gain += pts;
                    sb.append("  ✓ ").append(tid).append(' ').append(title)
                            .append(" +").append(pts).append("g\n");
                } else {
                    failCnt++;
                    sb.append("  ✗ ").append(tid).append(' ').append(title)
                            .append(' ').append(briefRet(body)).append('\n');
                }
                Thread.sleep(1200); // 防频控
            } catch (Throwable t) {
                failCnt++;
                sb.append("  ! 领奖异常: ").append(t).append('\n');
            }
        }
        if (nAward > 0) sb.append("  小计: 成功").append(okCnt).append(" 失败")
                .append(failCnt).append(" 共+").append(gain).append("g\n");

        /* ---------- 4. 能量/签到气泡 ---------- */
        String listBody = callAndWait(ctx,
                "mtop.taobao.idle.forest.energy.list", "1.0",
                "{\"fishAdCode\":\"" + fish + "\",\"gps\":\"" + gps
                        + "\",\"pageNum\":1,\"pageSize\":30,\"startId\":\"\"}",
                "list", 6000);
        JSONObject lj = parse(listBody);
        int bubOk = 0, bubFail = 0;
        JSONArray bubbles = null;
        if (lj != null && hasSuccess(lj)) {
            try {
                bubbles = lj.getJSONObject("data").optJSONArray("data");
            } catch (Throwable ignored) {
            }
        }
        if (bubbles != null && bubbles.length() > 0) {
            sb.append("[4] 能量气泡 ").append(bubbles.length()).append(" 个:\n");
            for (int i = 0; i < bubbles.length(); i++) {
                try {
                    JSONObject b = bubbles.getJSONObject(i);
                    String bid = b.optString("bubbleId", b.optString("id", ""));
                    String scene = b.optString("secondScene", "qiandaonengliang-xy");
                    if (bid.length() == 0) continue;
                    JSONObject data = new JSONObject();
                    data.put("bubbleId", bid);
                    data.put("fishAdCode", fish);
                    data.put("from", "wode");
                    data.put("gps", gps);
                    data.put("secondScene", scene);
                    String body = callAndWait(ctx,
                            "mtop.taobao.idle.forest.energy.issue", "3.0",
                            data.toString(), "issue", 6000);
                    JSONObject rj = parse(body);
                    boolean ok = rj != null && hasSuccess(rj);
                    if (ok) bubOk++;
                    else bubFail++;
                    sb.append("  ").append(ok ? "✓" : "✗").append(' ').append(bid)
                            .append(ok ? "" : " " + briefRet(body)).append('\n');
                    Thread.sleep(1000);
                } catch (Throwable t) {
                    bubFail++;
                }
            }
        } else {
            sb.append("[4] 能量气泡: 无可领 (").append(briefRet(listBody)).append(")\n");
        }

        /* ---------- 5. 复核 ---------- */
        String show2 = callAndWait(ctx, "mtop.taobao.idle.oliver.show", "2.0", showReq, "show", 6000);
        JSONObject sj2 = parse(show2);
        int nAward2 = -1;
        if (sj2 != null && hasSuccess(sj2)) {
            try {
                JSONArray tl2 = sj2.getJSONObject("data").getJSONArray("taskList");
                nAward2 = 0;
                for (int i = 0; i < tl2.length(); i++) {
                    if ("AWARD".equals(tl2.getJSONObject(i).optString("idleTaskStatus")))
                        nAward2++;
                }
            } catch (Throwable ignored) {
            }
        }
        sb.append("[5] 复核: 剩余待领 AWARD=").append(nAward2 < 0 ? "查询失败" : String.valueOf(nAward2)).append('\n');
        finish(ctx, sb, t0);
        return sb.toString();
    }

    private static String now() {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 多轮领奖档位选择（2026-11-10 实测规则）：
     * awardInfoDTOMap 形如 {"1":{awardIndex:"1",status:"2"},...}，status="2"=已领，
     * 可领档位 status 非 "2"。按档位号从小到大挑第一个未领的；map 缺失/全已领 → 兜底 "1"。
     */
    private static String pickAwardIndex(JSONObject task) {
        try {
            JSONObject map = task.optJSONObject("awardInfoDTOMap");
            if (map != null && map.length() > 0) {
                // 档位号是 map 的 key（"1","2","3"...），先排序再挑，保证按轮次顺序领
                java.util.List<int[]> cand = new java.util.ArrayList<>();
                java.util.Iterator<String> it = map.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject dto = map.optJSONObject(k);
                    if (dto == null) continue;
                    int idx;
                    try {
                        idx = Integer.parseInt(dto.optString("awardIndex", k));
                    } catch (Throwable e) {
                        continue;
                    }
                    boolean done = "2".equals(dto.optString("status", "2"));
                    if (!done) cand.add(new int[]{idx});
                }
                if (!cand.isEmpty()) {
                    java.util.Collections.sort(cand, new java.util.Comparator<int[]>() {
                        @Override
                        public int compare(int[] a, int[] b) {
                            return a[0] - b[0];
                        }
                    });
                    return String.valueOf(cand.get(0)[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        return "1";
    }

    /** 领一档奖励（joy.community.activity.task.award）。成功返回获得的饲料克数，失败 -1。 */
    private static int awardRound(Context ctx, long tidNum, String tidStr, String idx,
                                  String title, RhReport sb, String fish, String gps) {
        try {
            JSONObject data = new JSONObject();
            data.put("activityId", "104042");
            data.put("awardIndexList", idx);
            if (fish != null && fish.length() > 0) data.put("fishAdCode", fish);
            if (gps != null && gps.length() > 0) data.put("gps", gps);
            data.put("taskId", tidNum > 0 ? String.valueOf(tidNum) : tidStr);
            String body = callAndWait(ctx,
                    "mtop.taobao.idle.joy.community.activity.task.award", "1.0",
                    data.toString(), "award", 6000);
            JSONObject rj = parse(body);
            if (rj == null || !hasSuccess(rj)) {
                sb.append("      ✗ 领奖#").append(idx).append(' ').append(briefRet(body)).append('\n');
                return -1;
            }
            int pts = 0;
            try {
                pts = Integer.parseInt(rj.getJSONObject("data")
                        .getJSONObject("idleJoyTaskDTO")
                        .getJSONObject("extend").optString("pointNum", "0"));
            } catch (Throwable ignored) {
            }
            sb.append("      ✓ 领奖#").append(idx).append(' ').append(title)
                    .append(" +").append(pts).append("g\n");
            Thread.sleep(1200); // 防频控
            return pts;
        } catch (Throwable t) {
            sb.append("      ! 领奖#").append(idx).append(" 异常: ").append(t).append('\n');
            return -1;
        }
    }

    private static void finish(Context ctx, RhReport sb, long t0) {
        sb.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
        try {
            java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "rh_tasks_pond.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable t) {
            RhFileLog.write("[pond] write report fail: " + t);
        }
        RhFileLog.write(sb.toString());
    }

    /**
     * 进程内直调一个 mtop 接口并等待响应（v1.5.1 重试壳 + raw 监控）。
     *
     * v1.5.1 两轮尝试设计（2026-11-11 日志实证的两种失败形态）：
     *   形态A 静默挂起：请求进构建层但响应零到达（13:10/13:20/15:49 三轮）；
     *   形态B 网关拒绝：响应 8ms 回来但非 JSON，被落 raw 旁档（17:38 len=224）。
     * 单轮失败（null 或 raw）后隔 1.5s 重发一次；两轮都拿到 JSON 直接用，
     * 只有 raw 则返回最后一次 raw（上层 briefRet 展示原文定性拒绝原因）。
     *
     * 判定新响应不能用「lastModified() >= t0」：/sdcard 走 FUSE(sdcardfs)，
     * mtime 只有 1~2 秒精度，会被截断到窗口边界，导致刚落盘的文件被判成旧文件，
     * 白等到超时返回 null —— Android 10 上必现（MIX 3 实测 22:40/22:55 两轮全挂）。
     * 改用两层判据：① 落盘时同写的 .stamp 文件，内容是毫秒字符串，不受 fs 精度影响；
     * ② length:mtime 指纹，与 FishTaskAuto.callApi 同一套逻辑兜底。
     */
    static String callAndWait(Context ctx, String api, String version,
                              String data, String tailKey, long maxWaitMs) {
        String firstRaw = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1500); // 重发间隔：云控判定有概率性，间隔再打一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return firstRaw;
                }
            }
            String r = callAndWaitOnce(ctx, api, version, data, tailKey, maxWaitMs);
            if (r == null) continue;
            String t = r.trim();
            if (t.length() > 0 && (t.charAt(0) == '{' || t.charAt(0) == '[')) {
                return r; // JSON 响应：正常返回
            }
            if (firstRaw == null) firstRaw = r; // 非 JSON：留作兜底，重试一轮
        }
        return firstRaw;
    }

    /** 单轮直调+等待：JSON 命中返回 JSON 原文；raw（非 JSON）命中返回 raw 原文；超时返回 null */
    private static String callAndWaitOnce(Context ctx, String api, String version,
                                          String data, String tailKey, long maxWaitMs) {
        try {
            String tail = tailKey;
            int dot = api.lastIndexOf('.');
            if (dot >= 0) tail = api.substring(dot + 1);
            java.io.File cacheDir = ctx.getExternalCacheDir();
            if (cacheDir == null) cacheDir = ctx.getCacheDir();
            final java.io.File f = new java.io.File(cacheDir, "rh_resp_" + tail + ".json");
            final java.io.File stamp = new java.io.File(cacheDir, "rh_resp_" + tail + ".json.stamp");
            // v1.5.1 raw 旁档：writeTaskResp 丢弃非 JSON body 时同写 rh_resp_<tail>.json.raw + stamp，
            // 命中即返回原文，上层能看到网关拒绝的真实内容而不是「无响应」
            final java.io.File raw = new java.io.File(cacheDir, "rh_resp_" + tail + ".json.raw");
            final java.io.File rawStamp = new java.io.File(cacheDir, "rh_resp_" + tail + ".json.raw.stamp");
            long t0 = System.currentTimeMillis();
            String fp0 = fp(f);                       // 发之前拍指纹快照
            String rawFp0 = fp(raw);
            boolean sent = ApiDirect.send(ctx.getClassLoader(), api, version, data);
            if (!sent) return null;
            long deadline = t0 + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                if (stampNewer(stamp, t0) || fpChanged(f, fp0)) {
                    Thread.sleep(60);                  // 让 FileWriter 把尾部刷完再读
                    byte[] b = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    in.read(b);
                    in.close();
                    return new String(b, "UTF-8");
                }
                if (stampNewer(rawStamp, t0) || fpChanged(raw, rawFp0)) {
                    Thread.sleep(60);
                    byte[] b = new byte[(int) raw.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(raw);
                    in.read(b);
                    in.close();
                    return new String(b, "UTF-8");
                }
            }
            RhFileLog.write("[pond] callAndWait timeout " + api + " fp0=" + fp0 + " fp=" + fp(f));
            return null;
        } catch (Throwable t) {
            RhFileLog.write("[pond] callAndWait " + api + " err: " + t);
            return null;
        }
    }

    /** 文件指纹：长度 + mtime。文件不存在/空 → null */
    private static String fp(java.io.File f) {
        try {
            if (!f.exists() || f.length() == 0) return null;
            return f.length() + ":" + f.lastModified();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean fpChanged(java.io.File f, String fp0) {
        String fp = fp(f);
        return fp != null && !fp.equals(fp0);
    }

    /** .stamp 里的毫秒值 >= 请求发起时刻 → 这是本轮响应 */
    private static boolean stampNewer(java.io.File stamp, long t0) {
        try {
            if (!stamp.exists() || stamp.length() == 0) return false;
            java.io.FileInputStream in = new java.io.FileInputStream(stamp);
            byte[] b = new byte[(int) stamp.length()];
            in.read(b);
            in.close();
            return Long.parseLong(new String(b, "UTF-8").trim()) >= t0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONObject parse(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasSuccess(JSONObject j) {
        try {
            JSONArray ret = j.getJSONArray("ret");
            for (int i = 0; i < ret.length(); i++) {
                if (ret.getString(i).contains("SUCCESS")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String briefRet(String body) {
        if (body == null) return "无响应";
        String t = body.trim();
        if (t.length() == 0) return "空响应";
        if (t.charAt(0) == '{' || t.charAt(0) == '[') {
            JSONObject j = parse(body);
            if (j == null) return "响应异常";
            try {
                return j.getJSONArray("ret").getString(0);
            } catch (Throwable e) {
                return "ret解析失败";
            }
        }
        // v1.5.1 非 JSON：展示原文前 100 字符——网关拒绝原因（风控/签名/参数）可直接定性
        String one = t.replaceAll("\\s+", " ");
        return "非JSON响应: " + (one.length() > 100 ? one.substring(0, 100) + "..." : one);
    }
}
