package com.piggyhand.fish;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RhCashJoin {
   static final String API_DETAIL = "mtop.taobao.idle.cash.detail";
   static final String API_JOIN = "mtop.taobao.idle.cash.join";
   static final String API_COIN = "mtop.taobao.idle.coin.activity.user.info";
   static final String ASAC_JOIN = "2A21419W4V75OCRAXF3VZB";
   static final String STATE_FILE = "rh_cash_join.txt";
   static final String REPORT_FILE = "rh_tasks_join.txt";
   static final int MAX_TRY = 3;
   static final long RETRY_GAP = 60000L;
   static final long WAIT_DETAIL = 8000L;
   static final long WAIT_COIN = 8000L;
   static final long WAIT_JOIN = 15000L;
   static final long OPEN_GRACE = 30000L;
   private static final Object RUN_LOCK = new Object();
   private static volatile boolean running = false;
   private static volatile boolean watcherArmed = false;
   private static volatile long nextWakeDelay = 1800000L;

   private RhCashJoin() {
   }

   public static void autoRunDaily(Context var0) {
      try {
         String var1 = null;

         try {
            var1 = (String)Application.class.getMethod("getProcessName").invoke(null);
         } catch (Throwable var3) {
         }

         if (var1 != null && var1.contains(":")) {
            return;
         }

         if (!switchOn()) {
            RhFileLog.write("[join] auto-run disabled by config (cashJoin=false), skip");
            return;
         }

         startWatcher(var0);
      } catch (Throwable var4) {
         RhFileLog.write("[join] autoRunDaily err: " + var4);
      }
   }

   private static boolean switchOn() {
      try {
         ConfigManager var0 = ConfigManager.load();
         return var0 == null || var0.cashJoin;
      } catch (Throwable var1) {
         return true;
      }
   }

   private static void startWatcher(final Context var0) {
      if (!watcherArmed) {
         watcherArmed = true;
         RhFileLog.write("[join] watcher armed (window opens 00:01, fill to max)");
         new Thread(new Runnable() {
            @Override
            public void run() {
               if (RhCashJoin.sleepOrDie(30000L)) {
                  int var1 = 0;

                  long var2;
                  do {
                     try {
                        if (!RhCashJoin.switchOn()) {
                           var2 = 1800000L;
                        } else {
                           RhFileLog.write("[join] watch round " + ++var1 + " start");
                           RhCashJoin.run(var0);
                           var2 = RhCashJoin.nextWakeDelay;
                        }
                     } catch (Throwable var5) {
                        RhFileLog.write("[join] watch err: " + var5);
                        var2 = 900000L;
                     }

                     RhFileLog.write("[join] next check in " + var2 / 60000L + "m" + var2 % 60000L / 1000L + "s");
                  } while (RhCashJoin.sleepOrDie(var2));
               }
            }
         }, "rh-cash-join").start();
      }
   }

   public static String run(Context var0) {
      synchronized (RUN_LOCK) {
         if (running) {
            RhFileLog.write("[join] run skipped: another round in progress");
            return "上一轮仍在执行，跳过";
         }

         running = true;
      }

      long var21 = System.currentTimeMillis();
      RhReport var3 = new RhReport();
      var3.append("\ud83e\ude99 现金瓜分自动投币 @").append(var21).append('\n');

      String var5;
      try {
         return doRun(var0, var3, var21);
      } catch (Throwable var18) {
         var3.append("[!] 异常: ").append(var18).append('\n');
         nextWakeDelay = 900000L;
         finish(var0, var3, var21);
         var5 = var3.toString();
      } finally {
         synchronized (RUN_LOCK) {
            running = false;
         }
      }

      return var5;
   }

   private static String doRun(Context var0, RhReport var1, long var2) {
      JSONObject var4 = readState(var0);
      if (isTerminalToday(var4)) {
         var1.append("[0] 今日已终止（state=").append(var4.optString("state")).append("），0 点前不再执行\n");
         nextWakeDelay = delayToNextOpen();
         finish(var0, var1, var2);
         return var1.toString();
      } else {
         JSONObject var5 = RhCashClaim.callMtop(var0, "mtop.taobao.idle.cash.detail", "1.0", "{\"version\":\"v1.1\"}", null, null, 8000L);
         if (var5 != null && hasSuccess(var5)) {
            JSONObject var33 = var5.optJSONObject("data");
            if (var33 == null) {
               var1.append("[1] detail 无 data，中止本轮\n");
               bumpTryAndMaybeAbort(var0, var4, var1, "detail:nodata");
               finish(var0, var1, var2);
               return var1.toString();
            } else {
               JSONObject var7 = var33.optJSONObject("cashDetailVO");
               JSONObject var8 = var33.optJSONObject("userJoinStatus");
               if (var7 != null && var8 != null) {
                  String var9 = var7.optString("actId");
                  String var10 = var7.optString("outShowActId");
                  int var11 = optInt(var33.optString("maxCoinJoinedCount"), 0);
                  int var12 = optInt(var33.optString("joinedCount"), 0);
                  int var13 = optInt(var7.optString("costCoinNum"), 500);
                  long var14 = optLong(var7.optString("joinBeginTime"));
                  long var16 = optLong(var7.optString("joinEndTime"));
                  boolean var18 = "true".equalsIgnoreCase(var8.optString("canCoinJoin"));
                  boolean var19 = "true".equalsIgnoreCase(var8.optString("isBindZhifubao"));
                  boolean var20 = "true".equalsIgnoreCase(var8.optString("isAlreadyJoin"));
                  var1.append("[1] 第 ")
                     .append(var10)
                     .append(" 期 actId=")
                     .append(var9)
                     .append(" 已投 ")
                     .append(var12)
                     .append("/")
                     .append(var11)
                     .append(" 单份 ")
                     .append(var13)
                     .append(" 币")
                     .append(" canCoinJoin=")
                     .append(var18)
                     .append(" isAlreadyJoin=")
                     .append(var20)
                     .append('\n');
                  var1.append("     参与窗口 ").append(fmt(var14)).append(" ~ ").append(fmt(var16)).append('\n');
                  long var21 = System.currentTimeMillis();
                  if (var14 > 0L && var21 < var14) {
                     long var34 = var14 + 30000L - var21;
                     nextWakeDelay = clamp(var34, 60000L, 1800000L);
                     var1.append("[2] 未到参与窗口，").append(nextWakeDelay / 1000L).append("s 后再试（不占重试次数）\n");
                     finish(var0, var1, var2);
                     return var1.toString();
                  } else if (var16 > 0L && var21 > var16) {
                     markTerminal(var0, var4, "abort_window", var9, 0, var12, var12, "", "join window closed");
                     var1.append("[2] 本期参与已截止（").append(fmt(var16)).append("），当日不再执行\n");
                     nextWakeDelay = delayToNextOpen();
                     finish(var0, var1, var2);
                     return var1.toString();
                  } else if (var12 >= var11) {
                     markTerminal(var0, var4, "done_server", var9, 0, var12, var12, "", "already full");
                     var1.append("[3] 本期已投满 ").append(var12).append("/").append(var11).append("，落盘 done_server，当日不再执行\n");
                     nextWakeDelay = delayToNextOpen();
                     finish(var0, var1, var2);
                     return var1.toString();
                  } else {
                     int var23 = var11 - var12;
                     var1.append("[3] 还差 ").append(var23).append(" 份投满\n");
                     if (!var19) {
                        markTerminal(var0, var4, "abort_alipay", var9, 0, var12, var12, "", "zhifubao not bound");
                        var1.append("[4] 未绑定支付宝，join 必被拒，当日不再执行\n");
                        nextWakeDelay = delayToNextOpen();
                        finish(var0, var1, var2);
                        return var1.toString();
                     } else if (!var18) {
                        markTerminal(var0, var4, "abort_nocoin", var9, 0, var12, var12, "", "canCoinJoin=false");
                        var1.append("[4] canCoinJoin=false（未投满但已不可投），当日不再执行\n");
                        nextWakeDelay = delayToNextOpen();
                        finish(var0, var1, var2);
                        return var1.toString();
                     } else {
                        int var24 = var23;
                        JSONObject var25 = RhCashClaim.callMtop(var0, "mtop.taobao.idle.coin.activity.user.info", "1.0", "{}", null, null, 8000L);
                        if (var25 != null && hasSuccess(var25)) {
                           JSONObject var26 = var25.optJSONObject("data");
                           int var27 = var26 == null ? -1 : optInt(var26.optString("myIdleCoin"), -1);
                           if (var27 >= 0) {
                              int var28 = var13 > 0 ? var27 / var13 : var23;
                              if (var28 <= 0) {
                                 markTerminal(var0, var4, "abort_balance", var9, 0, var12, var12, "", "balance=" + var27);
                                 var1.append("[5] 余额不足：有 ").append(var27).append(" 币，需 ").append(var13).append(" 币起投，当日不再执行\n");
                                 nextWakeDelay = delayToNextOpen();
                                 finish(var0, var1, var2);
                                 return var1.toString();
                              }

                              if (var28 < var23) {
                                 var24 = var28;
                                 var1.append("[5] 余额 ").append(var27).append(" 只够 ").append(var28).append(" 份（目标 ").append(var23).append(" 份）\n");
                              } else {
                                 var1.append("[5] 余额 ").append(var27).append(" 足够（需 ").append(var23 * var13).append("）\n");
                              }
                           }
                        } else {
                           var1.append("[5] 余额查询失败，按目标份数直接提交（服务端会二次校验）\n");
                        }

                        JSONObject var35 = new JSONObject();

                        try {
                           var35.put("actId", var9);
                           var35.put("asac", "2A21419W4V75OCRAXF3VZB");
                           var35.put("betNum", var24);
                           var35.put("version", "v1.1");
                           var35.put("couponIdListStr", "");
                        } catch (Throwable var32) {
                        }

                        var1.append("[6] 提交 join betNum=").append(var24).append('\n');
                        JSONObject var36 = RhCashClaim.callMtop(var0, "mtop.taobao.idle.cash.join", "1.0", var35.toString(), null, null, 15000L);
                        String var37 = briefRet(var36);
                        int var29 = 0;
                        if (var36 != null) {
                           JSONObject var30 = var36.optJSONObject("data");
                           if (var30 != null) {
                              JSONArray var31 = var30.optJSONArray("ids");
                              if (var31 != null) {
                                 var29 = var31.length();
                              }
                           }
                        }

                        var1.append("     返回: ").append(var37).append(" ids=").append(var29).append('\n');
                        if (var36 != null && hasSuccess(var36) && var29 == var24) {
                           int var40 = verifyJoined(var0, var12);
                           var1.append("[7] 复核 joinedCount ").append(var12).append(" → ").append(var40 < 0 ? "?" : String.valueOf(var40)).append('\n');
                           markTerminal(var0, var4, "done_local", var9, var24, var12, var40 < 0 ? var12 + var24 : var40, idsOf(var36), var37);
                           var1.append("[7] ✓ 投满成功，落盘 done_local，当日不再执行\n");
                           nextWakeDelay = delayToNextOpen();
                           finish(var0, var1, var2);
                           return var1.toString();
                        } else if (var36 != null && var37.contains("FAIL_BIZ_")) {
                           int var39 = verifyJoined(var0, var12);
                           if (var39 > var12) {
                              markTerminal(var0, var4, "done_local", var9, var39 - var12, var12, var39, idsOf(var36), var37 + " (partial)");
                              var1.append("[7] 业务拒绝但账本已增（").append(var12).append("→").append(var39).append("），按已投记 done_local\n");
                              nextWakeDelay = delayToNextOpen();
                              finish(var0, var1, var2);
                              return var1.toString();
                           } else {
                              markTerminal(var0, var4, "abort_biz", var9, 0, var12, var39 < 0 ? var12 : var39, "", var37);
                              var1.append("[7] ✗ 业务拒绝，不重试，当日不再执行\n");
                              nextWakeDelay = delayToNextOpen();
                              finish(var0, var1, var2);
                              return var1.toString();
                           }
                        } else {
                           int var38 = verifyJoined(var0, var12);
                           var1.append("[7] 复核 joinedCount ").append(var12).append(" → ").append(var38 < 0 ? "?" : String.valueOf(var38)).append('\n');
                           if (var38 > var12) {
                              markTerminal(var0, var4, "done_local", var9, var38 - var12, var12, var38, "", var37 + " (resp lost)");
                              var1.append("[7] ✓ 响应丢了但账本已增，判成功，当日不再执行\n");
                              nextWakeDelay = delayToNextOpen();
                              finish(var0, var1, var2);
                              return var1.toString();
                           } else {
                              bumpTryAndMaybeAbort(var0, var4, var1, var37);
                              finish(var0, var1, var2);
                              return var1.toString();
                           }
                        }
                     }
                  }
               } else {
                  var1.append("[1] detail 结构异常（缺 cashDetailVO / userJoinStatus）\n");
                  bumpTryAndMaybeAbort(var0, var4, var1, "detail:shape");
                  finish(var0, var1, var2);
                  return var1.toString();
               }
            }
         } else {
            String var6 = briefRet(var5);
            var1.append("[1] detail 失败: ").append(var6).append("（fail-closed，不投）\n");
            bumpTryAndMaybeAbort(var0, var4, var1, "detail:" + var6);
            finish(var0, var1, var2);
            return var1.toString();
         }
      }
   }

   private static void bumpTryAndMaybeAbort(Context var0, JSONObject var1, RhReport var2, String var3) {
      int var4 = var1.optInt("try", 0) + 1;
      String var5 = var1.optString("actId", "");
      if (var4 >= 3) {
         markTerminal(var0, var1, "abort_retry", var5, 0, var1.optInt("joinedBefore", 0), var1.optInt("joinedAfter", 0), "", var3 + " try=" + var4);
         var2.append("[!] 已失败 ").append(var4).append(" 次，当日不再执行\n");
         nextWakeDelay = delayToNextOpen();
      } else {
         var1 = stateSkeleton(var1);

         try {
            var1.put("state", "retry_pending");
            var1.put("try", var4);
            var1.put("lastRet", var3);
            var1.put("at", System.currentTimeMillis());
         } catch (Throwable var7) {
         }

         writeState(var0, var1);
         nextWakeDelay = 60000L;
         var2.append("[!] 第 ").append(var4).append("/").append(3).append(" 次失败，").append(60L).append("s 后重试\n");
      }
   }

   private static int verifyJoined(Context var0, int var1) {
      sleep(1200L);
      JSONObject var2 = RhCashClaim.callMtop(var0, "mtop.taobao.idle.cash.detail", "1.0", "{\"version\":\"v1.1\"}", null, null, 8000L);
      if (var2 != null && hasSuccess(var2)) {
         JSONObject var3 = var2.optJSONObject("data");
         return var3 == null ? -1 : optInt(var3.optString("joinedCount"), -1);
      } else {
         return -1;
      }
   }

   private static JSONObject readState(Context var0) {
      try {
         File var1 = new File(stateDir(var0), "rh_cash_join.txt");
         // 老版本把状态写 cache/：持久区没有时回落读一次，免得升级当天状态丢
         if (!var1.exists()) {
            var1 = new File(dir(var0), "rh_cash_join.txt");
         }
         if (!var1.exists()) {
            return null;
         } else {
            byte[] var2 = new byte[(int)var1.length()];
            FileInputStream var3 = new FileInputStream(var1);
            int var4 = 0;

            while (var4 < var2.length) {
               int var5 = var3.read(var2, var4, var2.length - var4);
               if (var5 <= 0) {
                  break;
               }

               var4 += var5;
            }

            var3.close();
            return new JSONObject(new String(var2, 0, var4, "UTF-8"));
         }
      } catch (Throwable var6) {
         return null;
      }
   }

   static boolean isTerminalToday(JSONObject var0) {
      if (var0 == null) {
         return false;
      } else if (!today().equals(var0.optString("day", ""))) {
         return false;
      } else {
         String var1 = var0.optString("state", "");
         return var1.startsWith("done_") || var1.startsWith("abort_");
      }
   }

   private static JSONObject stateSkeleton(JSONObject var0) {
      JSONObject var1 = new JSONObject();

      try {
         var1.put("day", today());
         var1.put("actId", var0 == null ? "" : var0.optString("actId", ""));
         var1.put("state", var0 == null ? "" : var0.optString("state", ""));
         var1.put("betNum", var0 == null ? 0 : var0.optInt("betNum", 0));
         var1.put("ids", var0 == null ? "" : var0.optString("ids", ""));
         var1.put("joinedBefore", var0 == null ? 0 : var0.optInt("joinedBefore", 0));
         var1.put("joinedAfter", var0 == null ? 0 : var0.optInt("joinedAfter", 0));
         var1.put("try", var0 == null ? 0 : var0.optInt("try", 0));
         var1.put("lastRet", var0 == null ? "" : var0.optString("lastRet", ""));
      } catch (Throwable var3) {
      }

      return var1;
   }

   private static void markTerminal(Context var0, JSONObject var1, String var2, String var3, int var4, int var5, int var6, String var7, String var8) {
      JSONObject var9 = stateSkeleton(var1);

      try {
         var9.put("day", today());
         var9.put("actId", var3);
         var9.put("state", var2);
         var9.put("betNum", var4);
         var9.put("ids", var7 == null ? "" : var7);
         var9.put("joinedBefore", var5);
         var9.put("joinedAfter", var6);
         var9.put("lastRet", var8 == null ? "" : var8);
         var9.put("at", System.currentTimeMillis());
      } catch (Throwable var11) {
      }

      writeState(var0, var9);
      RhFileLog.write("[join] TERMINAL " + var2 + " actId=" + var3 + " betNum=" + var4 + " joined=" + var5 + "->" + var6 + " ret=" + var8);
   }

   private static void writeState(Context var0, JSONObject var1) {
      try {
         File var2 = stateDir(var0);
         File var3 = new File(var2, "rh_cash_join.txt.tmp");
         FileOutputStream var4 = new FileOutputStream(var3, false);
         var4.write(var1.toString().getBytes("UTF-8"));
         var4.flush();
         var4.close();
         File var5 = new File(var2, "rh_cash_join.txt");
         if (var5.exists()) {
            var5.delete();
         }

         var3.renameTo(var5);
      } catch (Throwable var6) {
         RhFileLog.write("[join] writeState fail: " + var6);
      }
   }

   public static String stateText(Context var0) {
      JSONObject var1 = readState(var0);
      return var1 == null
         ? "NONE"
         : var1.optString("day")
            + "|"
            + var1.optString("state")
            + "|"
            + var1.optInt("joinedAfter")
            + "|"
            + var1.optInt("betNum")
            + "|"
            + var1.optString("actId")
            + "|"
            + var1.optInt("try");
   }

   private static String idsOf(JSONObject var0) {
      try {
         JSONArray var1 = var0.getJSONObject("data").getJSONArray("ids");
         StringBuilder var2 = new StringBuilder();

         for (int var3 = 0; var3 < var1.length(); var3++) {
            if (var3 > 0) {
               var2.append(',');
            }

            var2.append(var1.optString(var3));
         }

         return var2.toString();
      } catch (Throwable var4) {
         return "";
      }
   }

   private static long delayToNextOpen() {
      Calendar var0 = Calendar.getInstance();
      var0.add(5, 1);
      var0.set(11, 0);
      var0.set(12, 1);
      var0.set(13, 30);
      var0.set(14, 0);
      long var1 = var0.getTimeInMillis() - System.currentTimeMillis();
      return clamp(var1, 60000L, 1800000L);
   }

   private static long clamp(long var0, long var2, long var4) {
      if (var0 < var2) {
         return var2;
      } else {
         return var0 > var4 ? var4 : var0;
      }
   }

   static String today() {
      return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
   }

   /** 响应通道目录：必须与宿主写 rh_resp_<tail>.json 的目录一致（cache/）。 */
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

   /** 状态落盘目录：持久区优先，最后回落 cache（cache 会被装包/清缓存抹掉）。 */
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

   private static int optInt(String var0, int var1) {
      try {
         return Integer.parseInt(var0.trim());
      } catch (Throwable var3) {
         return var1;
      }
   }

   private static long optLong(String var0) {
      try {
         return Long.parseLong(var0.trim());
      } catch (Throwable var2) {
         return 0L;
      }
   }

   private static String fmt(long var0) {
      return var0 <= 0L ? "?" : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(var0));
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

      try {
         FileWriter var4 = new FileWriter(new File(stateDir(var0), "rh_tasks_join.txt"), false);
         var4.write(var1.toString());
         var4.close();
      } catch (Throwable var7) {
         RhFileLog.write("[join] write report fail: " + var7);
      }

      try {
         File var8 = var0.getExternalCacheDir();
         if (var8 != null && var8.exists()) {
            FileWriter var5 = new FileWriter(new File(var8, "rh_tasks_join.txt"), false);
            var5.write(var1.toString());
            var5.close();
         }
      } catch (Throwable var6) {
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
