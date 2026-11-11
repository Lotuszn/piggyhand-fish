package com.piggyhand.fish;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.4.6 「赚闲鱼币提现 → 赚游戏币任务」引擎 —— 2026-11-11 米9 全链实测跑通的功能收编：
 *
 *  [0] 余额：mtop.taobao.idle.mini.game.point.withdraw.query {fishAdCode,gps}
 *  [1] 中央游戏任务：直接复用 RhGameTask.run（center→detail→complete→award，自带当日完成标记），
 *      报告只摘 小计/复核 三行，全文仍落 rh_tasks_game.txt
 *  [2] 渠道任务（GAME_CHANNEL 家族）：
 *        channel.query → AWARD→task.award{taskId,taskScene:GAME_CHANNEL}
 *                      → ACCEPTED→channel.complete{taskId,reportId}
 *                                失败→revisit.complete{reportId,sourceId}
 *                                再失败且带 jumpUrl→拉起任务页
 *        CSJ 浏览类（jumpUrl 含 showAds）协议打不动：play.ad.event.push 由服务端按
 *        fmfa_csj_mall_session_key 会话对账，伪造 token/implId 必 FAIL_BIZ_SERVER_ERROR
 *        （_r2_logcat.txt 实证）；必须真实浏览 ≥20s，CSJ SDK 在 RESUMED 会话内自行上报 award，
 *        所以这里只负责拉起页面，BROWSE_RESCAN_DELAY 后自动复扫把 AWARD 收掉
 *  [3] 停止条件（沿用之前的标记机制，本次按用户口径落到 0/1/2 三档）：
 *        复核渠道 0 待收口 → 完成标记 0；复扫1 仍 0 → 1；复扫2 仍 0 → 2；
 *        标识=2 当日自动跑跳过（手动点行不受限），次日 0 点 mark 归零自动恢复
 */
public final class RhCoinTask {
   static final String API_CH_QUERY = "mtop.taobao.idle.mini.game.task.channel.query";
   /* v1.5.2-fix：渠道任务上报走通用 complete（channel.complete 这个接口在服务端根本不存在，
    * 2026-11-11 抓包实证 59 次成功调用全是 mtop.taobao.idle.mini.game.task.complete +
    * progressValue/action+payload + 客户端自生成 UUID reportId） */
   static final String API_CH_COMPLETE = "mtop.taobao.idle.mini.game.task.complete";
   /* v1.5.2-fix2：complete 前必须先打 detail.query，否则服务端认不出这个 taskId，
    * 直接回 TASK_NOT_FOUND::任务不存在或已失效（2026-11-11 实测：
    *   只发 complete           → TASK_NOT_FOUND
    *   detail.query 不带 scene  → TASK_NOT_FOUND
    *   detail.query 带 taskScene=GAME_CHANNEL + appkey → SUCCESS/ACCEPTED
    *   随后 complete 同参数     → SUCCESS/completed=true/taskStatus=AWARD
    *   再 award 同参数          → awarded=true/rewardAmount=300
    * ）所以 detail.query 不是只读探针，它同时是"本会话登记这个任务"的开关。 */
   static final String API_CH_DETAIL = "mtop.taobao.idle.mini.game.task.detail.query";
   static final String API_CH_REVISIT = "mtop.taobao.idle.mini.game.task.channel.revisit.complete";
   static final String API_CH_AWARD = "mtop.taobao.idle.mini.game.task.award";
   /* 逛一逛（ecMall 广告位）三段式：查询发 token → 预检 → 推送 */
   static final String API_AD_QUERY = "mtop.taobao.idle.play.ad.query";
   static final String API_AD_EVT_Q = "mtop.taobao.idle.play.ad.event.query";
   static final String API_AD_EVT_P = "mtop.taobao.idle.play.ad.event.push";
   /* 游戏中心「点击签到」（连续签到阶梯，+50/80/100…游戏币） */
   static final String API_GAME_SIGN = "mtop.taobao.idle.mini.game.sign.in";
   static final String API_GAME_SIGN_Q = "mtop.taobao.idle.mini.game.sign.in.query";
   static final String API_SIGN_HOME = "mtop.taobao.idle.play.sign.in.home";
   static final String API_SIGN_DO = "mtop.taobao.idle.play.sign.in";
   static final String API_POINT = "mtop.taobao.idle.mini.game.point.withdraw.query";
   static final String REPORT_NAME = "rh_tasks_coin.txt";
   static final String MARK_FILE = "rh_coin_mark.txt";
   static final String TRY_FILE = "rh_coin_try.txt";
   static final int MAX_AUTO_TRY = 3;
   /** 浏览类任务拉起后，自动复扫收 AWARD 的延迟（浏览 20s + 页面加载 + 余量） */
   static final long BROWSE_RESCAN_DELAY = 100000L;
   /** 单轮最多自动拉起的任务页数，防止 activity 栈被连开炸掉 */
   static final int MAX_OPEN_PER_RUN = 2;
   /** 自动跑延迟：避开 40~50s 那波鱼塘/会员/游戏引擎，防止 center.query 共用响应文件串包 */
   static final long AUTO_DELAY = 240000L;
   /** v1.4.9 标识2后自动兑换触发延迟：attach 期 mtop/session 未就绪（实测启动 3s 内必"无响应"），60s 后再查 */
   static final long EXCH_DELAY = 60000L;

   private RhCoinTask() {
   }

   /* ================= 自动调度 ================= */

   public static void autoRunDaily(final Context ctx) {
      try {
         String proc = null;
         try {
            proc = (String) android.app.Application.class.getMethod("getProcessName").invoke(null);
         } catch (Throwable t) {
         }
         if (proc != null && proc.contains(":")) {
            return; // 只在主进程跑，:push 等子进程不碰
         }

         boolean on;
         try {
            ConfigManager c = ConfigManager.load();
            on = c == null || c.coinGame;
         } catch (Throwable t) {
            on = true;
         }
         if (!on) {
            RhFileLog.write("[coin] auto-run disabled by config (coinGame=false), skip");
            return;
         }

         if (markOf(ctx) >= 2) {
            RhFileLog.write("[coin] auto-run skip: 标识=2（今日已判定完成 3 次）");
            // v1.4.9 标识 2 之后的另一触发点：覆盖"10 点后才启动闲鱼"的场景。
            // 不能在 attach 线程同步跑——mtop 未就绪余额查询必"无响应"，白耗每日名额；
            // 延迟 60s 等 mtop/session 就绪（run() [3.5] 挂点在引擎尾部，mtop 已验证可用，保持同步）
            final Context xc = ctx;
            new Thread(new Runnable() {
               @Override
               public void run() {
                  try {
                     Thread.sleep(EXCH_DELAY);
                     RhCoinExchange.onCoinDone(xc);
                  } catch (Throwable t) {
                     RhFileLog.write("[coin] exch trigger fail: " + t);
                  }
               }
            }, "rh-coinex-auto").start();
            return;
         }

         final int tryN = bumpDayCount(ctx, TRY_FILE);
         if (tryN > MAX_AUTO_TRY) {
            RhFileLog.write("[coin] auto-run give up today, tries=" + tryN + " (手动点行仍可执行)");
            return;
         }

         RhFileLog.write("[coin] auto-run scheduled in " + AUTO_DELAY / 1000L + "s (try " + tryN + "/3, 标识=" + markOf(ctx) + ")");
         new Thread(new Runnable() {
            @Override
            public void run() {
               try {
                  Thread.sleep(AUTO_DELAY);
                  String r = RhCoinTask.run(ctx);
                  RhFileLog.write("[coin] auto-run done(try " + tryN + "): " + (r == null ? "null" : r.replace('\n', ' ')));
               } catch (Throwable t) {
                  RhFileLog.write("[coin] auto-run fail: " + t);
               }
            }
         }, "rh-coin-auto").start();
      } catch (Throwable t) {
         RhFileLog.write("[coin] autoRunDaily err: " + t);
      }
   }

   /* ================= 主流程 ================= */

   public static String run(Context ctx) {
      long t0 = System.currentTimeMillis();
      RhReport rep = new RhReport();
      rep.append("\ud83e\ude99 游戏币任务报告 @").append(t0).append('\n');
      rep.append("[*] 标识=").append(markOf(ctx)).append("（0=待跑 1=复扫1 2=复扫2/停止）\n");

      int earned = 0;
      int opened = 0;

      /* ---------- [0] 余额 ---------- */
      try {
         JSONObject po = parse(RhPondTask.callAndWait(ctx, API_POINT, "1.0", baseBody().toString(), "pt", 8000L));
         if (po != null && hasSuccess(po)) {
            JSONObject pd = po.optJSONObject("data");
            if (pd != null) {
               rep.append("[0] 余额: ").append(pd.optString("gamePointBalance", "?"))
                  .append(" = ").append(pd.optString("estimatedAmountText", "?")).append('\n');
            }
         } else {
            rep.append("[0] 余额查询失败 ").append(briefRet(po)).append('\n');
         }
      } catch (Throwable t) {
         rep.append("[0] 余额异常 ").append(t).append('\n');
      }
      sleep(600L);

      /* ---------- [0.5] 游戏中心连续签到 ---------- */
      // play.sign.in* 必须带 playId，而 playId 是每个「玩一玩」实例的数字 ID，源码里查不到、
      // 也不能硬编码。做法：从旁档 rh_apiproto.jsonl 里捞 App 自己发过的 playId（用户打开一次
      // 赚游戏币页就会留下），捞到就能全自动签到，捞不到报告里明说，不猜。
      try {
         rep.append("[0.5] ").append(playSign(ctx)).append('\n');
      } catch (Throwable t) {
         rep.append("[0.5] 签到异常 ").append(t).append('\n');
      }
      sleep(600L);

      /* ---------- [1] 中央游戏任务（复用 RhGameTask 引擎） ---------- */
      try {
         String gameRep = RhGameTask.run(ctx);
         rep.append("[1] 中央任务: ").append(condense(gameRep)).append('\n');
      } catch (Throwable t) {
         rep.append("[1] 中央任务异常 ").append(t).append('\n');
      }
      sleep(800L);

      /* ---------- [2] 渠道任务 ---------- */
      int doneAward = 0;
      int failC = 0;
      try {
         JSONArray tasks = channelTasks(ctx);
         if (tasks == null) {
            rep.append("[2] 渠道清单拉取失败（3 轮重试仍 TASK_PLATFORM_ERROR，稍后点本行复扫即可）\n");
         } else {
            int nAcc = 0;
            int nAward = 0;
            for (int i = 0; i < tasks.length(); i++) {
               JSONObject t = tasks.optJSONObject(i);
               if (t == null) continue;
               String st = t.optString("taskStatus");
               if ("ACCEPTED".equals(st)) nAcc++;
               if ("AWARD".equals(st)) nAward++;
            }
            rep.append("[2] 渠道: 共 ").append(tasks.length())
               .append(" 项 / ACCEPTED ").append(nAcc)
               .append(" / AWARD ").append(nAward).append('\n');

            for (int i = 0; i < tasks.length(); i++) {
               JSONObject t = tasks.optJSONObject(i);
               if (t == null) continue;
               String tid = t.optString("taskId");
               String st = t.optString("taskStatus");
               String label = taskLabel(t);
               if ("COMPLETE".equals(st)) continue; // 已收口
               sleep(500L);

               if ("AWARD".equals(st)) {
                  // 待领奖：直接 task.award
                  int got = awardChannel(ctx, tid);
                  if (got >= 0) {
                     doneAward++;
                     earned += got;
                     rep.append("    ✓ 领奖 ").append(label).append(" +").append(got).append(" 币\n");
                  } else {
                     failC++;
                     rep.append("    ✗ 领奖失败 ").append(label).append('\n');
                  }
                  continue;
               }

      // ACCEPTED：先试协议 complete（channel_click 同款）
      String jurl = t.optString("jumpUrl", "");
      if (jurl.contains("showAds") && jurl.contains("adType=ecMall")) {
         // 逛一逛类：走广告位协议（ad.query → 等浏览时长 → event.push），
         // 不再 startActivity —— 拉起 fleamarket://showAds 会落到「闲鱼已是最新版」兜底页，
         // 就是用户反馈的自动跳转（2026-11-11 现场取证）
         int got = runEcMallAd(ctx, jurl, tid, label, rep);
         if (got >= 0) {
            doneAward++;
            earned += got;
         } else {
            failC++;
         }
         continue;
      }
      if (jurl.contains("showAds") && jurl.contains("adType=rewardVideo")) {
         // 激励视频：协议可通，不必真播 —— 见 runRewardVideoAd 的方法注释（2026-11-12 实证）
         JSONObject gmv = t.optJSONObject("game");
         String akv = gmv != null ? gmv.optString("appkey", "") : t.optString("appkey", "");
         String scv = t.optString("taskScene", "");
         if (scv.length() == 0) scv = "GAME_CHANNEL";
         int got = runRewardVideoAd(ctx, jurl, tid, akv, scv, label, rep);
         if (got >= 0) {
            doneAward++;
            earned += got;
         } else {
            failC++;
         }
         continue;
      }

               try {
                  // v1.5.2-fix2：按 2026-11-11 实证格式构造上报体
                  //   level/visit 类 → progressValue=<taskValue>
                  //   action 类     → action=<taskAction> + payload={"value":<taskValue>}
                  //   reportId 客户端自生成 UUID 即可（服务端只做幂等去重）
                  //   taskScene 必须带（值取任务自身的 taskScene，缺省 GAME_CHANNEL）——
                  //   少了它服务端按默认场景找 taskId，找不到就 TASK_NOT_FOUND。
                  JSONObject gm = t.optJSONObject("game");
                  String appkey = gm != null ? gm.optString("appkey", "") : t.optString("appkey", "");
                  String scene = t.optString("taskScene", "");
                  if (scene.length() == 0) scene = "GAME_CHANNEL";

                  // 前置登记：detail.query 带 appkey+taskScene 返回 SUCCESS/ACCEPTED，
                  // 之后 complete 才认这个 taskId。失败不阻断，继续按原流程试（部分场景无需登记）。
                  JSONObject db = baseBody();
                  db.put("taskId", tid);
                  db.put("taskScene", scene);
                  if (appkey.length() > 0) db.put("appkey", appkey);
                  JSONObject dobj = parse(RhPondTask.callAndWait(ctx, API_CH_DETAIL, "1.0", db.toString(), "chd", 6000L));
                  boolean registered = dobj != null && hasSuccess(dobj);
                  if (!registered) {
                     RhFileLog.write("[coin] detail " + tid + " " + briefRet(dobj));
                  }

                  JSONObject cb = baseBody();
                  cb.put("taskId", tid);
                  cb.put("taskScene", scene);
                  cb.put("reportId", UUID.randomUUID().toString());
                  if (appkey.length() > 0) cb.put("appkey", appkey);
                  String ttype = t.optString("taskType", "");
                  String taction = t.optString("taskAction", "");
                  int tval = parseInt(t.optString("taskValue", "1"), 1);
                  if ("action".equals(ttype) && taction.length() > 0) {
                     cb.put("action", taction);
                     cb.put("payload", "{\"value\":" + tval + "}");
                  } else {
                     cb.put("progressValue", tval);
                  }
                  JSONObject co = parse(RhPondTask.callAndWait(ctx, API_CH_COMPLETE, "1.0", cb.toString(), "chc", 6000L));
                  boolean doneFlag = co != null && hasSuccess(co)
                          && co.optJSONObject("data") != null
                          && co.optJSONObject("data").optBoolean("completed", false);
                  if (doneFlag) {
                     int got = awardChannel(ctx, tid, appkey, scene);
                     if (got >= 0) {
                        doneAward++;
                        earned += got;
                        rep.append("    ✓ 上报+领奖 ").append(label).append(" +").append(got).append(" 币\n");
                     } else {
                        // complete 成功但 award 未发放（可能还在 ACCEPTED）：留给复扫
                        rep.append("    · 上报成功 ").append(label).append("，领奖未发放（复扫再收）\n");
                     }
                     sleep(700L);
                     continue;
                  }

                  // 回退 revisit.complete（只对「再访/进店」类有意义，别类打了会污染 lastVisit）
                  if ("channel_revisit".equals(ttype)) {
                     // sourceId 决定服务端认定「从哪儿进来的」：
                     //   desktop_shortcut 型任务必须报 desktop_shortcut，报 TASK_RECOMMEND 只会返回
                     //   result:true 但任务仍停在 ACCEPTED（2026-11-11 实测：换 sourceId 后立刻转 AWARD）
                     String src = "desktop_shortcut".equals(taction) ? "desktop_shortcut" : "TASK_RECOMMEND";
                     JSONObject rb = baseBody();
                     rb.put("taskId", tid);
                     rb.put("taskScene", scene);
                     rb.put("reportId", rid("revisit"));
                     rb.put("sourceId", src);
                     if (appkey.length() > 0) rb.put("appkey", appkey);
                     JSONObject ro = parse(RhPondTask.callAndWait(ctx, API_CH_REVISIT, "1.0", rb.toString(), "chr", 6000L));
                     if (ro != null && hasSuccess(ro)) {
                        int got = awardChannel(ctx, tid, appkey, scene);
                        if (got >= 0) {
                           doneAward++;
                           earned += got;
                           rep.append("    ✓ 再访(").append(src).append(")+领奖 ").append(label).append(" +").append(got).append(" 币\n");
                        } else {
                           rep.append("    · 再访成功 ").append(label).append("，领奖未发放（复扫再收）\n");
                        }
                        sleep(700L);
                        continue;
                     }
                  }

                  // 协议全不通：只记账，不拉起页面（拉起即跳「闲鱼已是最新版」兜底页）
                  failC++;
                  rep.append("    ✗ 协议不通 ").append(label).append(' ').append(briefRet(co)).append('\n');
               } catch (Throwable tt) {
                  failC++;
                  rep.append("    ! 上报异常 ").append(label).append(' ').append(tt).append('\n');
               }
            }

            rep.append("[2] 小计: 领奖成功 ").append(doneAward)
               .append(" / 失败 ").append(failC)
               .append(" / 拉起页面 ").append(opened)
               .append(" · 本轮 +").append(earned).append(" 游戏币\n");
         }
      } catch (Throwable t) {
         rep.append("[2] 渠道异常 ").append(t).append('\n');
      }
      sleep(1200L);

      /* ---------- [3] 复核 + 停止条件标记 ---------- */
      int remaining = -1;
      try {
         JSONArray ts2 = channelTasks(ctx);
         if (ts2 != null) {
            int n = 0;
            for (int i = 0; i < ts2.length(); i++) {
               JSONObject t = ts2.optJSONObject(i);
               if (t == null) continue;
               String st = t.optString("taskStatus");
               if ("ACCEPTED".equals(st) || "AWARD".equals(st)) n++;
            }
            remaining = n;
         }
      } catch (Throwable t) {
      }

      if (remaining == 0) {
         int v = markOf(ctx);
         rep.append("[3] 复核: 渠道 0 待收口 → 完成标记 ").append(v)
            .append(v == 0 ? "（本次完成）" : v == 1 ? "（复扫1）" : "（复扫2）").append('\n');
         if (v < 2) {
            markSet(ctx, v + 1);
            rep.append("[3] 标识 → ").append(v + 1);
            rep.append(v + 1 >= 2 ? "，今日自动跑停止（次日 0 点重置；手动点行不受限）\n" : "，下次自动跑为复扫\n");
         } else {
            rep.append("[3] 标识=2（今日已判定完成 3 次），保持停止\n");
         }
      } else if (remaining > 0) {
         rep.append("[3] 复核: 渠道 ").append(remaining).append(" 个待收口（浏览类浏览完成后点本行复扫领奖），未打标记\n");
      } else {
         rep.append("[3] 复核: 查询失败，未打标记（下次仍会重试）\n");
      }

      /* ---------- [3.5] 标识落 2 → 触发自动兑换（RhCoinExchange 内部有窗口/每日次数门） ---------- */
      if (markOf(ctx) >= 2) {
         RhCoinExchange.onCoinDone(ctx);
      }

      /* ---------- [4] 浏览复扫（有拉起才起线程） ---------- */
      final Context fc = ctx;
      final RhReport frep = rep;
      if (opened > 0) {
         new Thread(new Runnable() {
            @Override
            public void run() {
               try {
                  Thread.sleep(BROWSE_RESCAN_DELAY);
                  StringBuilder sb = new StringBuilder("\n[4] 浏览复扫 @").append(System.currentTimeMillis()).append('\n');
                  JSONArray ts3 = channelTasks(fc);
                  int got3 = 0;
                  if (ts3 != null) {
                     for (int i = 0; i < ts3.length(); i++) {
                        JSONObject t = ts3.optJSONObject(i);
                        if (t == null || !"AWARD".equals(t.optString("taskStatus"))) continue;
                        JSONObject g3 = t.optJSONObject("game");
                        String ak3 = g3 != null ? g3.optString("appkey", "") : "";
                        String sc3 = t.optString("taskScene", "");
                        if (sc3.length() == 0) sc3 = "GAME_CHANNEL";
                        int g = awardChannel(fc, t.optString("taskId"), ak3, sc3);
                        if (g >= 0) {
                           got3 += g;
                           sb.append("    ✓ ").append(taskLabel(t)).append(" +").append(g).append(" 币\n");
                        }
                        sleep(700L);
                     }
                  }
                  sb.append("[4] 浏览复扫小计: +").append(got3).append(" 币\n");
                  frep.append(sb.toString());
                  writeReport(fc, frep);
                  RhFileLog.write("[coin] browse rescan: +" + got3);
               } catch (Throwable t) {
                  RhFileLog.write("[coin] browse rescan fail: " + t);
               }
            }
         }, "rh-coin-rescan").start();
      }

      String out = rep.toString();
      writeReport(ctx, rep);
      RhFileLog.write(out);
      return out;
   }

   /* ================= 渠道领奖 ================= */

   /**
    * 拉渠道清单，最多 3 轮。
    *
    * 为什么必须重试：这个接口会回 TASK_PLATFORM_ERROR::任务服务繁忙，而且回得很随机
    * （App 自己在游戏页轮询时尤其容易撞上）。实测同一秒里 PC 侧直调成功、引擎内调用失败，
    * 说明是平台侧并发判重，不是参数问题 —— 参数三种写法（空 body / 只 gps / fishAdCode+gps）
    * 现场都能拿到 SUCCESS。既然不是参数错，就别改参数，改成隔 2s 再打；
    * 第 3 轮退化成空 body（少带上下文，命中率反而更高）。
    */
   private static JSONArray channelTasks(Context ctx) {
      for (int round = 0; round < 3; round++) {
         if (round > 0) sleep(2000L);
         String body = round >= 2 ? "{}" : baseBody().toString();
         JSONObject o = parse(RhPondTask.callAndWait(ctx, API_CH_QUERY, "1.0", body, "chq", 8000L));
         if (o != null && hasSuccess(o) && o.optJSONObject("data") != null) {
            JSONArray a = o.optJSONObject("data").optJSONArray("tasks");
            if (a != null) return a;
         }
         RhFileLog.write("[coin] chq round" + round + " fail: " + briefRet(o));
      }
      return null;
   }

   /** 兼容旧调用：不带 appkey/场景，按 GAME_CHANNEL 领 */
   private static int awardChannel(Context ctx, String taskId) {
      return awardChannel(ctx, taskId, "", "GAME_CHANNEL");
   }

   /**
    * task.award；返回 rewardAmount，失败/未发放返回 -1。
    * 参数必须与 complete 完全一致（taskScene + appkey），
    * 否则服务端匹配到的不是同一条任务，会出现「awarded=true 但奖励没进账」。
    */
   private static int awardChannel(Context ctx, String taskId, String appkey, String scene) {
      try {
         JSONObject ab = baseBody();
         ab.put("taskId", taskId);
         ab.put("taskScene", scene == null || scene.length() == 0 ? "GAME_CHANNEL" : scene);
         if (appkey != null && appkey.length() > 0) ab.put("appkey", appkey);
         JSONObject ao = parse(RhPondTask.callAndWait(ctx, API_CH_AWARD, "1.0", ab.toString(), "cha", 6000L));
         if (ao != null && hasSuccess(ao)) {
            JSONObject ad = ao.optJSONObject("data");
            if (ad != null && ad.optBoolean("awarded", true)) {
               return parseInt(ad.optString("rewardAmount", "0"), 0);
            }
         }
      } catch (Throwable t) {
         RhFileLog.write("[coin] award " + taskId + " err: " + t);
      }
      return -1;
   }

   /* ================= 游戏中心签到 ================= */

   /**
    * 连续签到的两步：home 查状态 → 未签则 play.sign.in。
    * playId 来源：rh_apiproto.jsonl 里 App 自己发过的 play.sign.in* 请求参数，
    * 命中后落 rh_playid.txt，之后不必再打开页面。
    */
   /**
    * 游戏中心「点击签到」+「连续签到奖励」。
    *
    * playId 不再靠猜：2026-11-11 现场抓包确证——
    *   playId=4   游戏中心 / 赚游戏币页（连续签到阶梯 +50/80/100… 游戏币）
    *   playId=101 神奇鱼塘「赚饲料」面板（+10/+10/+20 g 饲料）
    * 两个 playId 都是服务端固定的玩法实例 ID，不随账号变，所以直接内置；
    * 若日后活动换 ID，改常量即可（也支持 rh_sign_playid.txt 覆盖，免重编译）。
    *
    * 两条链路都要打：
    *   mini.game.sign.in  → 页面上的「点击签到」（阶梯奖励，幂等，已签返回 signed=true）
    *   play.sign.in       → 游戏中心签到记录（signInDays 累加在这里）
    */
   private static String playSign(Context ctx) {
      long pid = signPlayId(ctx);
      StringBuilder out = new StringBuilder();

      /* --- ① 阶梯签到：mini.game.sign.in{playId} --- */
      JSONObject q = new JSONObject();
      try {
         q.put("playId", pid);
      } catch (Throwable t) {
         return "playId 非法";
      }
      JSONObject qo = parse(RhPondTask.callAndWait(ctx, API_GAME_SIGN_Q, "1.0", q.toString(), "gsq", 6000L));
      JSONObject qd = qo == null ? null : qo.optJSONObject("data");
      String days = qd == null ? "" : qd.optString("continuousDays", "");
      String today = qd == null ? "" : qd.optString("currentDay", "");
      out.append("playId=").append(pid);
      if (qd != null) {
         out.append(" 连签").append(days).append("天/今日第").append(today).append("档");
      } else {
         out.append(" 状态查询失败(").append(briefRet(qo)).append(")");
      }

      JSONObject so = parse(RhPondTask.callAndWait(ctx, API_GAME_SIGN, "1.0", q.toString(), "gsd", 6000L));
      if (so != null && hasSuccess(so)) {
         JSONObject sd = so.optJSONObject("data");
         boolean already = sd != null && "true".equals(sd.optString("signed", ""));
         String amt = sd == null ? "" : (sd.optJSONObject("reward") == null
                 ? "" : sd.optJSONObject("reward").optString("pointNum", ""));
         out.append(already ? " → 今日已签" : " → 签到成功");
         if (amt.length() > 0) out.append(" +").append(amt);
      } else {
         String r = briefRet(so);
         out.append(" → ").append(r != null && r.contains("SIGNED") ? "今日已签" : "签到失败 " + r);
      }

      /* --- ② 签到记录：play.sign.in{playId, usertoken:""} --- */
      JSONObject hb = new JSONObject();
      try {
         hb.put("playId", pid);
         hb.put("usertoken", "");
      } catch (Throwable ignored) {
      }
      JSONObject ho = parse(RhPondTask.callAndWait(ctx, API_SIGN_HOME, "1.0", hb.toString(), "sigh", 6000L));
      if (ho != null && hasSuccess(ho) && ho.optJSONObject("data") != null) {
         JSONObject hd = ho.optJSONObject("data");
         out.append(" | 记录 signInDays=").append(hd.optString("signInDays", "?"))
                 .append(" awarded=").append(hd.optString("signInAwarded", "?"));
      }
      return out.toString();
   }

   /** 签到 playId：rh_sign_playid.txt 覆盖（可选）→ 内置 4（游戏中心） */
   private static long signPlayId(Context ctx) {
      try {
         File dir = ctx.getExternalFilesDir(null);
         if (dir == null) dir = ctx.getFilesDir();
         File f = new File(dir, "rh_sign_playid.txt");
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
      return 4L;
   }

   /** playId 解析：持久档 → 旁档 jsonl 里最近的 play.sign.in 请求 */
   private static String playIdOf(Context ctx) {
      try {
         File dir = ctx.getExternalFilesDir(null);
         if (dir == null) dir = ctx.getFilesDir();
         File f = new File(dir, "rh_playid.txt");
         if (f.exists() && f.length() > 0) {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            in.read(b);
            in.close();
            String s = new String(b, "UTF-8").trim();
            if (s.length() > 0) return s;
         }
      } catch (Throwable ignored) {
      }
      // 旁档：/storage/emulated/0/Android/data/com.taobao.idlefish/files/rh_apiproto.jsonl
      String found = "";
      try {
         File pf = new File(ConfigManager.fishExternal("files"), "rh_apiproto.jsonl");
         if (pf.exists() && pf.length() > 0) {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(pf, "r");
            long len = raf.length();
            long from = Math.max(0, len - 2L * 1024 * 1024); // 只扫尾部 2MB，够覆盖当天
            raf.seek(from);
            byte[] b = new byte[(int) (len - from)];
            raf.readFully(b);
            raf.close();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"playId\"\\s*:\\s*\"?(\\d{3,})\"?")
                    .matcher(new String(b, "UTF-8"));
            while (m.find()) found = m.group(1); // 取最后一个（最新）
         }
      } catch (Throwable ignored) {
      }
      if (found.length() > 0) {
         try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File tmp = new File(dir, "rh_playid.txt.tmp");
            FileWriter w = new FileWriter(tmp, false);
            w.write(found);
            w.close();
            File dst = new File(dir, "rh_playid.txt");
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
         } catch (Throwable ignored) {
         }
      }
      return found;
   }

   /* ================= 广告位纯协议（rewardVideo 激励视频 / ecMall 逛一逛） ================= */

   /**
    * 激励视频（CSJ rewardVideo）—— 协议可通，不需要真播。
    *
    * 关键在 jumpUrl 自带的 adEventPush 参数：它就是服务端下发给客户端的「观看完成上报描述」。
    *   例：adEventPush={"adPositionCode":"xyGameTask_bidding","eventType":"firstEvent","countdown":15}
    * 把这个 JSON 原样展开成 play.ad.event.push 的入参推回去，服务端即认定观看完成：
    *     push firstEvent  → 任务 ACCEPTED → AWARD（实测 5s 内翻转，claimableCount 0→1）
    *     task.award       → awarded=true / rewardAmount=100 / taskStatus=COMPLETE
    * 2026-11-12 02:5x 实测：渠道最后一项「看视频赚游戏币」(1047271694) 由 ACCEPTED 直达 COMPLETE，
    * 27/27 清零，余额 +100。之前判「必须真人播放」是误判 —— 服务端只认这次 push，不校验 CSJ 会话。
    *
    * 两个坑：
    *   ① 同一 adPositionCode 当日额度一发即满，重复推返回
    *      FAIL_BIZ_reach_upper_limit_error::任务已达上限 —— 要当「已达成」处理，不能当失败。
    *   ② 少了 adPositionCode 会 FAIL_SYS_BIZPARAM_MISSED；eventType/countdown 缺省也能过，
    *      但按服务端下发的原值推最稳（客户端本来就是这么读的）。
    */
   private static int runRewardVideoAd(Context ctx, String jumpUrl, String taskId,
                                       String appkey, String scene, String label, RhReport rep) {
      try {
         android.net.Uri u = android.net.Uri.parse(jumpUrl);
         String spec = u.getQueryParameter("adEventPush");
         if (spec == null || spec.length() == 0) {
            rep.append("    … ").append(label).append(" 无 adEventPush，跳过\n");
            return -1;
         }
         JSONObject sp = new JSONObject(spec);
         String code = sp.optString("adPositionCode", "");
         String etype = sp.optString("eventType", "firstEvent");
         int cd = sp.optInt("countdown", 0);
         if (code.length() == 0) {
            rep.append("    … ").append(label).append(" adEventPush 缺 adPositionCode\n");
            return -1;
         }

         JSONObject pb = new JSONObject();
         pb.put("adPositionCode", code);
         pb.put("eventType", etype);
         if (cd > 0) pb.put("countdown", cd);
         JSONObject po = parse(RhPondTask.callAndWait(ctx, API_AD_EVT_P, "1.0", pb.toString(), "adpv", 6000L));
         String ret = briefRet(po);
         boolean ok = po != null && hasSuccess(po);
         boolean capped = ret != null && ret.contains("reach_upper_limit");
         rep.append("    · ").append(label).append(" push ").append(code).append('/').append(etype)
            .append(" → ").append(ok ? "SUCCESS" : capped ? "已达上限（按已达成处理）" : ret).append('\n');
         if (!ok && !capped) return -1;

         sleep(2000L);

         // 复核：服务端翻转不是同步返回的，要重新拉清单看 taskStatus
         JSONArray ts = channelTasks(ctx);
         String st = null;
         for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject x = ts.optJSONObject(i);
            if (x != null && taskId.equals(x.optString("taskId"))) {
               st = x.optString("taskStatus");
               break;
            }
         }
         if ("COMPLETE".equals(st)) return 0;
         if (!"AWARD".equals(st)) {
            rep.append("    · ").append(label).append(" 未推进（status=").append(st).append("），下次复扫再收\n");
            return -1;
         }
         int got = awardChannel(ctx, taskId, appkey, scene);
         if (got >= 0) {
            rep.append("    ✓ 领奖 ").append(label).append(" +").append(got).append(" 币\n");
         }
         return got;
      } catch (Throwable t) {
         rep.append("    ! ").append(label).append(" 激励视频异常 ").append(t).append('\n');
         return -1;
      }
   }

   /**
    * 逛一逛类渠道任务：play.ad.query 取 token → 等够 browse.rewardDuration →
    * play.ad.event.query 预检 → play.ad.event.push（最多 5 发，30s 间隔）。
    * 2026-11-11 米9 日志实证：ecMall push 成功过三次（去浏览福利好物 / 逛逛商城领超值优惠券 /
    * 搜一搜推荐好物），说明这条路能打通；拉起 fleamarket://showAds 反而只会落到
    * 「当前闲鱼app已经是最新版」兜底页。返回获得游戏币数，失败 -1。
    */
   private static int runEcMallAd(Context ctx, String jumpUrl, String taskId,
                                  String label, RhReport rep) {
      try {
         Uri u = Uri.parse(jumpUrl);
         String code = u.getQueryParameter("adPositionCode");
         String scene = u.getQueryParameter("scenarioId");
         String src = u.getQueryParameter("sourceType");
         if (code == null || code.length() == 0) {
            rep.append("    ✗ ").append(label).append(" 无 adPositionCode\n");
            return -1;
         }
         JSONObject extra = new JSONObject();
         extra.put("scenarioId", scene == null ? "" : scene);
         extra.put("sourceType", src == null ? "FEEDS_SLOT" : src);
         JSONObject qb = new JSONObject();
         qb.put("code", code);
         qb.put("extra", extra.toString());
         JSONObject qo = parse(RhPondTask.callAndWait(ctx, API_AD_QUERY, "1.0", qb.toString(), "adq", 6000L));
         if (qo == null || !hasSuccess(qo)) {
            rep.append("    ✗ ").append(label).append(" ad.query ").append(briefRet(qo)).append('\n');
            return -1;
         }
         JSONObject d = qo.optJSONObject("data");
         String token = d == null ? "" : d.optString("token");
         String implId = d == null ? "" : d.optString("implId");
         int dur = 20;
         try {
            JSONObject attrs = new JSONObject(d.optString("attributes", "{}"));
            JSONObject cfg = attrs.optJSONObject("taskConfig");
            JSONObject br = cfg == null ? null : cfg.optJSONObject("browse");
            int rd = br == null ? 20 : parseInt(briefNum(br, "rewardDuration"), 20);
            if (rd > 0) dur = rd;
         } catch (Throwable ignored) {
         }
         if (token.length() == 0) {
            rep.append("    ✗ ").append(label).append(" 未下发 token\n");
            return -1;
         }
         rep.append("    · ").append(label).append(" token=").append(token)
            .append(" 浏览 ").append(dur).append("s\n");
         sleep((long) dur * 1000L + 2000L);

         JSONObject eb = new JSONObject();
         eb.put("adPositionCode", code);
         eb.put("eventType", "award");
         eb.put("token", token);
         eb.put("implId", implId);
         String pre = briefRet(parse(RhPondTask.callAndWait(ctx, API_AD_EVT_Q, "1.0", eb.toString(), "adqp", 6000L)));
         rep.append("    · 预检 ").append(pre).append('\n');

         String last = null;
         for (int i = 0; i < 5; i++) {
            JSONObject po = parse(RhPondTask.callAndWait(ctx, API_AD_EVT_P, "1.0", eb.toString(), "adpp", 6000L));
            last = briefRet(po);
            if (last != null && (last.contains("SUCCESS") || last.contains("TOKEN_MISMATCH"))) break;
            sleep(30000L);
         }
         rep.append("    · push ").append(last).append('\n');
         sleep(1500L);

         // 复核：任务转 AWARD 才发奖
         JSONObject cho = parse(RhPondTask.callAndWait(ctx, API_CH_QUERY, "1.0", baseBody().toString(), "chq2", 8000L));
         String st = null;
         if (cho != null && hasSuccess(cho) && cho.optJSONObject("data") != null) {
            JSONArray ts = cho.optJSONObject("data").optJSONArray("tasks");
            for (int i = 0; ts != null && i < ts.length(); i++) {
               JSONObject x = ts.optJSONObject(i);
               if (x != null && taskId.equals(x.optString("taskId"))) {
                  st = x.optString("taskStatus");
                  break;
               }
            }
         }
         if (!"AWARD".equals(st)) {
            rep.append("    · ").append(label).append(" 未推进（status=").append(st).append("）\n");
            return -1;
         }
         int got = awardChannel(ctx, taskId);
         if (got >= 0) rep.append("    ✓ 领奖 ").append(label).append(" +").append(got).append(" 币\n");
         return got;
      } catch (Throwable t) {
         rep.append("    ! ").append(label).append(" 逛一逛异常 ").append(t).append('\n');
         return -1;
      }
   }

   /** 取 JSON 字段并压成字符串（rewardDuration 可能是数字或字符串） */
   private static String briefNum(JSONObject o, String k) {
      try {
         return o.has(k) ? String.valueOf(o.get(k)) : "";
      } catch (Throwable t) {
         return "";
      }
   }

   /* ================= 拉起任务页（已停用） ================= */

   /**
    * 2026-11-11 起不再拉起任何任务页：fleamarket://showAds 深链在当前闲鱼版本会落到
    * 「当前闲鱼app已经是最新版」兜底页（用户反馈的自动跳转），且浏览进度由 CSJ SDK
    * 会话对账，拉起也报不上去。保留空实现只为让历史调用点编译通过。
    */
   private static void openTaskPage(Context ctx, String jumpUrl) {
      RhFileLog.write("[coin] openTaskPage suppressed (showAds 会跳兜底页): "
              + (jumpUrl == null ? "null" : jumpUrl.substring(0, Math.min(80, jumpUrl.length()))));
   }

   /* ================= 标记 / 计数文件 ================= */

   /** 当日标记：0=待跑 1=复扫1 2=复扫2/停止；读坏一律按 0 */
   static int markOf(Context ctx) {
      return dayCount(ctx, MARK_FILE);
   }

   static void markSet(Context ctx, int v) {
      if (v < 0) v = 0;
      if (v > 2) v = 2;
      writeDayCount(ctx, MARK_FILE, v);
      RhFileLog.write("[coin] mark -> " + v);
   }

   private static int dayCount(Context ctx, String file) {
      try {
         File f = new File(rhDir(ctx), file);
         if (!f.exists()) return 0;
         String s = new String(Files.readAllBytes(f.toPath()), "UTF-8").trim();
         int sp = s.indexOf(32);
         String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
         if (sp > 0 && today.equals(s.substring(0, sp))) {
            return Integer.parseInt(s.substring(sp + 1).trim());
         }
         return 0; // 跨天未写 → 视为 0（写时才滚动）
      } catch (Throwable t) {
         return 0;
      }
   }

   private static void writeDayCount(Context ctx, String file, int v) {
      try {
         String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
         File f = new File(rhDir(ctx), file);
         File tmp = new File(f.getParentFile(), file + ".tmp");
         FileWriter w = new FileWriter(tmp, false);
         w.write(today + " " + v);
         w.flush();
         w.close();
         if (f.exists()) f.delete();
         if (!tmp.renameTo(f)) {
            FileWriter w2 = new FileWriter(f, false);
            w2.write(today + " " + v);
            w2.close();
         }
      } catch (Throwable t) {
         RhFileLog.write("[coin] writeDayCount " + file + " fail: " + t);
      }
   }

   /** 当日计数 +1（自动跑 try 用），返回累加后的值 */
   private static int bumpDayCount(Context ctx, String file) {
      int v = dayCount(ctx, file) + 1;
      writeDayCount(ctx, file, v);
      return v;
   }

   private static File rhDir(Context ctx) {
      File f = null;
      try {
         f = ctx.getFilesDir();
      } catch (Throwable t) {
      }
      return f != null ? f : new File("/data/data/com.taobao.idlefish/files");
   }

   /* ================= 报告落盘（persist + externalCache，同 RhGameTask） ================= */

   private static void writeReport(Context ctx, RhReport rep) {
      boolean ok = false;
      try {
         File dir = FishMtopHook.persistDir(ctx);
         if (dir != null) {
            File f = new File(dir, REPORT_NAME);
            FileWriter w = new FileWriter(f, false);
            w.write(rep.toString());
            w.close();
            ok = true;
         }
      } catch (Throwable t) {
         RhFileLog.write("[coin] write report(internal) fail: " + t);
      }
      if (!ok) RhFileLog.write("[coin] REPORT NOT PERSISTED: " + REPORT_NAME);
      try {
         File ec = ctx.getExternalCacheDir();
         if (ec != null && ec.exists()) {
            FileWriter w = new FileWriter(new File(ec, REPORT_NAME), false);
            w.write(rep.toString());
            w.close();
         }
      } catch (Throwable t) {
      }
   }

   /* ================= 工具 ================= */

   /** fishAdCode：本机动态捕获优先；取不到返回空串（禁止写死测试值，baseBody 对空值省略字段） */
   private static String fishAdCode() {
      try {
         String s = RhLocationContext.fishAdCode();
         if (s != null && s.length() > 0) return s;
      } catch (Throwable t) {
      }
      return "";
   }

   /** gps：动态捕获 → 配置 pondGps 覆盖；都取不到返回空串（禁止写死测试值） */
   private static String gps() {
      try {
         String s = RhLocationContext.gps();
         if (s != null && s.length() > 0) return s;
      } catch (Throwable t) {
      }
      try {
         ConfigManager c = ConfigManager.load();
         if (c != null && c.pondGps != null && c.pondGps.length() > 0) return c.pondGps;
      } catch (Throwable t) {
      }
      return "";
   }

   static JSONObject baseBody() {
      JSONObject o = new JSONObject();
      try {
         // 空值省略字段而非 put 空串：服务端对缺 fishAdCode/gps 的请求照常服务
         // （RhLocationContext 设计实证 1.1.1 用户4），put "" 反而可能触发参数校验
         String fa = fishAdCode();
         if (fa.length() > 0) o.put("fishAdCode", fa);
         String gp = gps();
         if (gp.length() > 0) o.put("gps", gp);
      } catch (Throwable t) {
      }
      return o;
   }

   private static String taskLabel(JSONObject t) {
      String s = t.optString("title", "");
      if (s.length() == 0) s = t.optString("taskName", "");
      if (s.length() == 0) s = t.optString("name", "");
      String sub = t.optString("subTitle", "");
      String out = s.length() == 0 ? "任务" + t.optString("taskId") : s;
      return sub.length() > 0 ? out + "/" + sub : out;
   }

   /** 中央任务报告摘行：清单/小计/复核 + 失败行，压成一行分号串。
    *  注意 RhGameTask 的 RhReport 行首带 HH:mm:ss 前缀，必须 contains 匹配 */
   private static String condense(String gameRep) {
      if (gameRep == null || gameRep.length() == 0) return "无输出";
      StringBuilder sb = new StringBuilder();
      String[] ls = gameRep.split("\n");
      for (String l : ls) {
         String s = l.trim();
         boolean keep = s.contains("[1] 清单") || s.contains("[2] 小计") || s.contains("[3] 复核")
                 || s.contains("✗") || s.contains("! ");
         if (keep) {
            if (sb.length() > 0) sb.append("；");
            sb.append(s);
         }
      }
      return sb.length() > 0 ? sb.toString() : "详情见 rh_tasks_game.txt";
   }

   private static String rid(String prefix) {
      return prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
   }

   static int parseInt(String s, int def) {
      try {
         return Integer.parseInt(s.trim());
      } catch (Throwable t) {
         return def;
      }
   }

   static JSONObject parse(String s) {
      if (s == null || s.length() == 0) return null;
      try {
         return new JSONObject(s);
      } catch (Throwable t) {
         return null;
      }
   }

   static boolean hasSuccess(JSONObject o) {
      try {
         JSONArray r = o.getJSONArray("ret");
         for (int i = 0; i < r.length(); i++) {
            if (r.getString(i).contains("SUCCESS")) return true;
         }
      } catch (Throwable t) {
      }
      return false;
   }

   static String briefRet(JSONObject o) {
      if (o == null) return "无响应";
      try {
         return o.getJSONArray("ret").getString(0);
      } catch (Throwable t) {
         return "ret解析失败";
      }
   }

   static void sleep(long ms) {
      try {
         Thread.sleep(ms);
      } catch (InterruptedException t) {
         Thread.currentThread().interrupt();
      }
   }
}
