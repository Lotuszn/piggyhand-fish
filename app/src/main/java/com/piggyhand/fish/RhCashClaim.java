package com.piggyhand.fish;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RhCashClaim {
   static final String API_LIST = "mtop.taobao.idle.cash.join.list";
   static final String API_WITHDRAW = "mtop.taobao.idle.cash.withdraw";
   static final String ST_FAILED = "0";
   static final String ST_WAITING = "1";
   static final String ST_END = "2";
   static final String ST_TO_OPEN = "3";
   static final String ST_SUCCESS = "6";
   static final String REPORT_FILE = "rh_tasks_cash.txt";
   static final String DONE_FILE = "rh_cash_done.txt";
   static final String STATE_FILE = "rh_cash_claim.txt";
   static final int MAX_ATTEMPT = 2;
   static final long RETRY_GAP = 1800000L;
   static final long WAIT_LIST = 8000L;
   static final long WAIT_WITHDRAW = 8000L;
   static final long OPEN_GRACE = 20000L;
   private static volatile boolean watcherArmed = false;
   private static volatile long nextWakeDelay = 1800000L;

   private RhCashClaim() {
   }

   public static void autoRunDaily(Context var0) {
      try {
         String var1 = null;

         try {
            var1 = (String)Application.class.getMethod("getProcessName").invoke(null);
         } catch (Throwable var4) {
         }

         if (var1 != null && var1.contains(":")) {
            return;
         }

         boolean var2;
         try {
            ConfigManager var3 = ConfigManager.load();
            var2 = var3 == null || var3.cashClaim;
         } catch (Throwable var5) {
            var2 = true;
         }

         if (!var2) {
            RhFileLog.write("[cash] auto-run disabled by config (cashClaim=false), skip");
            return;
         }

         if (isTerminalToday(readState(var0))) {
            RhFileLog.write("[cash] auto-run skip: terminal today (" + stateText(var0) + ")");
            return;
         }

         startWatcher(var0);
      } catch (Throwable var6) {
         RhFileLog.write("[cash] autoRunDaily err: " + var6);
      }
   }

   private static void startWatcher(final Context var0) {
      if (!watcherArmed) {
         watcherArmed = true;
         RhFileLog.write("[cash] watcher armed (window 10:00:00-23:59:55 on draw day)");
         new Thread(new Runnable() {
            @Override
            public void run() {
               if (RhCashClaim.sleepOrDie(55000L)) {
                  int var1 = 0;

                  long var2;
                  do {
                     try {
                        if (RhCashClaim.isTerminalToday(RhCashClaim.readState(var0))) {
                           var2 = RhCashClaim.delayToNextOpen();
                        } else {
                           RhFileLog.write("[cash] watch round " + ++var1 + " start");
                           RhCashClaim.run(var0);
                           var2 = RhCashClaim.nextWakeDelay;
                        }
                     } catch (Throwable var5) {
                        RhFileLog.write("[cash] watch err: " + var5);
                        var2 = 900000L;
                     }

                     RhFileLog.write("[cash] next check in " + var2 / 60000L + "m" + var2 % 60000L / 1000L + "s");
                  } while (RhCashClaim.sleepOrDie(var2));
               }
            }
         }, "rh-cash-watch").start();
      }
   }

   public static String run(Context var0) {
      long var1 = System.currentTimeMillis();
      RhReport var3 = new RhReport();
      var3.append("\ud83e\udde7 现金夺宝开红包 @").append(var1).append('\n');
      JSONObject var4 = readState(var0);
      if (isTerminalToday(var4)) {
         var3.append("[0] 今日已终止（").append(var4.optString("state")).append(" 第 ").append(var4.optInt("round")).append(" 轮），0 点前不再执行\n");
         nextWakeDelay = delayToNextOpen();
         finish(var0, var3, var1);
         return var3.toString();
      } else {
         JSONObject var5 = callMtop(var0, "mtop.taobao.idle.cash.join.list", "1.0", "{\"pageNum\":1,\"pageSize\":20,\"version\":\"v1.1\"}", null, null, 8000L);
         if (var5 != null && hasSuccess(var5)) {
            JSONArray var6;
            try {
               var6 = var5.getJSONObject("data").getJSONArray("items");
            } catch (Throwable var24) {
               var3.append("[1] 记录结构异常: ").append(var24).append('\n');
               nextWakeDelay = 1800000L;
               finish(var0, var3, var1);
               return var3.toString();
            }

            long var7 = System.currentTimeMillis();
            long var9 = Long.MAX_VALUE;
            ArrayList var11 = new ArrayList();
            var3.append("[1] 记录 ").append(var6.length()).append(" 期：\n");

            for (int var12 = 0; var12 < var6.length(); var12++) {
               JSONObject var13 = var6.optJSONObject(var12);
               if (var13 != null) {
                  long var14 = optLong(var13.optString("openDrawTime"));
                  long var16 = optLong(var13.optString("closeDrawTime"));
                  String var18 = var13.optString("showWinStatus", "").trim();
                  boolean var19;
                  String var20;
                  if ("3".equals(var18)) {
                     var19 = true;
                     var20 = "可领";
                  } else if ("1".equals(var18)) {
                     var19 = false;
                     var20 = "待开奖 " + fmt(var14);
                     if (var14 > var7) {
                        var9 = Math.min(var9, var14);
                     }
                  } else if ("2".equals(var18)) {
                     var19 = false;
                     var20 = "已过期";
                  } else if ("6".equals(var18)) {
                     var19 = false;
                     var20 = "已开奖 " + (var13.has("drawAmount") ? var13.optString("drawAmountStr", var13.optString("drawAmount")) : "?");
                  } else if ("0".equals(var18)) {
                     var19 = false;
                     var20 = "开奖失败";
                  } else {
                     var19 = !var13.has("drawAmount") && var14 > 0L && var7 >= var14 && var7 <= var16;
                     var20 = var19 ? "可领(时间窗)" : "未知(" + var18 + ")";
                     if (!var19 && var14 > var7) {
                        var9 = Math.min(var9, var14);
                     }
                  }

                  if (var19) {
                     var11.add(var13);
                  }

                  var3.append("     ")
                     .append(var13.optString("outShowActId"))
                     .append(" actId=")
                     .append(var13.optString("actId"))
                     .append(" id=")
                     .append(var13.optString("id"))
                     .append(" st=")
                     .append(var18)
                     .append(" → ")
                     .append(var20)
                     .append('\n');
               }
            }

            if (var11.isEmpty()) {
               nextWakeDelay = computeNextWake(var9, System.currentTimeMillis());
               var3.append("[1] 本刻无可领的期，下次检查 ").append(fmt(System.currentTimeMillis() + nextWakeDelay)).append('\n');
               finish(var0, var3, var1);
               return var3.toString();
            } else {
               int var26 = 0;
               int var27 = 0;

               for (int var28 = 0; var28 < var11.size(); var28++) {
                  JSONObject var15 = (JSONObject)var11.get(var28);
                  String var30 = var15.optString("actId");
                  String var17 = var15.optString("id");
                  String var33 = var15.optString("outShowActId");
                  var3.append("[2] 领取 ").append(var33).append("（actId=").append(var30).append(" id=").append(var17).append("）\n");
                  JSONObject var36 = new JSONObject();

                  try {
                     var36.put("actId", var30);
                     var36.put("id", var17);
                     // 多用户：asac 走本机动态捕获（RhLocationContext），测试期写死值已移除；无捕获省略字段
                     String varAsac = RhLocationContext.asac();
                     if (varAsac != null) var36.put("asac", varAsac);
                     var36.put("version", "v1.1");
                  } catch (Throwable var23) {
                  }

                  JSONObject var38 = withdraw(var0, var36.toString());
                  if (var38 != null && hasSuccess(var38)) {
                     var26++;
                     JSONObject var21 = var38.optJSONObject("data");
                     String var22 = var21 == null
                        ? "?"
                        : firstNonEmpty(var21.optString("drawAmountStr"), var21.optString("drawAmount"), var21.optString("amountStr"), "?");
                     var3.append("     ✓ 成功 金额=").append(var22).append('\n');
                     sleep(900L);
                  } else {
                     var27++;
                     var3.append("     ✗ 失败 ").append(briefRet(var38)).append('\n');
                     sleep(900L);
                  }
               }

               var3.append("[2] 小计: 成功 ").append(var26).append(" / 失败 ").append(var27).append('\n');
               sleep(1200L);
               long var29 = System.currentTimeMillis();
               JSONObject var31 = callMtop(
                  var0, "mtop.taobao.idle.cash.join.list", "1.0", "{\"pageNum\":1,\"pageSize\":20,\"version\":\"v1.1\"}", null, null, 8000L
               );
               int var32 = -1;
               if (var31 != null && hasSuccess(var31)) {
                  try {
                     JSONArray var34 = var31.getJSONObject("data").getJSONArray("items");
                     int var37 = 0;

                     for (int var39 = 0; var39 < var34.length(); var39++) {
                        JSONObject var40 = var34.optJSONObject(var39);
                        if (var40 != null && "3".equals(var40.optString("showWinStatus", "").trim())) {
                           var37++;
                        }
                     }

                     var32 = var37;
                     var3.append("[3] 复核: 服务端仍标可领 ").append(var37).append(" 期\n");
                  } catch (Throwable var25) {
                     var3.append("[3] 复核解析失败: ").append(var25).append('\n');
                  }
               } else {
                  var3.append("[3] 复核请求失败，本轮不打完成标记\n");
               }

               int var35 = (var4 == null ? 0 : var4.optInt("round", 0)) + 1;
               if (var26 > 0 && var32 <= 0) {
                  markTerminal(var0, var4, "done_claim", var35, "ok=" + var26);
                  markDoneToday(var0);
                  nextWakeDelay = delayToNextOpen();
                  var3.append("[4] ✓ 已领取，落盘 done_claim，当日不再执行\n");
               } else if (var35 >= 2) {
                  markTerminal(var0, var4, var26 > 0 ? "done_partial" : "abort_quota", var35, "ok=" + var26 + " left=" + var32);
                  if (var26 > 0) {
                     markDoneToday(var0);
                  }

                  nextWakeDelay = delayToNextOpen();
                  var3.append("[4] 今日 ").append(2).append(" 轮配额用尽（成功 ").append(var26).append(" 失败 ").append(var27).append("），0 点前不再执行\n");
               } else {
                  writeState(var0, var4, "retry_pending", var35, "ok=" + var26 + " left=" + var32);
                  nextWakeDelay = 1800000L;
                  var3.append("[4] 第 ").append(var35).append("/").append(2).append(" 轮未领到，").append(30L).append(" 分钟后兜底一次\n");
               }

               if (var32 > 0) {
                  var3.append("[3] 仍有 ").append(var32).append(" 期未领到\n");
               }

               var3.append("[4] 下次检查 ").append(fmt(System.currentTimeMillis() + nextWakeDelay)).append('\n');
               finish(var0, var3, var1);
               return var3.toString();
            }
         } else {
            var3.append("[1] 参与记录拉取失败 ").append(briefRet(var5)).append('\n');
            nextWakeDelay = 300000L;
            finish(var0, var3, var1);
            return var3.toString();
         }
      }
   }

   private static JSONObject withdraw(Context var0, String var1) {
      HashMap var2 = new HashMap();
      String varAsac = RhLocationContext.asac(); // 多用户：动态 asac，无捕获省略
      if (varAsac != null) var2.put("asac", varAsac);
      JSONObject var3 = callMtop(var0, "mtop.taobao.idle.cash.withdraw", "1.0", var1, var2, null, 8000L);
      if (var3 != null && hasSuccess(var3)) {
         return var3;
      } else {
         HashMap var4 = new HashMap();
         if (varAsac != null) var4.put("asac", varAsac);
         JSONObject var5 = callMtop(var0, "mtop.taobao.idle.cash.withdraw", "1.0", var1, null, var4, 8000L);
         if (var5 != null && hasSuccess(var5)) {
            return var5;
         } else {
            return var3 != null ? var3 : var5;
         }
      }
   }

   private static long computeNextWake(long var0, long var2) {
      if (var0 != Long.MAX_VALUE && var0 > 0L) {
         long var4 = var0 + 20000L - var2;
         if (var4 <= 0L) {
            return 60000L;
         } else if (var4 > 3600000L) {
            return 1800000L;
         } else if (var4 > 660000L) {
            return 660000L;
         } else {
            return var4 < 60000L ? 60000L : var4;
         }
      } else {
         return 1800000L;
      }
   }

   static JSONObject callMtop(Context var0, String var1, String var2, String var3, Map<String, String> var4, Map<String, String> var5, long var6) {
      String var8 = var1.substring(var1.lastIndexOf(46) + 1);
      File var9 = dir(var0);
      File var10 = new File(var9, "rh_resp_" + var8 + ".json");

      try {
         if (var10.exists()) {
            var10.delete();
         }
      } catch (Throwable var16) {
      }

      if (!ApiMtop.fire(var0, var0.getClassLoader(), var1, var2, var3, var4, var5)) {
         return null;
      } else {
         long var11 = System.currentTimeMillis() + var6;
         String var13 = "\"" + var1 + "\"";

         while (System.currentTimeMillis() < var11) {
            sleep(200L);
            if (var10.exists()) {
               String var14 = readAll(var10);
               if (var14 != null && var14.indexOf(var13) >= 0) {
                  sleep(60L);
                  JSONObject var15 = parse(var14);
                  if (var15 != null) {
                     return var15;
                  }
               }
            }
         }

         RhFileLog.write("[cash] callMtop timeout: " + var1);
         return null;
      }
   }

   /** 响应通道目录：必须与 FishMtopHook.writeCacheFile 同目录（cache/），
    *  callMtop 轮询的就是这里落下来的 rh_resp_<tail>.json。 */
   private static File dir(Context var0) {
      File var1 = null;

      try {
         var1 = FishMtopHook.cacheDir();
      } catch (Throwable var4) {
      }

      if (var1 == null) {
         try {
            var1 = var0.getCacheDir();
         } catch (Throwable var3) {
         }
      }

      if (var1 == null) {
         var1 = new File("/data/data/com.taobao.idlefish/cache");
      }

      if (!var1.exists()) {
         var1.mkdirs();
      }

      return var1;
   }

   /** 状态落盘目录：持久区优先（外部 files，退内部 files），最后才回落 cache。
    *  与 dir() 分开是因为 dir() 是 mtop 响应通道，必须和宿主写响应的目录一致；
    *  状态文件放 cache 会在装包/清缓存时整目录消失，面板就退回「今日未执行」。 */
   private static File stateDir(Context var0) {
      File var1 = null;

      try {
         var1 = FishMtopHook.persistDir(var0);
      } catch (Throwable var2) {
      }

      if (var1 == null) {
         var1 = dir(var0);
      }

      if (!var1.exists()) {
         var1.mkdirs();
      }

      return var1;
   }

   private static String readAll(File var0) {
      try {
         byte[] var1 = new byte[(int)var0.length()];
         FileInputStream var2 = new FileInputStream(var0);
         int var3 = 0;

         while (var3 < var1.length) {
            int var4 = var2.read(var1, var3, var1.length - var3);
            if (var4 <= 0) {
               break;
            }

            var3 += var4;
         }

         var2.close();
         return new String(var1, 0, var3, "UTF-8");
      } catch (Throwable var5) {
         return null;
      }
   }

   private static JSONObject readState(Context var0) {
      try {
         File var1 = new File(stateDir(var0), "rh_cash_claim.txt");
         // 老版本把状态写 cache/：持久区没有时回落读一次，免得升级当天状态丢
         if (!var1.exists()) {
            var1 = new File(dir(var0), "rh_cash_claim.txt");
         }
         if (!var1.exists()) {
            return null;
         } else {
            String var2 = readAll(var1);
            return var2 != null && var2.trim().length() != 0 ? new JSONObject(var2) : null;
         }
      } catch (Throwable var3) {
         return null;
      }
   }

   static boolean isTerminalToday(JSONObject var0) {
      if (var0 == null) {
         return false;
      } else if (!RhCashJoin.today().equals(var0.optString("day", ""))) {
         return false;
      } else {
         String var1 = var0.optString("state", "");
         return var1.startsWith("done_") || var1.startsWith("abort_");
      }
   }

   private static void writeState(Context var0, JSONObject var1, String var2, int var3, String var4) {
      JSONObject var5 = new JSONObject();

      try {
         var5.put("day", RhCashJoin.today());
         var5.put("state", var2);
         var5.put("round", var3);
         var5.put("lastRet", var4 == null ? "" : var4);
         var5.put("at", System.currentTimeMillis());
      } catch (Throwable var11) {
      }

      try {
         File var6 = stateDir(var0);
         File var7 = new File(var6, "rh_cash_claim.txt.tmp");
         FileOutputStream var8 = new FileOutputStream(var7, false);
         var8.write(var5.toString().getBytes("UTF-8"));
         var8.flush();
         var8.close();
         File var9 = new File(var6, "rh_cash_claim.txt");
         if (var9.exists()) {
            var9.delete();
         }

         var7.renameTo(var9);
      } catch (Throwable var10) {
         RhFileLog.write("[cash] writeState fail: " + var10);
      }
   }

   private static void markTerminal(Context var0, JSONObject var1, String var2, int var3, String var4) {
      writeState(var0, var1, var2, var3, var4);
      RhFileLog.write("[cash] TERMINAL " + var2 + " round=" + var3 + " ret=" + var4);
   }

   public static String stateText(Context var0) {
      JSONObject var1 = readState(var0);
      return var1 == null ? "NONE" : var1.optString("day") + "|" + var1.optString("state") + "|" + var1.optInt("round") + "|" + var1.optString("lastRet");
   }

   private static long delayToNextOpen() {
      Calendar var0 = Calendar.getInstance();
      var0.add(5, 1);
      var0.set(11, 0);
      var0.set(12, 1);
      var0.set(13, 30);
      var0.set(14, 0);
      long var1 = var0.getTimeInMillis() - System.currentTimeMillis();
      if (var1 < 60000L) {
         return 60000L;
      } else {
         return var1 > 1800000L ? 1800000L : var1;
      }
   }

   public static boolean isDoneToday(Context var0) {
      try {
         File var1 = new File(stateDir(var0), "rh_cash_done.txt");
         if (!var1.exists()) {
            // 兼容升级前写在 cache/ 的标记
            var1 = new File(dir(var0), "rh_cash_done.txt");
         }
         if (!var1.exists()) {
            return false;
         } else {
            String var2 = readAll(var1);
            return var2 != null && System.currentTimeMillis() < Long.parseLong(var2.trim());
         }
      } catch (Throwable var3) {
         return false;
      }
   }

   private static void markDoneToday(Context var0) {
      try {
         long var1 = nextMidnight();
         File var3 = new File(stateDir(var0), "rh_cash_done.txt");
         FileWriter var4 = new FileWriter(var3, false);
         var4.write(String.valueOf(var1));
         var4.flush();
         var4.close();
         RhFileLog.write("[cash] DONE mark set until " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(var1)));
      } catch (Throwable var5) {
         RhFileLog.write("[cash] markDone fail: " + var5);
      }
   }

   private static long nextMidnight() {
      Calendar var0 = Calendar.getInstance();
      var0.add(5, 1);
      var0.set(11, 0);
      var0.set(12, 0);
      var0.set(13, 0);
      var0.set(14, 0);
      return var0.getTimeInMillis();
   }

   private static String keyOf(JSONObject var0) {
      return var0.optString("actId") + ":" + var0.optString("id");
   }

   private static long optLong(String var0) {
      try {
         return Long.parseLong(var0.trim());
      } catch (Throwable var2) {
         return 0L;
      }
   }

   private static String firstNonEmpty(String... var0) {
      for (String var4 : var0) {
         if (var4 != null && var4.length() > 0 && !"null".equals(var4)) {
            return var4;
         }
      }

      return "";
   }

   private static String fmt(long var0) {
      return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(var0));
   }

   private static JSONObject parse(String var0) {
      if (var0 != null && var0.length() != 0) {
         try {
            return new JSONObject(var0);
         } catch (Throwable var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static boolean hasSuccess(JSONObject var0) {
      if (var0 == null) {
         return false;
      } else {
         try {
            JSONArray var1 = var0.getJSONArray("ret");

            for (int var2 = 0; var2 < var1.length(); var2++) {
               if (var1.getString(var2).contains("SUCCESS")) {
                  return true;
               }
            }
         } catch (Throwable var3) {
         }

         return false;
      }
   }

   private static String briefRet(JSONObject var0) {
      if (var0 == null) {
         return "无响应";
      } else {
         try {
            return var0.getJSONArray("ret").getString(0);
         } catch (Throwable var2) {
            return "ret解析失败";
         }
      }
   }

   private static void finish(Context var0, RhReport var1, long var2) {
      var1.append("耗时 ").append((System.currentTimeMillis() - var2) / 1000L).append("s\n");
      boolean var4 = false;

      try {
         FileWriter var5 = new FileWriter(new File(stateDir(var0), "rh_tasks_cash.txt"), false);
         var5.write(var1.toString());
         var5.close();
         var4 = true;
      } catch (Throwable var8) {
         RhFileLog.write("[cash] write report fail: " + var8);
      }

      if (!var4) {
         RhFileLog.write("[cash] REPORT NOT PERSISTED: rh_tasks_cash.txt");
      }

      try {
         File var9 = var0.getExternalCacheDir();
         if (var9 != null && var9.exists()) {
            FileWriter var6 = new FileWriter(new File(var9, "rh_tasks_cash.txt"), false);
            var6.write(var1.toString());
            var6.close();
         }
      } catch (Throwable var7) {
      }

      RhFileLog.write(var1.toString());
   }

   private static void sleep(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }
   }

   private static boolean sleepOrDie(long var0) {
      try {
         Thread.sleep(var0);
         return true;
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
         return false;
      }
   }
}
