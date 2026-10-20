package com.piggyhand.fish;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.0.9 鱼塘任务引擎（RhPondTask）：
 * 进程内直调（复用 ApiDirect 通道 + 全局 MtopFinishEvent 落盘），
 * 全自动完成「赚饲料」可纯接口部分：
 *   1. oliver.show 拉任务列表（activityId=104042，taskInstanceId 即领奖 taskId）
 *   2. 过滤 idleTaskStatus==AWARD 的任务逐个 joy.community.activity.task.award 领取
 *   3. forest.energy.list 拉能量/签到气泡 → 逐个 forest.energy.issue 领取
 *   4. 复核状态，输出报告（rh_tasks_pond.txt + 返回值）
 *
 * 行为类任务（浏览/视频/搜索等）服务端有行为校验，纯接口不可行（2026-10-19 实测），
 * 用户手动/页面完成后变 AWARD，本引擎下次运行自动收割。
 */
public final class RhPondTask {

    private RhPondTask() {}

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

            // 40s 等 mtop 通道热 + 闲鱼初始化完成
            RhFileLog.write("[pond] auto-run scheduled in 40s");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(40000);
                        RhPondTask.run(ctx);
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

    /** 后台线程调用；返回报告全文（同时写 rh_tasks_pond.txt） */
    public static String run(Context ctx) {
        long t0 = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
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

        /* ---------- 1. 任务列表 ---------- */
        String showBody = callAndWait(ctx,
                "mtop.taobao.idle.oliver.show", "2.0",
                "{\"activityId\":\"104042\",\"extend\":{}," + RhLocationContext.jsonPair()
                        + "\"needBenefits\":true,\"needDetails\":true,\"needHadWin\":true,"
                        + "\"showEmptyInventory\":false,\"withInventoryDetail\":false}",
                "show", 6000);
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

        /* ---------- 2. AWARD 任务逐个领奖 ---------- */
        int gain = 0, okCnt = 0, failCnt = 0;
        if (nAward > 0) sb.append("[2] 领奖:\n");
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
                JSONObject data = new JSONObject();
                data.put("activityId", "104042");
                data.put("awardIndexList", "1");
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

        /* ---------- 3. 能量/签到气泡 ---------- */
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
            sb.append("[3] 能量气泡 ").append(bubbles.length()).append(" 个:\n");
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
            sb.append("[3] 能量气泡: 无可领 (").append(briefRet(listBody)).append(")\n");
        }

        /* ---------- 4. 复核 ---------- */
        String show2 = callAndWait(ctx,
                "mtop.taobao.idle.oliver.show", "2.0",
                "{\"activityId\":\"104042\",\"extend\":{}," + RhLocationContext.jsonPair()
                        + "\"needBenefits\":true,\"needDetails\":true,\"needHadWin\":true,"
                        + "\"showEmptyInventory\":false,\"withInventoryDetail\":false}",
                "show", 6000);
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
        sb.append("[4] 复核: 剩余待领 AWARD=").append(nAward2 < 0 ? "查询失败" : String.valueOf(nAward2)).append('\n');
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

    private static void finish(Context ctx, StringBuilder sb, long t0) {
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

    /** 进程内直调一个 mtop 接口并等待响应落盘（复用全局 MtopFinishEvent hook 的 rh_resp_<尾段>.json） */
    static String callAndWait(Context ctx, String api, String version,
                              String data, String tailKey, long maxWaitMs) {
        try {
            String tail = tailKey;
            int dot = api.lastIndexOf('.');
            if (dot >= 0) tail = api.substring(dot + 1);
            final java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "rh_resp_" + tail + ".json");
            long t0 = System.currentTimeMillis();
            boolean sent = ApiDirect.send(ctx.getClassLoader(), api, version, data);
            if (!sent) return null;
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
                if (f.exists() && f.lastModified() >= t0) {
                    byte[] b = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    in.read(b);
                    in.close();
                    return new String(b, "UTF-8");
                }
            }
            return null;
        } catch (Throwable t) {
            RhFileLog.write("[pond] callAndWait " + api + " err: " + t);
            return null;
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
        JSONObject j = parse(body);
        if (j == null) return body == null ? "无响应" : "响应异常";
        try {
            return j.getJSONArray("ret").getString(0);
        } catch (Throwable t) {
            return "ret解析失败";
        }
    }
}
