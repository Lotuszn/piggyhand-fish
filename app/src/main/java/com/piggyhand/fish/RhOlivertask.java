package com.piggyhand.fish;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.1.9 会员任务引擎（RhOlivertask）：
 * 「我的 → 闲鱼会员 → 做任务涨经验值」四条任务的纯协议实现。
 *
 * 链路（2026-10-25 实测，activityId=102178）：
 *   1. mtop.taobao.idle.oliver.show                     v2.0  拉任务列表 + activityDO.asac
 *   2. mtop.taobao.idle.cube.task.trigger               v1.0  上报完成（ACCEPTED → AWARD）
 *      {"activityId":"102178","idempotentKey":<毫秒戳>,"taskInstanceId":<id>}
 *   3. mtop.taobao.idle.joy.community.activity.task.award v1.0 领经验（AWARD → COMPLETE）
 *      {"activityId":"102178","asac":"<show下发>","awardIndexList":"1","taskId":<同 taskInstanceId>}
 *
 * 服务端不校验 visit_task / click_task 的真实访问行为，trigger 只认 activityId + taskInstanceId，
 * 因此四个任务可全自动拿下（实测 +900 经验 = 300+200+200+200）。
 * 每日 0 点重置（NATURAL_DAY / refreshCycleType=3）。
 *
 * 幂等：重复 trigger 返回 SUCCESS 但不改状态；重复 award 返回
 * FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD（服务端拦重复领取）。
 */
public final class RhOlivertask {

    /** 闲鱼会员经验值活动 ID（2026-10-25 抓包值；活动换 ID 时改这里） */
    public static final String ACTIVITY_ID = "102178";

    /* ---------- 每日 500 经验（会员页顶部气泡） ----------
     * 与下面 4 个任务是**两套体系**：H5 点气泡实测落到的接口是
     *   mtop.idle.user.level.exp.fetch v1.0  body = {"expId":1,"expType":2}
     * 领完气泡文案变「明天来」，通关进度 +500（实测 133850 → 134350）。
     * 它走 user.level，不吃 oliver 的 activityId/asac —— 所以 4 个任务全 COMPLETE、
     * isDoneToday=true 的时候，这一项**照样还能领**，闸门必须独立，不能挂在任务链里。
     */
    static final String API_DAILY_EXP = "mtop.idle.user.level.exp.fetch";
    static final String DAILY_EXP_BODY = "{\"expId\":1,\"expType\":2}";
    /** 每日经验领取标记（毫秒失效时刻），与 DONE_FILE 分开存 */
    static final String EXP_FILE = "rh_oliver_exp.txt";

    private RhOlivertask() {}

    /**
     * 打开闲鱼自动跑一次：开关 oliverTask=true 且今日未完成时延迟 45s 触发。
     * 与鱼塘任务错开 5s，避免两套引擎同时抢 mtop 通道。
     *
     * 判定只看「完成标记」（isDoneToday），不再看 rh_oliver_date.txt ——
     * 旧的日期标记是在线程里无条件写的，请求失败也会把当天记成已跑，
     * 结果 date 挡着不进引擎、done 又永远写不出来，当天彻底死锁（MIX 3 实测）。
     */
    public static void autoRunDaily(final Context ctx) {
        try {
            // 仅主进程（分身/插件进程不参与）
            String proc = null;
            try {
                proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return;

            boolean on;
            try {
                ConfigManager cfg = ConfigManager.load();
                on = cfg == null || cfg.oliverTask;
            } catch (Throwable t) {
                on = true; // 配置读失败按默认开
            }
            if (!on) {
                RhFileLog.write("[oliver] auto-run disabled by config (oliverTask=false), skip");
                return;
            }

            // 已完成标记还在有效期内：不用等 45s 白跑一趟
            if (isDoneToday(ctx)) {
                RhFileLog.write("[oliver] auto-run skip: already done today");
                return;
            }

            // 熔断：同一天自动尝试 N 次还没成就不再自动跑，避免每进一次 App 打一轮接口
            final int tries = bumpTry(ctx);
            if (tries > MAX_AUTO_TRY) {
                RhFileLog.write("[oliver] auto-run give up today, tries=" + tries
                        + " (手动点击仍可执行)");
                return;
            }

            RhFileLog.write("[oliver] auto-run scheduled in 45s (try " + tries + "/" + MAX_AUTO_TRY + ")");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(45000);
                        String rep = RhOlivertask.run(ctx);
                        RhFileLog.write("[oliver] auto-run done(try " + tries + "): "
                                + (rep == null ? "null" : rep.replace('\n', ' ')));
                        // 只有真跑完（done 标记落了）才算数；没完成就留给下次进 App 重试，
                        // 不写任何「今日已跑」的记账文件 —— 那正是之前死锁的来源
                        if (isDoneToday(ctx)) {
                            RhFileLog.write("[oliver] auto-run completed, done mark valid");
                        } else {
                            RhFileLog.write("[oliver] auto-run incomplete, will retry on next launch");
                        }
                    } catch (Throwable t) {
                        RhFileLog.write("[oliver] auto-run fail: " + t);
                    }
                }
            }, "rh-oliver-auto").start();
        } catch (Throwable t) {
            RhFileLog.write("[oliver] autoRunDaily err: " + t);
        }
    }

    /** 自动尝试计数字（格式：yyyyMMdd 次数），跨天自动归零 */
    private static final String TRY_FILE = "rh_oliver_try.txt";
    /** 同一天最多自动尝试次数，超过转人工 */
    private static final int MAX_AUTO_TRY = 3;

    private static int bumpTry(Context ctx) {
        java.io.File f = new java.io.File(rhDir(ctx), TRY_FILE);
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        int n = 0;
        try {
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
                int sp = s.indexOf(' ');
                // 只有同一日期才沿用计数，跨天从 0 起
                if (sp > 0 && today.equals(s.substring(0, sp))) {
                    n = Integer.parseInt(s.substring(sp + 1).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        n++;
        try {
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(today + " " + n);
            w.close();
        } catch (Throwable t) {
            RhFileLog.write("[oliver] bumpTry write fail: " + t);
        }
        return n;
    }

    /** 运行时状态目录：优先 filesDir，取不到退回硬编码路径 */
    private static java.io.File rhDir(Context ctx) {
        java.io.File dir = null;
        try {
            dir = ctx.getFilesDir();
        } catch (Throwable ignored) {
        }
        return dir != null ? dir : new java.io.File("/data/data/com.taobao.idlefish/files");
    }

    /**
     * 每日经验自动领取入口：与 autoRunDaily 平行，**不受 4 个任务完成状态影响**。
     * 延迟 20s（任务链 45s 才启动，中间留空档，避免两路同时抢 mtop 通道）。
     */
    public static void autoRunDailyExp(final Context ctx) {
        try {
            // 仅主进程
            String proc = null;
            try {
                proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
            } catch (Throwable ignored) {
            }
            if (proc != null && proc.contains(":")) return;

            // 开关沿用 oliverTask（同一个「会员任务」功能，不额外加 UI）
            boolean on = true;
            try {
                ConfigManager cfg = ConfigManager.load();
                if (cfg != null) on = cfg.oliverTask;
            } catch (Throwable ignored) {
            }
            if (!on) {
                RhFileLog.write("[oliver-exp] disabled by config (oliverTask=false), skip");
                return;
            }
            if (isExpDoneToday(ctx)) {
                RhFileLog.write("[oliver-exp] skip: already claimed today");
                return;
            }

            RhFileLog.write("[oliver-exp] scheduled in 20s");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(20000);
                        RhFileLog.write("[oliver-exp] " + claimDailyExp(ctx));
                    } catch (Throwable t) {
                        RhFileLog.write("[oliver-exp] fail: " + t);
                    }
                }
            }, "rh-oliver-exp").start();
        } catch (Throwable t) {
            RhFileLog.write("[oliver-exp] autoRunDailyExp err: " + t);
        }
    }

    /**
     * 真领一次，返回一行结果文本（写进运行日志 + 报告）。
     *
     * 回包现状（2026-10-26 实测）：这条链的回包不走 mtopsdk 的 MtopFinishEvent，
     * 我们的响应捕获（FishMtopHook 的 finish 事件 hook）管不到，callAndWait 大概率拿 null。
     * 所以按「已发出即记账」处理 —— 接口本身幂等，已领再打只会返回已领错误，一天一次不会重复加经验。
     * 万一哪天拿到回包且 ret 不含 SUCCESS，则不落标记，下次进 App 再试。
     */
    static String claimDailyExp(Context ctx) {
        String body;
        try {
            body = RhPondTask.callAndWait(ctx, API_DAILY_EXP, "1.0", DAILY_EXP_BODY, "expfetch", 3500);
        } catch (Throwable t) {
            return "callAndWait 异常，不记账：" + t;
        }
        if (body != null && body.length() > 0) {
            if (body.indexOf("SUCCESS") < 0) {
                return "回包未成功，不记账，下次再试 -> " + head(body, 180);
            }
            markExpDone(ctx);
            return "领取成功 +500 经验 -> " + head(body, 180);
        }
        // 无回包：请求已由 direct 通道发出（日志 [direct] sent 可查），按已领记账
        markExpDone(ctx);
        return "已发送 " + API_DAILY_EXP + " " + DAILY_EXP_BODY
                + "（该接口回包不在捕获通道内，按已领取记账）";
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static java.io.File expFile(Context ctx) {
        return new java.io.File(rhDir(ctx), EXP_FILE);
    }

    /** 每日经验今日是否已领（标记过期或读坏一律当未领，最多多打一次幂等接口） */
    static boolean isExpDoneToday(Context ctx) {
        try {
            java.io.File f = expFile(ctx);
            if (!f.exists() || f.length() == 0) return false;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = br.readLine();
            br.close();
            if (line == null) return false;
            long until = Long.parseLong(line.trim());
            return System.currentTimeMillis() < until;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markExpDone(Context ctx) {
        try {
            java.io.File f = expFile(ctx);
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(String.valueOf(nextMidnight()));
            w.close();
        } catch (Throwable t) {
            RhFileLog.write("[oliver-exp] mark write fail: " + t);
        }
    }

    /**
     * 并发闸门：oliver.show 被并发调用时服务端会回 208B 非 JSON（限流页），
     * 一次连点就能把整轮跑废。同一时刻只放行一轮。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 完成标记文件名：内容为「失效时刻」毫秒戳（次日 0 点），过点即自动失效 */
    private static final String DONE_FILE = "rh_oliver_done.txt";

    private static java.io.File doneFile(Context ctx) {
        return new java.io.File(rhDir(ctx), DONE_FILE);
    }

    /** 次日 0 点的毫秒戳：这就是「今晚 12 点」这条线的精确位置 */
    private static long nextMidnight() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 今日是否已全部完成。标记读坏/读不到一律当「未完成」——
     * 宁可多跑一次（服务端会拦重复领取），也不能把没做完的当成做完了。
     */
    public static boolean isDoneToday(Context ctx) {
        try {
            java.io.File f = doneFile(ctx);
            if (!f.exists()) return false;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            long deadline = Long.parseLong(s);
            return System.currentTimeMillis() < deadline;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打完成标记：先写 .tmp 再 rename，避免写一半掉电留下坏文件 */
    private static void markDoneToday(Context ctx) {
        try {
            long deadline = nextMidnight();
            java.io.File f = doneFile(ctx);
            java.io.File tmp = new java.io.File(f.getParentFile(), DONE_FILE + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp, false);
            w.write(String.valueOf(deadline));
            w.flush();
            w.close();
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) {
                java.io.FileWriter w2 = new java.io.FileWriter(f, false);
                w2.write(String.valueOf(deadline));
                w2.flush();
                w2.close();
            }
            RhFileLog.write("[oliver] DONE mark set, valid until "
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.CHINA).format(new java.util.Date(deadline)));
        } catch (Throwable t) {
            RhFileLog.write("[oliver] markDone fail: " + t);
        }
    }

    /** 供 OLIVER_STATE 指令查询：DONE <失效毫秒> / TODO */
    public static String stateText(Context ctx) {
        try {
            java.io.File f = doneFile(ctx);
            if (isDoneToday(ctx)) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
                return "DONE " + s;
            }
            return "TODO";
        } catch (Throwable t) {
            return "TODO";
        }
    }

    /** 后台线程调用；返回报告全文（同时写 rh_tasks_oliver.txt） */
    public static String run(Context ctx) {
        if (!RUNNING.compareAndSet(false, true)) {
            RhFileLog.write("[oliver] skipped: previous run still in progress");
            return "🏅 会员任务报告 " + now()
                    + "\n[!] 上一轮还在执行，本次跳过（避免并发触发接口限流）\n";
        }
        try {
            return runInner(ctx);
        } finally {
            RUNNING.set(false);
        }
    }

    /** 引擎主体：串行执行，外部不得并发调用 */
    private static String runInner(Context ctx) {
        long t0 = System.currentTimeMillis();
        RhReport sb = new RhReport();
        sb.append("🏅 会员任务报告 ").append(now()).append('\n');

        // 每日经验与 4 个任务是两套体系：任务全做完也要领，所以必须放在 isDoneToday 闸门之前
        try {
            if (!isExpDoneToday(ctx)) {
                sb.append("[0] 每日经验 ").append(claimDailyExp(ctx)).append('\n');
            }
        } catch (Throwable t) {
            sb.append("[0] 每日经验异常：").append(t).append('\n');
        }

        // 进引擎前的第一道闸：本地已完成标记还在有效期内，连 show 都不用打
        if (isDoneToday(ctx)) {
            sb.append("[0] 今日任务已完成，跳过（次日 0 点重置）\n");
            RhFileLog.write("[oliver] run skipped: done mark still valid");
            finish(ctx, sb, t0);
            return sb.toString();
        }

        /* ---------- 1. 任务列表 + asac ---------- */
        JSONObject showJson = parse(show(ctx));
        JSONArray tasks = null;
        String asac = "";
        int nAward = 0, nAcc = 0, nComp = 0;
        if (showJson != null) {
            try {
                JSONObject data = showJson.getJSONObject("data");
                tasks = data.optJSONArray("taskList");
                JSONObject act = data.optJSONObject("activityDO");
                if (act != null) asac = act.optString("asac", "");
                if (tasks != null) {
                    for (int i = 0; i < tasks.length(); i++) {
                        String st = tasks.getJSONObject(i).optString("idleTaskStatus");
                        if ("AWARD".equals(st)) nAward++;
                        else if ("ACCEPTED".equals(st)) nAcc++;
                        else if ("COMPLETE".equals(st)) nComp++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (tasks == null) {
            sb.append("[1] 任务列表拉取失败（响应非 JSON / 活动未下发；并发或限流时常见，隔 1 分钟再点一次）\n");
            finish(ctx, sb, t0);
            return sb.toString();
        }
        sb.append("[1] 任务 ").append(tasks.length()).append(" 个（未完成=").append(nAcc)
                .append(" 待领=").append(nAward).append(" 已完成=").append(nComp)
                .append("）asac=").append(asac.length() > 0 ? asac : "(缺失)").append('\n');

        // 服务端口径已经是「全部 COMPLETE」：今天没活可干，直接落标记，后面 12 点前都不再进来
        if (tasks.length() > 0 && nComp == tasks.length()) {
            markDoneToday(ctx);
            sb.append("[1] 服务端显示全部完成 → 打完成标记，今晚 12 点前不再执行\n");
            finish(ctx, sb, t0);
            return sb.toString();
        }

        if (asac.length() == 0) {
            sb.append("  ! 没取到 asac，领奖会失败，本轮终止\n");
            finish(ctx, sb, t0);
            return sb.toString();
        }

        /* ---------- 2. 逐个任务：trigger（如需）→ award ---------- */
        int okTrigger = 0, failTrigger = 0, okAward = 0, failAward = 0, expGain = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t;
            try {
                t = tasks.getJSONObject(i);
            } catch (Throwable e) {
                continue;
            }
            String st = t.optString("idleTaskStatus");
            if ("COMPLETE".equals(st)) continue;

            String tid = t.optString("taskInstanceId");
            String title = "";
            int exp = 0;
            try {
                title = t.getJSONObject("materialDTO").optString("title", "");
            } catch (Throwable ignored) {
            }
            try {
                exp = Integer.parseInt(t.getJSONObject("extend").optString("expNumber", "0"));
            } catch (Throwable ignored) {
            }
            sb.append("[2] ").append(tid).append(' ').append(title)
                    .append(" (+").append(exp).append(" 经验) ").append(st).append('\n');

            /* 2a. ACCEPTED → trigger 上报 */
            if ("ACCEPTED".equals(st)) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("activityId", ACTIVITY_ID);
                    // idempotentKey：客户端毫秒戳，服务端不校验；按任务序号 +i 保证互不相同
                    d.put("idempotentKey", System.currentTimeMillis() + i);
                    d.put("taskInstanceId", Long.parseLong(tid));
                    JSONObject rj = parse(RhPondTask.callAndWait(ctx,
                            "mtop.taobao.idle.cube.task.trigger", "1.0",
                            d.toString(), "trigger", 6000));
                    boolean ok = rj != null && hasSuccess(rj);
                    if (ok) {
                        okTrigger++;
                        sb.append("     ✓ 上报完成 → AWARD\n");
                    } else {
                        failTrigger++;
                        sb.append("     ✗ 上报失败 ").append(briefRet(rj)).append('\n');
                        sleep(1200);
                        continue; // 没到 AWARD 就不领奖
                    }
                } catch (Throwable e) {
                    failTrigger++;
                    sb.append("     ! 上报异常 ").append(e).append('\n');
                    continue;
                }
                sleep(900);
            }

            /* 2b. AWARD → award 领经验 */
            try {
                JSONObject d = new JSONObject();
                d.put("activityId", ACTIVITY_ID);
                d.put("asac", asac);
                d.put("awardIndexList", "1"); // 字符串 "1"，实测数字会被判非法
                d.put("taskId", Long.parseLong(tid));
                JSONObject rj = parse(RhPondTask.callAndWait(ctx,
                        "mtop.taobao.idle.joy.community.activity.task.award", "1.0",
                        d.toString(), "award", 6000));
                boolean ok = rj != null && hasSuccess(rj)
                        && rj.optJSONObject("data") != null
                        && rj.optJSONObject("data").optBoolean("success", false);
                if (ok) {
                    okAward++;
                    int got = exp;
                    try {
                        got = Integer.parseInt(rj.getJSONObject("data")
                                .getJSONObject("idleJoyTaskDTO")
                                .getJSONObject("awardInfoDTOMap")
                                .getJSONObject("1")
                                .getJSONObject("extend").optString("expNumber", String.valueOf(exp)));
                    } catch (Throwable ignored) {
                    }
                    expGain += got;
                    sb.append("     ✓ 领取成功 +").append(got).append(" 经验\n");
                } else {
                    failAward++;
                    sb.append("     ✗ 领取失败 ").append(briefRet(rj)).append('\n');
                }
            } catch (Throwable e) {
                failAward++;
                sb.append("     ! 领取异常 ").append(e).append('\n');
            }
            sleep(1000); // 防频控
        }
        sb.append("[2] 小计: 上报 成功").append(okTrigger).append("/失败").append(failTrigger)
                .append(" · 领取 成功").append(okAward).append("/失败").append(failAward)
                .append(" · 共 +").append(expGain).append(" 经验\n");

        /* ---------- 3. 复核：以服务端为准，全 COMPLETE 才落标记 ---------- */
        JSONObject sj2 = parse(show(ctx));
        int leftCount = -1;   // -1 = 复核没查成，这种就不能打标记
        if (sj2 != null) {
            try {
                JSONArray tl2 = sj2.getJSONObject("data").getJSONArray("taskList");
                int c = 0;
                for (int i = 0; i < tl2.length(); i++) {
                    if (!"COMPLETE".equals(tl2.getJSONObject(i).optString("idleTaskStatus"))) c++;
                }
                leftCount = c;
            } catch (Throwable ignored) {
            }
        }
        if (leftCount == 0) {
            markDoneToday(ctx);
            sb.append("[3] 复核: 0 个未完成 → 打完成标记，今晚 12 点前不再执行\n");
        } else if (leftCount > 0) {
            sb.append("[3] 复核: ").append(leftCount).append(" 个未完成（每日 0 点重置，可再点一次重试）\n");
        } else {
            sb.append("[3] 复核: 查询失败，未打标记（下次仍会重试）\n");
        }
        finish(ctx, sb, t0);
        return sb.toString();
    }

    /* ---------------- 内部 ---------------- */

    /** 节流休眠：吞掉 InterruptedException（引擎线程不参与中断语义） */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String show(Context ctx) {
        try {
            JSONObject d = new JSONObject();
            d.put("activityId", ACTIVITY_ID);
            d.put("extend", new JSONObject());
            d.put("needBenefits", true);
            d.put("needDetails", false);
            d.put("needHadWin", false);
            d.put("showEmptyInventory", false);
            d.put("withInventoryDetail", false);
            String body = RhPondTask.callAndWait(ctx, "mtop.taobao.idle.oliver.show", "2.0",
                    d.toString(), "show", 6000);
            // 非 JSON 时留前 160 字符：限流/风控页只在这行能看出来
            if (body != null && body.length() > 0 && parse(body) == null) {
                RhFileLog.write("[oliver] show non-json(" + body.length() + "): "
                        + body.substring(0, Math.min(160, body.length())));
            }
            return body;
        } catch (Throwable t) {
            RhFileLog.write("[oliver] show err: " + t);
            return null;
        }
    }

    private static String now() {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }

    private static void finish(Context ctx, RhReport sb, long t0) {
        sb.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
        try {
            java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "rh_tasks_oliver.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable t) {
            RhFileLog.write("[oliver] write report fail: " + t);
        }
        RhFileLog.write(sb.toString());
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

    private static String briefRet(JSONObject j) {
        if (j == null) return "无响应";
        try {
            return j.getJSONArray("ret").getString(0);
        } catch (Throwable t) {
            return "ret解析失败";
        }
    }
}
