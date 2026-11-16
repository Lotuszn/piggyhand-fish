package com.piggyhand.fish.task;

import android.content.Context;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;

import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

import static com.piggyhand.fish.task.JsonUtil.*;

/**
 * 币余额探针 + 余额台账（zhushou_*.log）——Phase 2 优化5 深拆簇3（2026-11-15）。
 * 从 FishTaskAuto 原样迁出：探针调度、90s±30% 自重排、引擎互斥、网关熔断、
 * 5 分钟去重落盘、48h 旧档清理，全部域内自洽。
 * 对 FishTaskAuto 的依赖（单向，无环）：engineRunning() 引擎互斥 + 借实例走 callApi 响应通道。
 * 日志文件名与行格式一字未动（zhushou_yyyy.MM.dd.log / "yyyy.MM.dd HH:mm:ss N"，compat 红线）。
 */
final class CoinLogger {

   private static final Object COIN_LOG_LOCK = new Object();
   private static volatile long coinLogLastVal = -1L;
   private static volatile long coinLogLastAt = 0L;
   private static String coinLogLastDay = "";
   private static String coinLogCleanedDay = "";

   private CoinLogger() {
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }

   static void startCoinBalanceLogger(final Context var0) {
      try {
         // 多进程守卫：仅主进程开探针（:push/:channel 等子进程不重复探）
         String var1 = (String) Class.forName("android.app.Application").getMethod("getProcessName").invoke(null);
         if (var1 != null && var1.contains(":")) {
            return;
         }
      } catch (Throwable var2) {
      }

      final ScheduledExecutorService var3 = Executors.newSingleThreadScheduledExecutor();
      // 节奏整形（2026-11-15）：固定 90s 周期是等间隔机器特征，改为每轮跑完按 90s±30% 自重排；
      // 引擎 RUNNING 期间探针静默跳过——跑批与探针两类流量不再叠加（周期互斥）。
      // v1.5.5 改进 B 背景：map.init 是三台用户日志里被网关秒拒最多的接口，高频直调是主要诱因。
      // 配合 coinBalanceSnapshot 内的连续拒绝熔断（3 次停 10 分钟）。
      Runnable probe = new Runnable() {
         @Override
         public void run() {
            try {
               if (FishTaskAuto.engineRunning()) {
                  log("[coinlog] engine running, probe skipped this round");
               } else {
                  coinBalanceSnapshot(var0);
               }
            } catch (Throwable var2) {
            }
            long nextMs = (long) (90000L * (0.7D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.6D));
            var3.schedule(this, nextMs, TimeUnit.MILLISECONDS);
         }
      };
      var3.schedule(probe, 20L * 1000L, TimeUnit.MILLISECONDS);
      log("[coinlog] balance logger armed (90s±30% jitter, engine-mutex, first in 20s)");
   }

   /** v1.5.5 改进 B：币余额探针熔断——连续 3 次网关拒绝（GATEWAY_REJECT）后停探。
    *  v1.5.71 P1-B: 固定 10min 改阶梯退避——同一波拒绝潮里越退越深（10/30/60min），
    *  晚间 00~01/19~23 拒绝潮实测可持续数小时，固定 10min 只会反复撞墙扩大风控窗口。
    *  网络断连（NETWORK_LOST，v1.5.71 P1-C 新归因）同样计入熔断——包没到，探针继续打也是空打。
    *  静态：logger 与跑批共用同一判定；SUCCESS 清零（等级也清零，恢复后从头爬阶梯）。 */
   private static final java.util.concurrent.atomic.AtomicInteger GW_FAIL_STREAK =
           new java.util.concurrent.atomic.AtomicInteger(0);
   private static volatile long gwBlockedUntil = 0L;
   /** v1.5.71 P1-B: 退避等级 0/1/2 -> 10/30/60min；封顶 60min 直到出现一次成功 */
   private static final java.util.concurrent.atomic.AtomicInteger GW_BACKOFF_LVL =
           new java.util.concurrent.atomic.AtomicInteger(0);

   static void coinBalanceSnapshot(Context var0) {
      // v1.5.71 P1-B: 高发失败窗（00~01 点、19~23 点，三份用户日志互证）按 65% 概率跳轮——
      // 等效把该时段探针频率降到约 1/3，不动调度器结构（90s±30% 原样，compat 红线外）
      try {
         java.util.Calendar varCal = java.util.Calendar.getInstance();
         int varH = varCal.get(java.util.Calendar.HOUR_OF_DAY);
         if ((varH <= 1 || varH >= 19)
                 && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < 65) {
            return;
         }
      } catch (Throwable ignored) {
      }

      // v1.5.5 改进 B：熔断窗内直接跳过本轮探针（不打请求、不占 30s 窗口）
      long varNow = System.currentTimeMillis();
      if (varNow < gwBlockedUntil) {
         return;
      }

      try {
         ClassLoader var1 = var0.getClassLoader();
         String var2 = FishTaskAuto.cacheDirPath(var0);
         // 借 FishTaskAuto 实例走响应通道（callApi 含节奏整形 + api 级互斥，与跑批同规矩）
         FishTaskAuto var3 = new FishTaskAuto(var1, var2);
         JSONObject var4 = var3.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "币余额");
         // 改进 B + P1-B: 连续 3 次拒绝（网关拒绝或连接层失败）→ 按阶梯等级停探 10/30/60min
         String varRet0 = firstRet(var4);
         if (varRet0 != null
                 && (varRet0.contains("GATEWAY_REJECT") || varRet0.contains("NETWORK_LOST"))) {
            int varStreak = GW_FAIL_STREAK.incrementAndGet();
            if (varStreak >= 3) {
               int varLvl = GW_BACKOFF_LVL.get();
               long varPauseMin = varLvl == 0 ? 10L : (varLvl == 1 ? 30L : 60L);
               gwBlockedUntil = System.currentTimeMillis() + varPauseMin * 60000L;
               GW_FAIL_STREAK.set(0);
               if (varLvl < 2) GW_BACKOFF_LVL.incrementAndGet(); // 已到 60min 档就钉死，成功才回零
               String varKind = varRet0.contains("NETWORK_LOST") ? "network-lost" : "gateway-reject";
               log("[coinlog] " + varKind + " x3, balance probe paused " + varPauseMin
                       + "min (backoff lvl " + (varLvl + 1) + ")");
            }
            return;
         }
         GW_FAIL_STREAK.set(0);
         GW_BACKOFF_LVL.set(0); // v1.5.71 P1-B: 成功即回零，下次拒绝潮从 10min 重新爬
         JSONObject var5 = var4 == null ? null : var4.optJSONObject("data");
         if (var5 == null) {
            return;
         }

         long var6 = parseCoinNum(var5.optString("coinNum", ""));
         if (var6 <= 0L) {
            JSONObject var8 = var5.optJSONObject("userInfo");
            if (var8 != null) {
               var6 = parseCoinNum(var8.optString("coinNum", ""));
            }
         }

         if (var6 > 0L) {
            recordCoinBalance(var0, var6);
         }
      } catch (Throwable var9) {
         log("[coinlog] snapshot error: " + var9);
      }
   }

   static void recordCoinBalance(Context var0, long var1) {
      if (var1 > 0L) {
         try {
            synchronized (COIN_LOG_LOCK) {
               long var4 = System.currentTimeMillis();
               SimpleDateFormat var6 = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
               String var7 = var6.format(new Date(var4));
               boolean var8 = !var7.equals(coinLogLastDay);
               // 5 分钟去重：同值 + 短窗 + 未跨天 不落盘（防 zhushou_*.log 膨胀）
               if (var1 == coinLogLastVal && var4 - coinLogLastAt < 300000L && !var8) {
                  return;
               }

               File var9 = var0.getExternalFilesDir(null);
               String var10 = var9 != null ? var9.getAbsolutePath() : ConfigManager.fishExternal("files");
               File var11 = new File(var10);
               if (!var11.exists()) {
                  var11.mkdirs();
               }

               File var12 = new File(var11, "zhushou_" + var7 + ".log");
               FileOutputStream var13 = new FileOutputStream(var12, true);
               String var14 = var7 + " " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(var4)) + " " + var1 + "\n";
               var13.write(var14.getBytes("UTF-8"));
               var13.close();
               coinLogLastVal = var1;
               coinLogLastAt = var4;
               coinLogLastDay = var7;
               log("[coinlog] " + var14.trim());
               if (!var7.equals(coinLogCleanedDay)) {
                  coinLogCleanedDay = var7;
                  cleanOldCoinLogs(var11, var6);
               }
            }
         } catch (Throwable var17) {
            log("[coinlog] record error: " + var17);
         }
      }
   }

   /** 48h 前的 zhushou_*.log 清理（跨天首次落盘时触发一次） */
   private static void cleanOldCoinLogs(File var0, SimpleDateFormat var1) {
      try {
         File[] var2 = var0.listFiles();
         if (var2 == null) {
            return;
         }

         String var3 = var1.format(new Date(System.currentTimeMillis() - 172800000L));
         Date var4 = var1.parse(var3);

         for (File var8 : var2) {
            String var9 = var8.getName();
            if (var9.startsWith("zhushou_") && var9.endsWith(".log")) {
               try {
                  Date var10 = var1.parse(var9.substring(8, var9.length() - 4));
                  if (var10 != null && var10.before(var4)) {
                     var8.delete();
                  }
               } catch (Throwable var11) {
               }
            }
         }
      } catch (Throwable var12) {
         log("[coinlog] clean error: " + var12);
      }
   }
}
