package com.piggyhand.fish;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v1.4.9 「自动兑换」引擎 —— 赚闲鱼币提现 → 攒满自动兑支付宝现金红包10元：
 *
 * 触发（用户口径）：每日「赚游戏币任务」标识落 2 之后，自动检查一次：
 *   - RhCoinTask.run() 复核把标记推到 2 的时刻；
 *   - RhCoinTask.autoRunDaily 发现标识已是 2 而跳过的时刻（覆盖"10 点后才启动闲鱼"场景）。
 *
 * 硬性条件（自动流程须全过）：
 *   1. 游戏币余额 >= targetGamePoints（服务端当前 100000 = 10 元档，攒满即兑）；
 *   2. 当前时间 >= 10:00（商品每日 10 点上新，10 点前兑换必失败）；
 *   3. 每日查询/兑换仅 1 次：rh_coin_exch.txt 落 "yyyyMMdd 状态"，跨天自动失效。
 *      1=checked（今日已查过/已试，不再消耗）2=exchanged（今日已兑换成功）
 * 手动 COINEX / 点行触发不受"每日 1 次"限制（测试与强制用），报告照实落盘。
 *
 * 兑换链（2026-11-11 抓包实证）：
 *   shoppingmall v2.0 {categoryId:140001, pointProductCode:"IDLE_MINI_GAME_POINT"} → 动态匹配
 *     名称含"支付宝"且 100000 档的红包商品取 benefitId；匹配不到退回实测 11126 由服务端校验兜底；
 *   exchangebenefit v2.0 {asac, benefitId, fishAdCode, gps, pointProductCode:"IDLE_MINI_GAME_POINT"}。
 */
public final class RhCoinExchange {
   static final String API_SHOP = "mtop.taobao.idle.point.pointbank.shoppingmall";
   static final String API_EXCHANGE = "mtop.taobao.idle.point.pointbank.shoppingmall.exchangebenefit";
   static final String API_POINT = "mtop.taobao.idle.mini.game.point.withdraw.query";
   static final String REPORT_NAME = "rh_tasks_coinexch.txt";
   static final String MARK_FILE = "rh_coin_exch.txt";
   /** 每日状态：0=未做 1=checked（已查/已试） 2=exchanged（已兑成功） */
   static final int ST_NONE = 0;
   static final int ST_CHECKED = 1;
   static final int ST_EXCHANGED = 2;
   /** 兑换窗口起始小时（商品 10 点上新） */
   static final int OPEN_HOUR = 10;
   /** 抓包实测的红包商品 ID 兜底值（正常应被列表动态匹配覆盖） */
   static final String FALLBACK_BENEFIT_ID = "11126";

   private RhCoinExchange() {
   }

   /* ================= 触发入口（标识 2 之后） ================= */

   public static void onCoinDone(Context ctx) {
      try {
         boolean on;
         try {
            ConfigManager c = ConfigManager.load();
            on = c != null && c.coinExchange;
         } catch (Throwable t) {
            on = true;
         }
         if (!on) {
            RhFileLog.write("[exch] auto skip: disabled by config (coinExchange=false)");
            return;
         }
         int st = stateOf(ctx);
         if (st != ST_NONE) {
            RhFileLog.write("[exch] auto skip: 今日已处理（状态=" + stateStr(st) + "），每日仅 1 次");
            return;
         }
         Calendar ca = Calendar.getInstance();
         int h = ca.get(Calendar.HOUR_OF_DAY);
         if (h < OPEN_HOUR) {
            RhFileLog.write("[exch] auto skip: " + h + " 点 < 10:00 商品未上新（不消耗当日次数，等下次进闲鱼）");
            return;
         }
         RhFileLog.write("[exch] triggered: 标识=2 且窗口开启，执行今日唯一一次检查");
         runCheck(ctx, false);
      } catch (Throwable t) {
         RhFileLog.write("[exch] onCoinDone err: " + t);
      }
   }

   /* ================= 主流程 ================= */

   /**
    * 完整检查+兑换。manual=true（点行/COINEX）不受"每日 1 次"与时间硬性限制；
    * manual=false（自动）两条硬性条件任一不满足即终止并按口径落标记。
    */
   public static String runCheck(Context ctx, boolean manual) {
      long t0 = System.currentTimeMillis();
      RhReport rep = new RhReport();
      rep.append("\ud83e\udde7 自动兑换报告 @").append(t0).append(manual ? "（手动触发）" : "（自动触发）").append('\n');
      int st0 = stateOf(ctx);
      rep.append("[*] 今日状态=").append(stateStr(st0))
         .append(" · 窗口 >=10:00 · 攒满即兑 · 每日 1 次\n");

      /* ---------- [1] 余额查询 ---------- */
      JSONObject po = RhCoinTask.parse(RhPondTask.callAndWait(ctx, API_POINT, "1.0",
              RhCoinTask.baseBody().toString(), "pt", 8000L));
      if (po == null || !RhCoinTask.hasSuccess(po) || po.optJSONObject("data") == null) {
         rep.append("[1] 余额查询失败 ").append(RhCoinTask.briefRet(po)).append('\n');
         if (!manual) {
            // 严格"每日仅 1 次"：查询失败也消耗当日名额，防止整天重试风暴
            markSet(ctx, ST_CHECKED);
            rep.append("[*] 已落 checked（查询失败同样消耗今日名额，明日再试）\n");
         }
         return finish(ctx, rep, t0);
      }
      JSONObject pd = po.optJSONObject("data");
      long bal = parseLong(pd.optString("gamePointBalance", "0"));
      long target = parseLong(pd.optString("targetGamePoints", "100000"));
      rep.append("[1] 余额: ").append(bal).append(" = ").append(pd.optString("estimatedAmountText", "?"))
         .append(" · 目标 ").append(target)
         .append(" · ").append(pd.optString("progressText", ""))
         .append(" · ").append(pd.optString("tips", "")).append('\n');

      /* ---------- [2] 硬性条件 1：余额攒满 ---------- */
      if (bal < target) {
         rep.append("[2] 硬性条件1 不满足：余额 ").append(bal).append(" < ").append(target).append("，不执行兑换\n");
         if (!manual) {
            markSet(ctx, ST_CHECKED);
            rep.append("[*] 已落 checked（今日不再查询，攒满后明日自动执行）\n");
         }
         return finish(ctx, rep, t0);
      }
      rep.append("[2] 硬性条件1 通过：余额 ").append(bal).append(" >= ").append(target).append('\n');

      /* ---------- [3] 硬性条件 2：时间窗口 ---------- */
      Calendar ca = Calendar.getInstance();
      int h = ca.get(Calendar.HOUR_OF_DAY);
      int mi = ca.get(Calendar.MINUTE);
      if (h < OPEN_HOUR) {
         if (!manual) {
            rep.append("[3] 硬性条件2 不满足：").append(h).append(':').append(mi < 10 ? "0" : "").append(mi)
               .append(" < 10:00，商品未上新，自动流程跳过（不消耗当日次数）\n");
            return finish(ctx, rep, t0);
         }
         rep.append("[3] 警告：").append(h).append(':').append(mi < 10 ? "0" : "").append(mi)
            .append(" < 10:00（商品未上新），手动强制继续，大概率兑换失败\n");
      } else {
         rep.append("[3] 硬性条件2 通过：当前 ").append(h).append(':').append(mi < 10 ? "0" : "").append(mi)
            .append(" >= 10:00\n");
      }

      /* ---------- [4] 商品匹配（动态 benefitId） ---------- */
      String benefitId = FALLBACK_BENEFIT_ID;
      String how = "抓包实测兜底值";
      try {
         JSONObject sb = RhCoinTask.baseBody();
         sb.put("categoryId", 140001);
         sb.put("coinExchangeMall", true);
         sb.put("pageNumber", 1);
         sb.put("pageSize", 30);
         sb.put("pointProductCode", "IDLE_MINI_GAME_POINT");
         sb.put("withInventoryDetail", true);
         JSONObject so = RhCoinTask.parse(RhPondTask.callAndWait(ctx, API_SHOP, "2.0", sb.toString(), "ptshop", 8000L));
         if (so != null && RhCoinTask.hasSuccess(so) && so.optJSONObject("data") != null) {
            JSONArray arr = so.optJSONObject("data").optJSONArray("data");
            if (arr != null) {
               for (int i = 0; i < arr.length(); i++) {
                  String item = String.valueOf(arr.opt(i));
                  // 宽松匹配：名称含"支付宝"且 100000 档，避免依赖未知字段名
                  if (item.contains("支付宝") && item.contains("100000")) {
                     JSONObject it = arr.optJSONObject(i);
                     String bid = it == null ? "" : it.optString("benefitId");
                     if (bid.length() > 0) {
                        benefitId = bid;
                        how = "列表动态匹配";
                     }
                     break;
                  }
               }
            }
         }
         rep.append("[4] 商品: benefitId=").append(benefitId).append("（").append(how).append("）\n");
      } catch (Throwable t) {
         rep.append("[4] 商品列表异常，退回 benefitId=").append(benefitId).append(' ').append(t).append('\n');
      }
      RhCoinTask.sleep(600L);

      /* ---------- [5] 兑换 ---------- */
      try {
         JSONObject eb = RhCoinTask.baseBody();
         String asac = RhLocationContext.asac();
         if (asac != null && asac.length() > 0) {
            eb.put("asac", asac); // 抓包实证必带；无捕获则省略由服务端判
         }
         eb.put("benefitId", RhCoinTask.parseInt(benefitId, 11126));
         eb.put("pointProductCode", "IDLE_MINI_GAME_POINT");
         JSONObject eo = RhCoinTask.parse(RhPondTask.callAndWait(ctx, API_EXCHANGE, "2.0", eb.toString(), "ptex", 8000L));
         if (eo != null && RhCoinTask.hasSuccess(eo)) {
            markSet(ctx, ST_EXCHANGED);
            rep.append("[5] \u2713 兑换成功！支付宝现金红包10元已发放（服务端幂等，重复兑会被拦）\n");
         } else {
            rep.append("[5] \u2717 兑换失败 ").append(RhCoinTask.briefRet(eo)).append('\n');
            if (!manual) {
               markSet(ctx, ST_CHECKED);
               rep.append("[*] 已落 checked（每日仅 1 次，失败原因见上，明日自动重试）\n");
            }
         }
      } catch (Throwable t) {
         rep.append("[5] ! 兑换异常 ").append(t).append('\n');
         if (!manual) markSet(ctx, ST_CHECKED);
      }

      return finish(ctx, rep, t0);
   }

   /* ================= 每日标记（0/1/2） ================= */

   static int stateOf(Context ctx) {
      try {
         File f = new File(rhDir(ctx), MARK_FILE);
         if (!f.exists()) return ST_NONE;
         String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
         int sp = s.indexOf(32);
         String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
         if (sp > 0 && today.equals(s.substring(0, sp))) {
            return Integer.parseInt(s.substring(sp + 1).trim());
         }
         return ST_NONE; // 跨天自动失效
      } catch (Throwable t) {
         return ST_NONE;
      }
   }

   static void markSet(Context ctx, int v) {
      try {
         String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
         File f = new File(rhDir(ctx), MARK_FILE);
         File tmp = new File(f.getParentFile(), MARK_FILE + ".tmp");
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
         RhFileLog.write("[exch] mark -> " + stateStr(v));
      } catch (Throwable t) {
         RhFileLog.write("[exch] markSet fail: " + t);
      }
   }

   private static String stateStr(int v) {
      return v == ST_EXCHANGED ? "exchanged(已兑换)" : v == ST_CHECKED ? "checked(已查/已试)" : "none(未做)";
   }

   private static File rhDir(Context ctx) {
      File f = null;
      try {
         f = ctx.getFilesDir();
      } catch (Throwable t) {
      }
      return f != null ? f : new File("/data/data/com.taobao.idlefish/files");
   }

   /* ================= 报告落盘（persist + externalCache） ================= */

   private static String finish(Context ctx, RhReport rep, long t0) {
      rep.append("耗时 ").append((System.currentTimeMillis() - t0) / 1000L).append("s\n");
      boolean ok = false;
      try {
         File dir = FishMtopHook.persistDir(ctx);
         if (dir != null) {
            FileWriter w = new FileWriter(new File(dir, REPORT_NAME), false);
            w.write(rep.toString());
            w.close();
            ok = true;
         }
      } catch (Throwable t) {
         RhFileLog.write("[exch] write report(internal) fail: " + t);
      }
      if (!ok) RhFileLog.write("[exch] REPORT NOT PERSISTED: " + REPORT_NAME);
      try {
         File ec = ctx.getExternalCacheDir();
         if (ec != null && ec.exists()) {
            FileWriter w = new FileWriter(new File(ec, REPORT_NAME), false);
            w.write(rep.toString());
            w.close();
         }
      } catch (Throwable t) {
      }
      RhFileLog.write(rep.toString());
      return rep.toString();
   }

   private static long parseLong(String s) {
      try {
         return Long.parseLong(s.trim());
      } catch (Throwable t) {
         return 0L;
      }
   }
}
