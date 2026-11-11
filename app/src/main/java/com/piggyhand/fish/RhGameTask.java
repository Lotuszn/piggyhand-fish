package com.piggyhand.fish;

import android.app.Application;
import android.content.Context;
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

public final class RhGameTask {
   static final String API_CENTER = "mtop.taobao.idle.mini.game.task.center.query";
   static final String API_DETAIL = "mtop.taobao.idle.mini.game.task.detail.query";
   static final String API_COMPLETE = "mtop.taobao.idle.mini.game.task.complete";
   static final String API_AWARD = "mtop.taobao.idle.mini.game.task.award";
   static final String REPORT_FILE = "rh_tasks_game.txt";
   static final String DONE_FILE = "rh_game_done.txt";
   static final String TRY_FILE = "rh_game_try.txt";
   static final int MAX_AUTO_TRY = 3;
   static final long WAIT_CENTER = 8000L;
   static final long WAIT_NORMAL = 6000L;

   private RhGameTask() {
   }

   public static void autoRunDaily(final Context var0) {
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
            var2 = var3 == null || var3.gameTask;
         } catch (Throwable var5) {
            var2 = true;
         }

         if (!var2) {
            RhFileLog.write("[game] auto-run disabled by config (gameTask=false), skip");
            return;
         }

         if (isDoneToday(var0)) {
            RhFileLog.write("[game] auto-run skip: already done today");
            return;
         }

         final int var7 = bumpTry(var0);
         if (var7 > 3) {
            RhFileLog.write("[game] auto-run give up today, tries=" + var7 + " (手动点击仍可执行)");
            return;
         }

         RhFileLog.write("[game] auto-run scheduled in 50s (try " + var7 + "/3)");
         new Thread(new Runnable() {
            @Override
            public void run() {
               try {
                  Thread.sleep(50000L);
                  String var1 = RhGameTask.run(var0);
                  RhFileLog.write("[game] auto-run done(try " + var7 + "): " + (var1 == null ? "null" : var1.replace('\n', ' ')));
                  if (RhGameTask.isDoneToday(var0)) {
                     RhFileLog.write("[game] auto-run completed, done mark valid");
                  } else {
                     RhFileLog.write("[game] auto-run incomplete, will retry on next launch");
                  }
               } catch (Throwable var2) {
                  RhFileLog.write("[game] auto-run fail: " + var2);
               }
            }
         }, "rh-game-auto").start();
      } catch (Throwable var6) {
         RhFileLog.write("[game] autoRunDaily err: " + var6);
      }
   }

   public static String run(Context var0) {
      long var1 = System.currentTimeMillis();
      RhReport var3 = new RhReport();
      var3.append("\ud83c\udfae 游戏任务报告 @").append(var1).append('\n');
      int var4 = 0;
      int var5 = 0;
      int var6 = 0;
      int var7 = 0;
      int var8 = 0;
      String var9 = RhPondTask.callAndWait(var0, "mtop.taobao.idle.mini.game.task.center.query", "1.0", "{}", "query", 8000L);
      JSONObject var10 = parse(var9);
      if (var10 != null && hasSuccess(var10)) {
         JSONArray var11;
         try {
            var11 = var10.getJSONObject("data").getJSONArray("games");
         } catch (Throwable var31) {
            var3.append("[1] 清单结构异常: ").append(var31).append('\n');
            finish(var0, var3, var1);
            return var3.toString();
         }

         ArrayList var12 = new ArrayList();
         // v1.5.2fix：AWARD 单独成表。旧实现只收 ACCEPTED，把「已转 AWARD 待领奖」的任务
         // 直接丢掉（列表页显示"无可做"，领奖永远不发）—— 这正是"两项未领奖"的根因。
         ArrayList var12a = new ArrayList();
         int var13 = 0;

         for (int var14 = 0; var14 < var11.length(); var14++) {
            JSONObject var15 = var11.optJSONObject(var14);
            if (var15 != null) {
               JSONObject var16 = var15.optJSONObject("task");
               if (var16 != null && var16.optString("taskId").length() != 0) {
                  var13++;
                  String varSt = var16.optString("taskStatus");
                  if ("ACCEPTED".equals(varSt)) {
                     var12.add(var15);
                  } else if ("AWARD".equals(varSt)) {
                     var12a.add(var15);
                  }
               }
            }
         }

         var3.append("[1] 清单: ").append(var11.length()).append(" 个游戏 / ").append(var13)
            .append(" 个任务 / 可做").append(var12.size())
            .append(" / 待领奖").append(var12a.size()).append('\n');

         /* 待领奖先用列表状态直接收割：center.query 已带 taskStatus=AWARD 与 appkey，
            不必再走 detail/complete（那两步对 AWARD 只会返回"已是 AWARD，跳过"）。*/
         if (!var12a.isEmpty()) {
            for (int varA = 0; varA < var12a.size(); varA++) {
               JSONObject varAg = (JSONObject)var12a.get(varA);
               JSONObject varAt = varAg.optJSONObject("task");
               String varAtId = varAt.optString("taskId");
               String varAtKey = varAg.optString("appkey");
               String varAtTitle = varAg.optString("title");
               String varAtAmt = varAt.optString("rewardAmount", "0");
               var3.append("[1.5] ").append(varAtTitle).append(" +").append(varAtAmt).append(" 币\n");
               int[] varAr = awardGame(var0, varAtId, varAtKey);
               if (varAr[0] > 0) {
                  var6++;
                  var8 += varAr[1];
                  var3.append("     ✓ 领取成功 +").append(varAr[1]).append(" 闲鱼币\n");
               } else {
                  var7++;
                  var3.append("     ✗ 领奖失败\n");
               }
               sleep(900L);
            }
         }

         if (var12.isEmpty() && var12a.isEmpty()) {
            var3.append("[1] 无可做任务（可能今日已全部完成，或已领完）\n");
            finish(var0, var3, var1);
            return var3.toString();
         } else {
            for (int var36 = 0; var36 < var12.size(); var36++) {
               JSONObject var38 = (JSONObject)var12.get(var36);
               JSONObject var40 = var38.optJSONObject("task");
               String var17 = var38.optString("appkey");
               String var18 = var38.optString("title");
               String var19 = var40.optString("taskId");
               String var20 = var40.optString("taskType");
               String var21 = var40.optString("taskAction");
               String var22 = var40.optString("rewardAmount", "0");
               int var23 = parseInt(var40.optString("taskValue"), 1);
               var3.append("[2] ").append(var18).append(" (").append(var20).append(' ').append(var23).append(") +").append(var22).append(" 币\n");

                  try {
                     JSONObject var24 = new JSONObject();
                     var24.put("taskId", var19);
                     var24.put("appkey", var17);
                     String varDetailRaw = RhPondTask.callAndWait(var0, "mtop.taobao.idle.mini.game.task.detail.query", "1.0", var24.toString(), "query", 6000L);
                     JSONObject var25 = parse(varDetailRaw);
                     if (var25 == null || !hasSuccess(var25)) {
                        var3.append("     · 详情查询失败，跳过 ").append(briefRet(var25)).append('\n');
                        if (var25 == null && varDetailRaw != null) {
                           var3.append("       响应原文: ").append(snippet(varDetailRaw, 100)).append('\n');
                        }
                        continue;
                     }

                  String var26 = "";

                  try {
                     var26 = var25.getJSONObject("data").optString("taskStatus");
                  } catch (Throwable var30) {
                  }

                  if ("COMPLETE".equals(var26) || "AWARD".equals(var26)) {
                     var3.append("     · 已是 ").append(var26).append("，跳过\n");
                     continue;
                  }
               } catch (Throwable var35) {
                  var3.append("     ! 详情异常 ").append(var35).append('\n');
                  continue;
               }

               sleep(500L);

               String var46;
               try {
                  JSONObject var47 = new JSONObject();
                  var47.put("taskId", var19);
                  var47.put("appkey", var17);
                  var47.put("reportId", uuid());
                  if ("action".equals(var20)) {
                     var47.put("action", var21);
                     var47.put("payload", "{\"value\":" + var23 + "}");
                  } else {
                     var47.put("progressValue", var23);
                  }

                  String varCompleteRaw = RhPondTask.callAndWait(var0, "mtop.taobao.idle.mini.game.task.complete", "1.0", var47.toString(), "complete", 6000L);
                  JSONObject var49 = parse(varCompleteRaw);
                  if (var49 == null || !hasSuccess(var49)) {
                     var5++;
                     var3.append("     ✗ 上报失败 ").append(briefRet(var49)).append('\n');
                     if (var49 == null && varCompleteRaw != null) {
                        var3.append("       响应原文: ").append(snippet(varCompleteRaw, 100)).append('\n');
                     }
                     sleep(1000L);
                     continue;
                  }

                  JSONObject var27 = var49.optJSONObject("data");
                  boolean var28 = var27 != null && var27.optBoolean("completed", false);
                  var46 = var27 == null ? "" : var27.optString("taskStatus");
                  if (!var28) {
                     var5++;
                     var3.append("     ✗ 未判定完成 current=")
                        .append(var27 == null ? "?" : var27.optString("currentValue"))
                        .append(" target=")
                        .append(var27 == null ? "?" : var27.optString("targetValue"))
                        .append('\n');
                     sleep(1000L);
                     continue;
                  }

                  var4++;
                  var3.append("     ✓ 上报完成 → ").append(var46).append('\n');
               } catch (Throwable var34) {
                  var5++;
                  var3.append("     ! 上报异常 ").append(var34).append('\n');
                  sleep(1000L);
                  continue;
               }

               sleep(800L);
               if (!"AWARD".equals(var46)) {
                  var3.append("     · 完成即发，无需领奖\n");
               } else {
                  try {
                     JSONObject var48 = new JSONObject();
                     var48.put("taskId", var19);
                     var48.put("appkey", var17);
                     // 多用户：asac 走本机动态捕获，无捕获省略字段
                     String varAsac = RhLocationContext.asac();
                     if (varAsac != null) var48.put("asac", varAsac);
                     String varAwardRaw = RhPondTask.callAndWait(var0, "mtop.taobao.idle.mini.game.task.award", "1.0", var48.toString(), "award", 6000L);
                     JSONObject var50 = parse(varAwardRaw);
                     if (var50 != null && hasSuccess(var50)) {
                        JSONObject var51 = var50.optJSONObject("data");
                        boolean var52 = var51 != null && var51.optBoolean("awarded", false);
                        if (var52) {
                           var6++;
                           int var29 = parseInt(var51.optString("rewardAmount"), 0);
                           var8 += var29;
                           var3.append("     ✓ 领取成功 +").append(var29).append(' ').append(var51.optString("rewardUnit")).append('\n');
                        } else {
                           var7++;
                           var3.append("     ✗ 未发放 ").append(var51 == null ? "?" : var51.toString()).append('\n');
                        }
                     } else {
                        var7++;
                        var3.append("     ✗ 领奖失败 ").append(briefRet(var50)).append('\n');
                        if (var50 == null && varAwardRaw != null) {
                           var3.append("       响应原文: ").append(snippet(varAwardRaw, 100)).append('\n');
                        }
                     }
                  } catch (Throwable var33) {
                     var7++;
                     var3.append("     ! 领奖异常 ").append(var33).append('\n');
                  }

                  sleep(900L);
               }
            }

            var3.append("[2] 小计: 上报 成功")
               .append(var4)
               .append("/失败")
               .append(var5)
               .append(" · 领奖 成功")
               .append(var6)
               .append("/失败")
               .append(var7)
               .append(" · 共 +")
               .append(var8)
               .append(" 闲鱼币\n");
            sleep(1200L);
            String var37 = RhPondTask.callAndWait(var0, "mtop.taobao.idle.mini.game.task.center.query", "1.0", "{}", "query", 8000L);
            JSONObject var39 = parse(var37);
            int var41 = -1;

            try {
               JSONArray var42 = var39.getJSONObject("data").getJSONArray("games");
               int var43 = 0;

               for (int var44 = 0; var44 < var42.length(); var44++) {
                  JSONObject var45 = var42.optJSONObject(var44) == null ? null : var42.optJSONObject(var44).optJSONObject("task");
                  if (var45 != null) {
                     String varSt2 = var45.optString("taskStatus");
                     // AWARD 也算待做：领奖失败时下一轮还要再来一次
                     if ("ACCEPTED".equals(varSt2) || "AWARD".equals(varSt2)) {
                        var43++;
                     }
                  }
               }

               var41 = var43;
            } catch (Throwable var32) {
            }

            if (var41 == 0) {
               markDoneToday(var0);
               var3.append("[3] 复核: 0 个待做 → 打完成标记，今晚 12 点前不再自动执行\n");
            } else if (var41 > 0) {
               var3.append("[3] 复核: ").append(var41).append(" 个仍待做（可再点一次重试）\n");
            } else {
               var3.append("[3] 复核: 查询失败，未打标记（下次仍会重试）\n");
            }

            finish(var0, var3, var1);
            return var3.toString();
         }
      } else {
         var3.append("[1] 任务清单拉取失败 ").append(briefRet(var10)).append('\n');
         // v1.5.1 非 JSON 响应原文带回（网关拒绝原因定性）：callAndWait 返回 raw 时 parse 必为 null
         if (var10 == null && var9 != null) {
            var3.append("    响应原文: ").append(snippet(var9, 120)).append('\n');
         }
         finish(var0, var3, var1);
         return var3.toString();
      }
   }

   /**
    * 直接领奖（task.award）：返回 {是否成功, 实发币数}。
    * 2026-11-11 米9 实测：{taskId,appkey} 两个字段即可，服务端返回
    * data.awarded=true / rewardAmount / rewardUnit=IDLE_COIN。
    */
   private static int[] awardGame(Context var0, String varTaskId, String varAppkey) {
      int[] varR = new int[]{0, 0};
      try {
         JSONObject var1 = new JSONObject();
         var1.put("taskId", varTaskId);
         var1.put("appkey", varAppkey);
         // 多用户：asac 走本机动态捕获，无捕获省略字段
         String varAsac = RhLocationContext.asac();
         if (varAsac != null) var1.put("asac", varAsac);
         String varRaw = RhPondTask.callAndWait(var0, API_AWARD, "1.0", var1.toString(), "award", 6000L);
         JSONObject var2 = parse(varRaw);
         if (var2 == null || !hasSuccess(var2)) {
            RhFileLog.write("[game] award fail " + varTaskId + " " + briefRet(var2));
            return varR;
         }
         JSONObject var3 = var2.optJSONObject("data");
         if (var3 != null && var3.optBoolean("awarded", false)) {
            varR[0] = 1;
            varR[1] = parseInt(var3.optString("rewardAmount"), 0);
         }
      } catch (Throwable var4) {
         RhFileLog.write("[game] award err " + varTaskId + ": " + var4);
      }
      return varR;
   }

   public static boolean isDoneToday(Context var0) {
      try {
         File var1 = doneFile(var0);
         if (!var1.exists()) {
            return false;
         } else {
            String var2 = new String(Files.readAllBytes(var1.toPath()), "UTF-8").trim();
            return System.currentTimeMillis() < Long.parseLong(var2);
         }
      } catch (Throwable var3) {
         return false;
      }
   }

   private static void markDoneToday(Context var0) {
      try {
         long var1 = nextMidnight();
         File var3 = doneFile(var0);
         File var4 = new File(var3.getParentFile(), "rh_game_done.txt.tmp");
         FileWriter var5 = new FileWriter(var4, false);
         var5.write(String.valueOf(var1));
         var5.flush();
         var5.close();
         if (var3.exists()) {
            var3.delete();
         }

         if (!var4.renameTo(var3)) {
            FileWriter var6 = new FileWriter(var3, false);
            var6.write(String.valueOf(var1));
            var6.flush();
            var6.close();
         }

         RhFileLog.write("[game] DONE mark set, valid until " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(var1)));
      } catch (Throwable var7) {
         RhFileLog.write("[game] markDone fail: " + var7);
      }
   }

   private static int bumpTry(Context var0) {
      File var1 = new File(rhDir(var0), "rh_game_try.txt");
      String var2 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
      int var3 = 0;

      try {
         if (var1.exists()) {
            String var4 = new String(Files.readAllBytes(var1.toPath()), "UTF-8").trim();
            int var5 = var4.indexOf(32);
            if (var5 > 0 && var2.equals(var4.substring(0, var5))) {
               var3 = Integer.parseInt(var4.substring(var5 + 1).trim());
            }
         }
      } catch (Throwable var7) {
      }

      var3++;

      try {
         FileWriter var9 = new FileWriter(var1, false);
         var9.write(var2 + " " + var3);
         var9.close();
      } catch (Throwable var6) {
         RhFileLog.write("[game] bumpTry write fail: " + var6);
      }

      return var3;
   }

   private static File doneFile(Context var0) {
      return new File(rhDir(var0), "rh_game_done.txt");
   }

   private static File rhDir(Context var0) {
      File var1 = null;

      try {
         var1 = var0.getFilesDir();
      } catch (Throwable var3) {
      }

      return var1 != null ? var1 : new File("/data/data/com.taobao.idlefish/files");
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

   private static String uuid() {
      UUID var0 = UUID.randomUUID();
      return var0.toString();
   }

   private static int parseInt(String var0, int var1) {
      try {
         return Integer.parseInt(var0.trim());
      } catch (Throwable var3) {
         return var1;
      }
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

   /** v1.5.1 非 JSON 响应原文摘要：压平空白、截断（报告可读性） */
   private static String snippet(String raw, int max) {
      if (raw == null) return "";
      String one = raw.trim().replaceAll("\\s+", " ");
      if (one.length() > max) {
         return one.substring(0, max) + "...";
      }
      return one;
   }

   private static void finish(Context var0, RhReport var1, long var2) {
      var1.append("耗时 ").append((System.currentTimeMillis() - var2) / 1000L).append("s\n");
      boolean var4 = false;

      try {
         // 报告主写持久区（cache/ 会被装包与清缓存抹掉，二次读不到了）
         File var5 = new File(FishMtopHook.persistDir(var0), "rh_tasks_game.txt");
         if (var5.getParentFile() != null && !var5.getParentFile().exists()) {
            var5.getParentFile().mkdirs();
         }
         FileWriter var6 = new FileWriter(var5, false);
         var6.write(var1.toString());
         var6.close();
         var4 = true;
      } catch (Throwable var8) {
         RhFileLog.write("[game] write report(internal) fail: " + var8);
      }

      if (!var4) {
         RhFileLog.write("[game] REPORT NOT PERSISTED: rh_tasks_game.txt");
      }

      try {
         File var9 = var0.getExternalCacheDir();
         if (var9 != null && var9.exists()) {
            FileWriter var10 = new FileWriter(new File(var9, "rh_tasks_game.txt"), false);
            var10.write(var1.toString());
            var10.close();
         }
      } catch (Throwable var7) {
      }

      RhFileLog.write(var1.toString());
   }

   private static File rhCacheDir(Context var0) {
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

   private static void sleep(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }
   }
}
