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
   static final String API_CH_COMPLETE = "mtop.taobao.idle.mini.game.task.channel.complete";
   static final String API_CH_REVISIT = "mtop.taobao.idle.mini.game.task.channel.revisit.complete";
   static final String API_CH_AWARD = "mtop.taobao.idle.mini.game.task.award";
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
         JSONObject cho = parse(RhPondTask.callAndWait(ctx, API_CH_QUERY, "1.0", baseBody().toString(), "chq", 8000L));
         JSONArray tasks = cho != null && hasSuccess(cho) && cho.optJSONObject("data") != null
                 ? cho.optJSONObject("data").optJSONArray("tasks") : null;
         if (tasks == null) {
            rep.append("[2] 渠道清单拉取失败 ").append(briefRet(cho)).append('\n');
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
               if (t.optString("jumpUrl", "").contains("showAds")) {
                  // CSJ 浏览类：协议上报必被服务端会话对账拦掉，拉真实页面让 SDK 自己报
                  if (opened < MAX_OPEN_PER_RUN) {
                     opened++;
                     openTaskPage(ctx, t.optString("jumpUrl"));
                     rep.append("    ▶ ").append(label).append(" → 已拉起任务页，真实浏览≥20s 后由 SDK 自动上报，稍后自动复扫领奖\n");
                  } else {
                     rep.append("    … ").append(label).append(" → 浏览类待拉起（本轮额度用完，可再点一次本行）\n");
                  }
                  continue;
               }

               try {
                  JSONObject cb = baseBody();
                  cb.put("taskId", tid);
                  cb.put("reportId", rid("click"));
                  JSONObject co = parse(RhPondTask.callAndWait(ctx, API_CH_COMPLETE, "1.0", cb.toString(), "chc", 6000L));
                  if (co != null && hasSuccess(co)) {
                     int got = awardChannel(ctx, tid);
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

                  // 回退 revisit.complete（进店/再访类）
                  JSONObject rb = baseBody();
                  rb.put("reportId", rid("revisit"));
                  rb.put("sourceId", "TASK_RECOMMEND");
                  JSONObject ro = parse(RhPondTask.callAndWait(ctx, API_CH_REVISIT, "1.0", rb.toString(), "chr", 6000L));
                  if (ro != null && hasSuccess(ro)) {
                     int got = awardChannel(ctx, tid);
                     if (got >= 0) {
                        doneAward++;
                        earned += got;
                        rep.append("    ✓ 再访+领奖 ").append(label).append(" +").append(got).append(" 币\n");
                     } else {
                        rep.append("    · 再访成功 ").append(label).append("，领奖未发放（复扫再收）\n");
                     }
                     sleep(700L);
                     continue;
                  }

                  // 协议全不通：带 jumpUrl 的拉真实页面兜底
                  String ju = t.optString("jumpUrl", "");
                  if (ju.length() > 0 && opened < MAX_OPEN_PER_RUN) {
                     opened++;
                     openTaskPage(ctx, ju);
                     rep.append("    ▶ ").append(label).append(" → 协议不通，已拉起任务页手动完成\n");
                  } else {
                     failC++;
                     rep.append("    ✗ 协议不通 ").append(label).append(' ').append(briefRet(co)).append('\n');
                  }
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
         JSONObject cho2 = parse(RhPondTask.callAndWait(ctx, API_CH_QUERY, "1.0", baseBody().toString(), "chq", 8000L));
         JSONArray ts2 = cho2 != null && hasSuccess(cho2) && cho2.optJSONObject("data") != null
                 ? cho2.optJSONObject("data").optJSONArray("tasks") : null;
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
                  JSONObject cho3 = parse(RhPondTask.callAndWait(fc, API_CH_QUERY, "1.0", baseBody().toString(), "chq", 8000L));
                  JSONArray ts3 = cho3 != null && hasSuccess(cho3) && cho3.optJSONObject("data") != null
                          ? cho3.optJSONObject("data").optJSONArray("tasks") : null;
                  int got3 = 0;
                  if (ts3 != null) {
                     for (int i = 0; i < ts3.length(); i++) {
                        JSONObject t = ts3.optJSONObject(i);
                        if (t == null || !"AWARD".equals(t.optString("taskStatus"))) continue;
                        int g = awardChannel(fc, t.optString("taskId"));
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

   /** task.award(taskScene=GAME_CHANNEL)；返回 rewardAmount，失败/未发放返回 -1 */
   private static int awardChannel(Context ctx, String taskId) {
      try {
         JSONObject ab = baseBody();
         ab.put("taskId", taskId);
         ab.put("taskScene", "GAME_CHANNEL");
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

   /* ================= 拉起任务页 ================= */

   private static void openTaskPage(Context ctx, String jumpUrl) {
      try {
         Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(jumpUrl));
         it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         it.setPackage("com.taobao.idlefish"); // 收敛到闲鱼内接 deeplink，防外溢到浏览器
         ctx.startActivity(it);
      } catch (Throwable t) {
         RhFileLog.write("[coin] openTaskPage fail: " + t);
      }
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
