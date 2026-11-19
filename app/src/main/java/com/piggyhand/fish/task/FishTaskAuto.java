package com.piggyhand.fish.task;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;
import com.piggyhand.fish.hook.FishMtopHook;
import com.piggyhand.fish.net.ApiDirect;
import com.piggyhand.fish.net.RhReport;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.widget.Toast;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

// JSON/数字解析纯函数已迁 JsonUtil（优化5簇2）；静态导入保持裸调用形态不变
import static com.piggyhand.fish.task.JsonUtil.*;

public final class FishTaskAuto {
   private static final String TASK_LIST_API = "mtop.taobao.idle.task.queryolivertasklist";
   private static final String TASK_BENEFIT_API = "mtop.taobao.idle.task.getolivertaskbenefit";
   private static final String TASK_TRIGGER_API = "mtop.taobao.idle.task.uploadolivertasktrigger";
   private static final String NEXT_FRESH_API = "mtop.taobao.idlehome.idle.coin.nextfresh";
   private static final String SEARCH_API = "mtop.taobao.idlemtopsearch.search";
   private static final String AD_QUERY_API = "mtop.taobao.idle.play.ad.query";
   private static final String AD_EVENT_API = "mtop.taobao.idle.play.ad.event.push";
   private static final String AD_EVENT_QUERY_API = "mtop.taobao.idle.play.ad.event.query";
   private static final String SIGNIN_API = "mtop.taobao.idle.coin.signin";
   private static final String PLAY_SIGN_API = "mtop.taobao.idle.play.sign.in";
   // 地图/圈数/背包/擦亮 API 常量与 cycleClaimOnly 已迁 MapTasks（优化5簇4）
   private static final String SHOP_BENEFIT_API = "mtop.taobao.idle.startup.shop.daily.benefit";
   private static final String LOTTERY_FEEDS_API = "mtop.taobao.idle.coin.lottery.feeds";
   private static final String LOTTERY_BET_API = "mtop.taobao.idle.coin.lottery.bet";
   private static final int REQ_TIMEOUT_S = 30;
   private static final int BROWSE_MAX_STEPS = 60;
   private final ClassLoader cl;
   /** 响应通道目录：宿主 FishMtopHook.writeCacheFile 落 rh_resp_*.json 的地方，必须是 getExternalCacheDir。
    *  只用来读写 rh_resp_*、rh_pref.json，不承载任何需要跨冷启动保留的东西。 */
   final String cacheDir;
   /** 报告目录：持久区（files/）。宿主的 cache/ 会被冷启动清空，报告必须住这里。 */
   private final String reportDir;
   private final Set<String> processed = new HashSet<>();
   final RhReport report = new RhReport();
   private volatile boolean abort = false;
   /** 触发毒化记忆：key=任务类型，value=连续 PARAM 类失败次数。
    *  实例字段而不是静态——FishTaskAuto 每次跑批新 new 一个，天然按轮清零，
    *  避免单例态跨轮残留把本来能跑的任务永久封锁（见 optimization 2026-11-12）。 */
   private final java.util.HashMap<String, Integer> triggerPoison = new java.util.HashMap<String, Integer>();
   /** 2026-11-15 实测补丁（本轮修）：主列表里判过「当日额度已满」的 deliveryId 台账。
    *  现场 06:33:22 —— 主循环 06:32:26 已经 [跳过] 89640，6 秒后的复核循环却只看
    *  idleTaskStatus=AWARD + needBenefit 又打了一发 benefit，回 RMB_CHECK，等于白送一次风控计数。
    *  复核 / 收尾 / claimOne 三条路径统一查这张表，额度满的任务一次都不再发。 */
   private final Set<String> quotaFullIds = ConcurrentHashMap.newKeySet();
   // map.init 60s 结果缓存（MAP_INIT_CACHE/MAP_INIT_TS）已迁 MapTasks（优化5簇4）
   private static Context appCtx;
   private static volatile boolean toastOn = true;
   private static volatile boolean polishOn = true;
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

   /** 引擎运行标记读接口（CoinLogger 探针互斥用；写仍由 launch/收尾内部执行） */
   static boolean engineRunning() {
      return RUNNING.get();
   }
   private static final long PROBE_INTERVAL_MS = 900000L;
   private static final long PROBE_BURST_MS = 300000L;
   private static final long PROBE_FAIL_MS = 180000L;
   /** ---- 风控节奏整形（v1.5.73 用户可配版）----
    *  五个中心值由模块 UI「延迟时间配置」无极滑块写入 config，范围：
    *    查询 800~8000 / 触发 800~10000 / 领取 800~12000 / 领取前延迟 2000~30000 / 抖动 10~60%
    *  配置里为 0（旧配置或从未打开过模块界面）时，按设备首跑随机固化到 pref rh_pace。
    *  2026-11-19 定稿：删掉设备系数（会乘走用户设定值）与 800ms 硬地板（与滑块左端重合
    *  会导致最左端约一半间隔精确等于 800，形成新的固定节奏特征）。
    *  pace() 统一收口在 callApi 发送前，任务执行器零改动。 ---- */
   private static final long PACE_Q_DEF = 2000L;
   private static final long PACE_S_DEF = 3000L;
   private static final long PACE_C_DEF = 5000L;
   private static final long PACE_G_DEF = 11500L;
   private static final int  PACE_A_DEF = 40;
   private static volatile long paceQueryMs = PACE_Q_DEF;
   private static volatile long paceSubmitMs = PACE_S_DEF;
   private static volatile long paceClaimMs = PACE_C_DEF;
   private static volatile long paceClaimGapMs = PACE_G_DEF;
   private static volatile double paceAmp = PACE_A_DEF / 100D;
   private static final Set<String> rejectedToday = ConcurrentHashMap.newKeySet();
   private static volatile String rejectedDay = "";
   // LVL 当日台账（lvlDay/lvlDir/lvlMap/lvlLoaded/LVL_FILE）与 RMB 锁已迁 LvlLedger（优化5簇1）；
   // K_* 键常量留这里（键的使用方在任务执行器）。
   private static final String K_SIGNIN = "@signin";
   private static final String K_PLAY_SIGN = "@playSignin";
   static final String K_DAILY_REWARD = "@dailyReward";
   final Set<String> lvlBumpedThisRun = new HashSet<>();
   volatile boolean lvlOnRun = true;
   private List<String> itemPool = null;
   private static String playSignData() {
      StringBuilder var0 = new StringBuilder("{");
      String var1 = RhLocationContext.asac();
      if (var1 != null) {
         var0.append("\"asac\":\"").append(var1).append("\",");
      }

      var0.append(RhLocationContext.jsonPair());
      var0.append("\"playId\":4,\"usertoken\":\"\"}");
      return var0.toString();
   }

   /** 地图/圈数/背包/擦亮域（优化5簇4）：finish 阶段动作收口在 MapTasks，owner 引用回穿响应通道 */
   final MapTasks mapTasks = new MapTasks(this);

   FishTaskAuto(ClassLoader var1, String var2) {
      this.cl = var1;
      this.cacheDir = var2;
      // 报告与 lvl 存档共用持久目录；lvlDir 未初始化时退化为通道目录（只影响首次极端时序，不影响请求）
      this.reportDir = LvlLedger.dir() != null ? LvlLedger.dir() : var2;
   }

   private static void markRejected(String var0) {
      try {
         String var1 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
         if (!var1.equals(rejectedDay)) {
            rejectedDay = var1;
            rejectedToday.clear();
         }

         rejectedToday.add(var0);
      } catch (Throwable var2) {
      }
   }

   static String cacheDirPath(Context var0) {
      File var1 = com.piggyhand.fish.core.RhRuntime.cacheDir();
      return var1 != null ? var1.getAbsolutePath() : ConfigManager.fishExternal("cache");
   }

   private static int probeRunnableCount(Context var0) {
      try {
         LvlLedger.loadOnce(var0);
         ClassLoader var1 = var0.getClassLoader();
         String var2 = cacheDirPath(var0);
         FishTaskAuto var3 = new FishTaskAuto(var1, var2);
         JSONObject var4 = var3.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "探测");
         List var5 = var3.extractTasks(var4);
         if (var5 == null) {
            return -1;
         } else {
            int var6 = 0;

            for (Object var8o : var5) {
               JSONObject var8 = (JSONObject) var8o;
               String var9 = str(var8, "deliveryId");
               if (var9 != null && !LvlLedger.skip(var9)) {
                  String var10 = str(var8, "idleTaskStatus");
                  if ("AWARD".equals(var10)) {
                     var6++;
                  } else if ("COMPLETE".equals(var10)) {
                     JSONObject var11 = var8.optJSONObject("taskBenefitDTO");
                     if (var11 != null && "true".equals(optStr(var11, "needBenefit"))) {
                        var6++;
                     }
                  } else if ("ACCEPTED".equals(var10) && !rejectedToday.contains(var9)) {
                     var6++;
                  }
               }
            }

            // 圈数挑战 AWARD 探测（11-10）：任务无可跑但挑战到档时仍要 launch（claim_only 迷你跑批）
            // UI 开关关闭时不探测不置位，迷你跑批保持沉默
            ConfigManager var14 = ConfigManager.load();
            int var13 = (var14 == null || var14.cycleChallenge) ? MapTasks.probeCycleAward(var3) : 0;
            if (var6 == 0 && var13 > 0) {
               MapTasks.cycleClaimOnly = true;
               var6 = 1;
            } else {
               MapTasks.cycleClaimOnly = false;
            }

            return var6;
         }
      } catch (Throwable var12) {
         log("[probe] error: " + var12);
         return -1;
      }
   }

   /**
    * 手动触发（模块 App「7天圈数挑战」行点击）：独立实例查档+领取一轮，结果落持久报告文件供 CYCLE_REPORT 读取。
    * 报告头部带 @13位时间戳 runId，App 侧 pollCycleReport 按 runId 过滤旧报告。
    * 与自动路径共用 maybeClaimCycleReward（含本地去重档），不会重复领取。
    */
   public static void runCycleClaimOnce(Context var0) {
      FishTaskAuto var1 = new FishTaskAuto(var0.getClassLoader(), cacheDirPath(var0));
      try {
         var1.report.append("圈数挑战报告 @").append(System.currentTimeMillis()).append('\n');
         ConfigManager var2 = ConfigManager.load();
         if (var2 != null && !var2.cycleChallenge) {
            var1.report.append("[圈数挑战] 开关已关闭，先在常用功能里打开\n");
         } else {
            var1.mapTasks.maybeClaimCycleReward();
         }
      } catch (Throwable var3) {
         var1.report.append("[圈数挑战] 异常 ").append(var3).append('\n');
      }
      // 报告落持久区 files/（lvlDirPath 同源），冷启动不被清；写 cache 会被系统回收
      String var4 = LvlLedger.dirPath(var0 != null ? var0 : appCtx);
      var1.writeFile((var4 != null ? var4 : cacheDirPath(var0)) + "/rh_tasks_cycle.txt", var1.report.toString());
      log("[cycle-manual] report written");
   }

   public static void launch(Context var0, String var1) {
      if (!RUNNING.compareAndSet(false, true)) {
         log("[tasks] engine already running, trigger ignored");
      } else {
         appCtx = var0.getApplicationContext() != null ? var0.getApplicationContext() : var0;
         // 与 core.RhRuntime.appCtx 保持同步（LvlLedger 等已统一走 RhRuntime 来源；幂等写，值恒等）
         com.piggyhand.fish.core.RhRuntime.appCtx = appCtx;

         try {
            ConfigManager var2 = ConfigManager.load();
            toastOn = var2 == null || var2.toastNotify;
            polishOn = var2 == null || var2.polish;
            // v1.5.73：每轮跑批前刷新节奏参数（UI 滑块改值 → 重启闲鱼 → 这里读到新值）
            applyPace(var2);
         } catch (Throwable var4) {
         }

         ClassLoader var5 = var0.getClassLoader();
         String var3 = cacheDirPath(var0);   // 响应通道：必须是宿主写 rh_resp_* 的那个目录
         LvlLedger.reload(var0);             // 持久区：存档 + 报告，冷启动不被清（优化5簇1 收口）
         new Thread(() -> {
            try {
               new FishTaskAuto(var5, var3).run(var1);
            } finally {
               RUNNING.set(false);
            }
         }, "rh-tasks").start();
      }
   }

   static void toast(String var0) {
      if (toastOn && appCtx != null) {
         try {
            new Handler(Looper.getMainLooper()).post(() -> {
               try {
                  Toast.makeText(appCtx, var0, 0).show();
               } catch (Throwable var2x) {
               }
            });
         } catch (Throwable var2) {
         }
      }
   }

   private static String signinRewards(JSONObject var0) {
      try {
         if (var0 == null) {
            return "";
         } else {
            JSONObject var1 = var0.optJSONObject("data");
            if (var1 == null) {
               return "";
            } else {
               JSONArray var2 = var1.optJSONArray("rewards");
               if (var2 != null) {
                  return var2.toString();
               } else {
                  JSONObject var3 = var1.optJSONObject("result");
                  if (var3 != null) {
                     var2 = var3.optJSONArray("rewards");
                     if (var2 != null) {
                        return var2.toString();
                     }
                  }

                  var2 = var1.optJSONArray("rewardList");
                  return var2 == null ? "" : var2.toString();
               }
            }
         }
      } catch (Throwable var4) {
         return "";
      }
   }

   private static String coinSuffix(String var0) {
      try {
         if (var0 != null && !var0.isEmpty()) {
            JSONArray var1 = new JSONArray(var0);
            int var2 = 0;

            for (int var3 = 0; var3 < var1.length(); var3++) {
               JSONObject var4 = var1.optJSONObject(var3);
               if (var4 != null) {
                  String[] var5 = new String[]{"coinNum", "coin", "amount", "value"};

                  for (String var9 : var5) {
                     if (var4.has(var9)) {
                        try {
                           var2 += Integer.parseInt(String.valueOf(var4.get(var9)).replaceAll("[^0-9-]", ""));
                        } catch (Throwable var11) {
                        }
                        break;
                     }
                  }
               }
            }

            return var2 > 0 ? " +" + var2 + "币" : "";
         } else {
            return "";
         }
      } catch (Throwable var12) {
         return "";
      }
   }

   public static void autoRunDaily(final Context var0) {
      try {
         String var1 = null;

         try {
            var1 = (String)Application.class.getMethod("getProcessName").invoke(null);
         } catch (Throwable var21) {
         }

         if (var1 != null && var1.contains(":")) {
            return;
         }

         int var2 = 240;

         try {
            ConfigManager var3 = ConfigManager.load();
            if (var3 == null || !var3.enabled || !var3.autoRun) {
               log("[auto-run] disabled by config (enabled=" + (var3 != null && var3.enabled) + ", autoRun=" + (var3 != null && var3.autoRun) + "), skip");
               return;
            }

            var2 = var3.autoRunIntervalMin;
         } catch (Throwable var22) {
            log("[auto-run] config check fail, continue: " + var22);
         }

         long var24 = System.currentTimeMillis();

         File var5;
         try {
            File var6 = var0.getFilesDir();
            var5 = new File(var6 != null ? var6 : new File("/data/data/com.taobao.idlefish/files"), "rh_autorun.txt");
         } catch (Throwable var20) {
            var5 = new File("/data/data/com.taobao.idlefish/files", "rh_autorun.txt");
         }

         long var25 = 0L;

         try {
            var25 = Long.parseLong(new String(Files.readAllBytes(var5.toPath()), "UTF-8").trim());
         } catch (Throwable var19) {
         }

         if (var25 == 0L) {
            try {
               File var8 = com.piggyhand.fish.core.RhRuntime.cacheDir();
               String var9 = var8 == null ? "" : new String(Files.readAllBytes(new File(var8, "rh_pref.json").toPath()), "UTF-8");
               String var10 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
               if (var9.contains("\"autorun_date\":\"" + var10 + "\"")) {
                  var25 = var24;
               }
            } catch (Throwable var18) {
            }
         }

         long var26 = var24 - var25;
         final long var27 = (long)Math.max(0, var2) * 60000L;
         final long var12 = 45000L;
         log("[auto-run] app start -> first probe in 45s (last run " + (var25 == 0L ? "never" : var26 / 60000L + " min ago") + ")");
         final long var16 = var25 == 0L ? var24 : var25;
         new Thread(new Runnable() {
            @Override
            public void run() {
               // v1.5.5 P0-3 就绪门：先等引擎通道就绪（报告服务 bind 成功 ≈ hook 链尾部完成），
               // 就绪即走（通常远早于 45s）；45s 兜底照旧开跑，不因信号缺失卡死。
               // (2) 号日志「探测 timeout ×10」里有一部分是引擎未就绪时的白打探针。
               long varWait0 = System.currentTimeMillis();

               // Phase 2b:就绪标记移入 core.RhRuntime(engineListening),task 不再引 hook 包
               while (!com.piggyhand.fish.core.RhRuntime.engineListening && System.currentTimeMillis() - varWait0 < var12) {
                  try {
                     Thread.sleep(500L);
                  } catch (InterruptedException varW) {
                     return;
                  }
               }

               long var1 = var12;
               int var3 = 0;
               long var4 = var16;

               while (true) {
                  try {
                     Thread.sleep(var1);
                  } catch (InterruptedException var11) {
                     return;
                  }

                  int var6 = FishTaskAuto.probeRunnableCount(var0);
                  if (var6 < 0) {
                     FishTaskAuto.log("[probe] fail, retry in 3min");
                     var1 = 180000L;
                  } else {
                     boolean var7 = var6 > 0;
                     boolean var8 = System.currentTimeMillis() - var4 >= var27;
                     if (!var7 && !var8) {
                        if (var3 > 0) {
                           var3--;
                        }

                        var1 = var3 > 0 ? 300000L : 900000L;
                     } else {
                        try {
                           File var9 = var0.getFilesDir();
                           File var10 = new File(var9 != null ? var9 : new File("/data/data/com.taobao.idlefish/files"), "rh_autorun.txt");
                           Files.write(var10.toPath(), String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
                        } catch (Throwable var12x) {
                        }

                        FishTaskAuto.log("[probe] runnable=" + var6 + (var8 && !var7 ? " (force)" : "") + " -> launch");
                        var4 = System.currentTimeMillis();
                        // 圈数挑战独占可领时切 claim_only：跳过浏览段，只做签到+掷骰+圈数/奖桶领取
                        String var13x = MapTasks.cycleClaimOnly ? "{\"claim_only\":true,\"polish\":false}" : "{}";
                        if (MapTasks.cycleClaimOnly) {
                           FishTaskAuto.log("[probe] cycle award pending -> claim_only run");
                        }
                        MapTasks.cycleClaimOnly = false;
                        FishTaskAuto.launch(var0, var13x);
                        var3 = 3;
                        var1 = 300000L;
                     }
                  }
               }
            }
         }, "rh-autorun").start();
      } catch (Throwable var23) {
         log("[auto-run] schedule FAILED: " + var23);
      }
   }

   private void run(String var1) {
      long var2 = System.currentTimeMillis();
      log("=== task auto run start ===");
      JSONObject var4 = parseObj(var1);
      boolean var5 = var4.has("signin") ? var4.optBoolean("signin") : true;
      boolean var6 = var4.has("polish") ? var4.optBoolean("polish") : polishOn;
      boolean var7 = opt(var4, "sold", false);
      boolean var8 = opt(var4, "shop", false);
      boolean var9 = opt(var4, "browse", true);
      boolean var10 = opt(var4, "claim_only", false);
      boolean var11 = opt(var4, "lvl", true);
      this.lvlOnRun = var11;
      // 2026-11-15：假曝光闸。play.ad.event.push 的 eventType=complete 是纯伪造，
      // 服务端与广告平台（优量汇/穿山甲）对账时缺真实曝光回传，客户端侧无法补。
      // 2026-11-16 改默认开：11-12 实证（RhCoinTask.runRewardVideoAd，渠道任务 1047271694
      // ACCEPTED→COMPLETE 领 +100）把服务端下发的 adEventPush 原样 push 回去即认定观看完成，
      // 不校验 CSJ 会话——「假曝光毒化当日窗口」的旧担忧不成立。默认关的直接后果是
      // 106538/106181（看15秒视频×2，loopTimes=4）全天挂 0/4 一动不动（今日真机实证）。
      boolean varAdEvent = opt(var4, "adEvent", true);
      if (var10) {
         var9 = false;
      }

      try {
         if (var11) {
            LvlLedger.loadOnce(appCtx);
            LvlLedger.ensure(LvlLedger.dayOf(System.currentTimeMillis()));
         }

         if (var5 && (!var11 || !LvlLedger.skip("@signin"))) {
            JSONObject var12 = this.callApi("mtop.taobao.idle.coin.signin", "1.0", "{}", "签到");
            String var13 = firstRet(var12);
            boolean var14 = var13 != null && var13.contains("SUCCESS");
            this.report.append("[签到] ").append(var14 ? "SUCCESS " + coinSuffix(signinRewards(var12)) : (var13 == null ? "无响应" : var13)).append('\n');
            if (var14) {
               toast("✓ 每日签到" + coinSuffix(signinRewards(var12)));
               if (var11) {
                  LvlLedger.bump("@signin", this.lvlBumpedThisRun);
               }
            } else if (var11 && var13 != null && (var13.contains("SIGNED") || var13.contains("已签"))) {
               LvlLedger.bump("@signin", this.lvlBumpedThisRun);
            }
         } else if (var5) {
            this.report.append("[签到] 跳过：标识=2（今日已判定完成 3 次）\n");
         }

         if (var5 && (!var11 || !LvlLedger.skip("@playSignin"))) {
            JSONObject var46 = this.callApi("mtop.taobao.idle.play.sign.in", "1.0", playSignData(), "玩一玩签到");
            String var50 = firstRet(var46);
            boolean var53 = var50 != null && var50.contains("SUCCESS");
            boolean var15 = var46 != null && var46.optJSONObject("data") != null && var46.optJSONObject("data").optBoolean("signInAwarded", false);
            this.report.append("[玩一玩签到] ").append(var53 ? "SUCCESS" + (var15 ? " 今日已签+奖" : " 幂等(今日已签)") : (var50 == null ? "无响应" : var50)).append('\n');
            if (var53) {
               toast("✓ 玩一玩签到");
               if (var11) {
                  LvlLedger.bump("@playSignin", this.lvlBumpedThisRun);
               }
            } else if (var11 && var50 != null && var50.contains("SIGNED_IN")) {
               LvlLedger.bump("@playSignin", this.lvlBumpedThisRun);
            }
         } else if (var5) {
            this.report.append("[玩一玩签到] 跳过：标识=2（今日已判定完成 3 次）\n");
         }

         if (opt(var4, "mapRoll", true)) {
            this.mapTasks.doMapRoll();
            this.mapTasks.maybeClaimCycleReward(); // 掷骰推进圈数后查 AWARD 档位并领取
         }

         if (opt(var4, "bagUse", true)) {
            this.mapTasks.doBagUse();
         }

         if (var6) {
            this.mapTasks.runPolish();
         }

         if (var7) {
            this.report.append("[卖鱼] 跳过：钓场玩法在闲鱼 7.25.31 已移除（APK 内无 fishing 接口，旧版玩法已下线）\n");
         }

         if (var8) {
            JSONObject var47 = this.callApi("mtop.taobao.idle.startup.shop.daily.benefit", "1.0", "{}", "小店日福利");
            String var51 = firstRet(var47);
            if (var51 != null && var51.contains("SUCCESS")) {
               toast("✓ 小店日福利");
            }
         }

         JSONObject var48 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "任务列表");
         List var52 = this.extractTasks(var48);
         if (var52 == null) {
            this.report.append("任务列表获取失败\n");
            this.finish(var2);
            return;
         }

         this.report.append("任务总数: ").append(var52.size()).append('\n');
         if (var11) {
            long var54 = 0L;

            try {
               JSONObject var16 = var48 == null ? null : var48.optJSONObject("data");
               if (var16 != null) {
                  var54 = var16.optLong("systemTime", 0L);
               }
            } catch (Throwable var44) {
            }

            LvlLedger.ensure(LvlLedger.dayOf(var54 > 0L ? var54 : System.currentTimeMillis()));
         }

         ArrayList var55 = new ArrayList();
         ArrayList var56 = new ArrayList();
         ArrayList var57 = new ArrayList();
         ArrayList var17 = new ArrayList();
         ArrayList var18 = new ArrayList();
         ArrayList var19 = new ArrayList();
         String[] var20 = null;
         ArrayList var21 = new ArrayList();
         ArrayList var22 = new ArrayList();
         HashSet var23 = new HashSet();
         int var24 = 0;

         for (Object var26o : var52) {
            JSONObject var26 = (JSONObject) var26o;
            String var27 = str(var26, "deliveryId");
            // 2026-11-15 P3 实测（_diag/p3_capture/00_SUMMARY.md）：
            //   89640  status=AWARD   times=3/3 reachLimit=true  → 因 !"AWARD".equals 判定落 else
            //          → 塞进 var55「可领」→ claimOne 打 getolivertaskbenefit → RMB_CHECK
            //   106181 status=COMPLETE times=4/4 reachLimit=true → 落内层 showAds 分支
            //          → 塞进 var57「视频任务」→ runAdEventTask 再伪造 4 次 complete
            // 对已经没有任何东西可给的任务反复刷接口，就是 FAIL_BIZ_IDL_COIN_TASK_RMB_CHECK 的直接来源。
            JSONObject varProg = var26.optJSONObject("progressDTO");
            String var30 = str(var26, "idleTaskStatus");
            // 2026-11-16 回归修复：AWARD = 已做完待领奖，progressDTO 天然 times=maxTimes、reachLimit=true
            // —— quotaFull 拦 AWARD 会把倒计时任务（58233）永远卡在「当日额度已满」，领奖入口被误杀
            // （今日真机实证：58233 挂 AWARD 一整天，面板「领取奖励」按钮亮着，引擎零请求）。
            boolean varQuotaFull = !"AWARD".equals(var30) && LvlLedger.quotaFull(var26);
            if (varQuotaFull && var27 != null) {
               // 记入 var23 去重集，避免同一批内重复判定同一任务
               var23.add(var27);
               // 落台账：复核 / 收尾 / claimOne 三条后续路径都要认这张表，见 quotaFullIds 注释
               this.quotaFullIds.add(var27);
               this.report.append("[跳过] ").append(var27).append(" 当日额度已满（")
                     .append(varProg.optInt("times", 0)).append("/")
                     .append(varProg.optInt("maxTimes", 0)).append("），不发请求\n");
            } else if (var27 != null && !var23.contains(var27) && !rejectedToday.contains(var27)) {
               // 2026-11-16 视频任务豁免 LVL 台账：106181/106538 在旧版 eventType 改写毒化期被 bump 到 2，
               // skip 恒真 → 永远进不了 var57（真机台账实证 "106181":2,"106538":2，报告 视频任务:0）。
               // push 路径有服务端 times/maxTimes 权威进度 + 每轮复核，幂等安全，防重不靠持久台账；
               // 真实防重由 push 全败 markRejected（内存态，当日有效）承担，见 runAdEventTask 尾部。
               JSONObject varAdChk = var26.optJSONObject("materialDTO");
               boolean varLvlExempt = "ACCEPTED".equals(var30) && varAdChk != null
                     && optStr(varAdChk, "action").contains("showAds");
               if (var11 && !varLvlExempt && LvlLedger.skip(var27)) {
                  var24++;
               } else {
                  var23.add(var27);
                  JSONObject var28 = var26.optJSONObject("materialDTO");
                  JSONObject var29 = var26.optJSONObject("taskBenefitDTO");
                  // var30 已在 quotaFull 判定前取好（AWARD 豁免需要它），这里直接复用
                  String var31 = var28 == null ? "" : optStr(var28, "actionType");
                  String var32 = var28 == null ? "?" : optStr(var28, "title");
                  String var33 = var28 == null ? "" : optStr(var28, "action");
                  String var34 = str(var26, "sceneId");
                  boolean var35 = var29 != null && "true".equals(optStr(var29, "needBenefit"));
                  if ((!"COMPLETE".equals(var30) || !var35) && !"AWARD".equals(var30)) {
                     if ("ACCEPTED".equals(var30) && var33.contains("showAds") && var33.contains("adType=ecMall")) {
                        var18.add(new String[]{var27, var32, var33});
                     } else if ("ACCEPTED".equals(var30) && var33.contains("showAds")) {
                        if (!varAdEvent) {
                           // 不伪造曝光：这类任务只能靠真实看完广告完成，代码侧无解。
                           // 硬发 complete 只会把额度记成"已完成"却无曝光，反而毒化当日窗口。
                           this.report.append("[广告] ").append(var32)
                                 .append(" 跳过（adEvent=off，假曝光拿不到平台侧回传）\n");
                        } else {
                           String var36 = extractAdEventPush(var33);
                           if (var36 != null) {
                              var57.add(new String[]{var27, var36, optStr(var28, "subTitle")});
                           } else {
                              this.report.append("[广告] ").append(var32).append(" 无 adEventPush 参数，跳过\n");
                           }
                        }
                     } else if ("ACCEPTED".equals(var30) && var33.contains("xianyu/search.html")) {
                        var17.add(new String[]{var27, var32, var33, str(var26, "fromToken"), str(var26, "groupId")});
                     } else if ("ACCEPTED".equals(var30) && var33.contains("bbdbindex.html")) {
                        var19.add(new String[]{var27, var32});
                     } else if (!"ACCEPTED".equals(var30)
                        || !"clickFinish".equals(var31)
                           && !"EXCUTE".equals(var31)
                           && !"PLAINVISIT".equals(var31)
                           && !"VISIT".equals(var31)
                           && !"SYSTEM_SWITCH".equals(var31)) {
                        if ("ACCEPTED".equals(var30) && "idleCoinFeesAnchor".equals(var31)) {
                           // 带上列表里同一行的 fromToken/groupId（P0-1）：浏览上报属于 496 场景，
                           // 旧代码只发 sceneId+implId+deliveryId，7 份日志里 28 次全 PARAM_ILLEGAL。
                           var20 = new String[]{var27, var34 == null ? "496" : var34, str(var26, "fromToken"), str(var26, "groupId")};
                        }
                     } else {
                        // 第 5 列带上 materialDTO.actionType：触发毒化按任务类型聚合（按标题聚合永远只 1 次）
                        var56.add(new String[]{var27, var32, str(var26, "fromToken"), str(var26, "groupId"), var31});
                     }
                  } else {
                     var55.add(new String[]{var27, var32});
                  }
               }
            }
         }

         this.report
            .append("可领:")
            .append(var55.size())
            .append(" 可触发:")
            .append(var56.size())
            .append(" 视频任务:")
            .append(var57.size())
            .append(" 搜一搜:")
            .append(var17.size())
            .append(" ecMall:")
            .append(var18.size())
            .append(" 夺宝:")
            .append(var19.size())
            .append(" 浏览:")
            .append(var20 != null ? 1 : 0)
            .append('\n');
         this.report.append("[lvl] 跳过=").append(var24).append(" 记录=").append(LvlLedger.size()).append(" day=").append(LvlLedger.day()).append('\n');

         for (Object var65o : var55) {
            String[] var65 = (String[]) var65o;
            if (this.abort) {
               break;
            }

            this.claimOne(var65[0], var65[1], var21, var22);
            this.processed.add(var65[0]);
            sleepJitter(1200L);
         }

         for (Object var66o : var57) {
            String[] var66 = (String[]) var66o;
            if (this.abort) {
               break;
            }

            this.runAdEventTask(var66[0], var66[1], var21, var22);
            sleepJitter(2000L);
         }

         for (Object var67o : var17) {
            String[] var67 = (String[]) var67o;
            if (this.abort || var10) {
               break;
            }

            this.runSearchTask(var67[0], var67[1], var67[2], var67[3], var67[4], var21, var22);
            sleepJitter(2000L);
         }

         for (Object var68o : var18) {
            String[] var68 = (String[]) var68o;
            if (this.abort || var10) {
               break;
            }

            this.runEcMallTask(var68[0], var68[1], var68[2], var21, var22);
            sleepJitter(2000L);
         }

         for (Object var69o : var19) {
            String[] var69 = (String[]) var69o;
            if (this.abort || var10) {
               break;
            }

            this.runTreasureBetTask(var69[0], var69[1], var21, var22);
            sleepJitter(2000L);
         }

         for (Object var70o : var56) {
            String[] var70 = (String[]) var70o;
            if (this.abort || var10) {
               break;
            }

            // 字段闸：缺 fromToken/groupId 的触发请求云端必回 PARAM_ILLEGAL，直接不发（P0-1）
            JSONObject var72 = this.triggerBody("496", var70[0], var70[2], var70[3], null);
            String var71k = "触发|" + (var70.length > 4 && var70[4] != null ? var70[4] : var70[1]);
            if (var72 == null) {
               this.report.append("[触发] ").append(var70[1]).append(" 跳过（列表未下发 fromToken/groupId，发也必回 PARAM_ILLEGAL）\n");
               continue;
            }

            if (this.triggerBlocked(var71k)) {
               this.report.append("[触发] ").append(var70[1]).append(" 跳过（同类 ").append(var70.length > 4 ? var70[4] : "?").append(" 本轮已连败 2 次，停发待下一轮）\n");
               continue;
            }

            JSONObject var74 = this.callApi("mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var72.toString(), "触发任务 " + var70[1]);
            String var76 = firstRet(var74);
            this.triggerNote(var71k, var76);
            if (var74 != null && var76 != null) {
               if (!var76.contains("SUCCESS")) {
                  this.report.append("[触发] ").append(var70[1]).append(" -> ").append(var76).append("（云控判定需真实行为，协议跳过）\n");
                  markRejected(var70[0]);
                  if (this.lvlOnRun) {
                     LvlLedger.bump(var70[0], this.lvlBumpedThisRun);
                  }
               } else {
                  this.report.append("[触发] ").append(var70[1]).append(" -> ").append(var76).append('\n');
                  if (this.lvlOnRun) {
                     LvlLedger.reset(var70[0]);
                  }

                  int var78 = -1;
                  int var80 = 0;

                  while (var80 < 10 && !this.abort) {
                     sleepJitter(2000L);
                     String var82 = null;
                     int var84 = -1;
                     int var86 = -1;
                     List var88 = this.extractTasks(this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "补打复核 " + var70[1]));
                     if (var88 != null) {
                        for (Object var37o : var88) {
                           JSONObject var37 = (JSONObject) var37o;
                           if (var70[0].equals(str(var37, "deliveryId"))) {
                              var82 = str(var37, "idleTaskStatus");
                              JSONObject var38 = var37.optJSONObject("progressDTO");
                              if (var38 != null) {
                                 try {
                                    var84 = Integer.parseInt(str(var38, "times"));
                                 } catch (Throwable var41) {
                                 }

                                 try {
                                    var86 = Integer.parseInt(str(var38, "maxTimes"));
                                 } catch (Throwable var40) {
                                 }
                              }
                              break;
                           }
                        }
                     }

                     if (!"AWARD".equals(var82) && !"COMPLETE".equals(var82)) {
                        if (var86 > 0 && var84 >= var86 || var80 > 0 && var84 == var78) {
                           break;
                        }

                        var78 = var84;
                        JSONObject var90 = this.callApi(
                           "mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var72.toString(), "补打 " + var70[1] + "#" + (var80 + 2)
                        );
                        String var91 = firstRet(var90);
                        if (var91 != null && var91.contains("SUCCESS")) {
                           var80++;
                           continue;
                        }

                        if (var91 != null) {
                           this.report.append("[触发] ").append(var70[1]).append(" 补打#").append(var80 + 2).append(" -> ").append(var91).append('\n');
                        }
                        break;
                     }

                     this.claimOne(var70[0], var70[1], var21, var22);
                     this.processed.add(var70[0]);
                     break;
                  }
               }
            } else {
               this.report.append("[触发] ").append(var70[1]).append(" -> 无响应，跳过\n");
            }

            sleepJitter(1200L);
         }

         if (var9 && var20 != null && !this.abort && !browseDeadToday()) {
            this.browseForTask(var20, var21, var22);
         } else if (var20 != null && browseDeadToday()) {
            this.report.append("[浏览] 跳过（当日已判需真实行为，不再重试）\n");
         }

         if (!this.abort) {
            for (int var64 = 0; var64 < 2; var64++) {
               sleepJitter(6000L);
               var48 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "任务列表复核" + (var64 + 1));
               List var71 = this.extractTasks(var48);
               if (var71 != null) {
                  for (Object var75o : var71) {
                     JSONObject var75 = (JSONObject) var75o;
                     String var77 = str(var75, "deliveryId");
                     if (var77 != null && !this.processed.contains(var77) && (!var11 || !LvlLedger.skip(var77))) {
                        // 复核这条路径原来只看 status=AWARD/needBenefit，不看额度——
                        // 06:33:22 实测：89640 主循环已跳过，这里又打了一发 benefit 换回 RMB_CHECK。
                        if (LvlLedger.quotaFull(var75) || this.quotaFullIds.contains(var77)) {
                           this.processed.add(var77);
                           this.report.append("[复核] ").append(var77).append(" 跳过（当日额度已满，不发 benefit）\n");
                           continue;
                        }
                        JSONObject var79 = var75.optJSONObject("taskBenefitDTO");
                        String var81 = str(var75, "idleTaskStatus");
                        JSONObject var83 = var75.optJSONObject("materialDTO");
                        String var85 = var83 == null ? "?" : optStr(var83, "title");
                        boolean var87 = var79 != null && "true".equals(optStr(var79, "needBenefit"));
                        if ("COMPLETE".equals(var81) && var87 || "AWARD".equals(var81)) {
                           this.processed.add(var77);
                           this.claimOne(var77, var85, var21, var22);
                           sleep(800L);
                        }
                     }
                  }
               }
            }
         }

         this.report.append("领取成功 ").append(var21.size()).append(": ").append(var21).append('\n');
         this.report.append("领取失败 ").append(var22.size()).append(": ").append(var22).append('\n');
      } catch (Throwable var45) {
         this.report.append("引擎异常: ").append(var45).append('\n');
         log("task auto FAIL: " + var45);
      }

      /* v1.5.2-fix：收尾兜底 —— 当日所有 AWARD 任务强制再收一遍。
       * 现场（2026-11-11）：两单 CSJ 视频任务 106538/106181 全天挂在 AWARD 没被收，
       * 原因有二：① 主循环先判 rejectedToday / lvlSkip 直接跳过；
       * ② trigger 返回 SUCCESS 但状态不变（服务端只记"上报"，不改状态），
       *    必须再跟一发 getolivertaskbenefit 才真正落库（106181 实测打了 3 次 trigger 无效，
       *    补 benefit 后立刻变 COMPLETE）。
       * 兜底不复用任何缓存 token：重拉列表取最新 fromToken+groupId 再打。 */
      if (!this.abort) {
         List<String> var21 = new ArrayList<String>();
         List<String> var22 = new ArrayList<String>();
         try {
            List var100 = this.extractTasks(this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "收尾复查"));
            if (var100 != null) {
               for (Object var101o : var100) {
                  JSONObject var101 = (JSONObject) var101o;
                  if (!"AWARD".equals(str(var101, "idleTaskStatus"))) continue;
                  String var102 = str(var101, "deliveryId");
                  // 风控锁不再进收尾：既不打 trigger 也不打 benefit（11-12 日志里收尾是第二次刷屏源）
                  if (LvlLedger.rmbLocked(var102)) {
                     this.report.append("[收尾] ").append(var102).append(" 跳过（人民币风控锁）\n");
                     continue;
                  }
                  // 额度闸前置：额度满的 AWARD 再打 benefit 只会换回 RMB_CHECK，连 trigger 一起省掉
                  if (LvlLedger.quotaFull(var101) || this.quotaFullIds.contains(var102)) {
                     this.report.append("[收尾] ").append(var102).append(" 跳过（当日额度已满，不发 benefit）\n");
                     this.processed.add(var102);
                     continue;
                  }
                  JSONObject var103 = var101.optJSONObject("materialDTO");
                  String var104 = var103 == null ? "?" : optStr(var103, "title");
                  // 字段闸（P0-1）：收尾阶段最容易被列表缺字段带到 PARAM_ILLEGAL，缺就不发。
                  // 注意不 continue —— 触发发不出去也要走 claimOne，AWARD 状态直接领奖仍可能成。
                  JSONObject var105 = this.triggerBody("496", var102, str(var101, "fromToken"), str(var101, "groupId"), null);
                  String var105k = "收尾|" + (var103 != null ? optStr(var103, "actionType") : var104);
                  if (var105 == null) {
                     this.report.append("[收尾] ").append(var104).append(" 跳过触发（缺 fromToken/groupId），直接尝试领奖\n");
                  } else if (this.triggerBlocked(var105k)) {
                     this.report.append("[收尾] ").append(var104).append(" 跳过触发（同类本轮已连败 2 次），直接尝试领奖\n");
                  } else {
                     JSONObject var108 = this.callApi("mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var105.toString(), "收尾触发 " + var104);
                     String var109 = firstRet(var108);
                     this.triggerNote(var105k, var109);
                     this.report.append("[收尾] ").append(var104).append(" trigger=").append(var109).append('\n');
                  }

                  sleepJitter(1500L);
                  this.claimOne(var102, var104, var21, var22);
                  sleepJitter(1500L);
                  this.claimOne(var102, var104, var21, var22);
                  this.processed.add(var102);
                  sleepJitter(1000L);
               }
            }
         } catch (Throwable var110) {
            this.report.append("[收尾] 异常 ").append(var110).append('\n');
         }
      }

      this.finish(var2);
   }

   private static String extractAdEventPush(String var0) {
      try {
         int var1 = var0.indexOf("adEventPush=");
         if (var1 < 0) {
            return null;
         } else {
            String var2 = var0.substring(var1 + "adEventPush=".length());
            int var3 = var2.indexOf(38);
            if (var3 >= 0) {
               var2 = var2.substring(0, var3);
            }

            String var4 = URLDecoder.decode(var2, "UTF-8");
            JSONObject var5 = new JSONObject(var4);
            // 2026-11-16：不再改写 eventType——服务端按下发原值（firstEvent+countdown）对账，
            // 改写成 complete 反而不认（11-12 RhCoinTask.runRewardVideoAd 实证：firstEvent
            // 一发即 AWARD；改值后服务端视作未知事件）。
            return var5.toString();
         }
      } catch (Throwable var6) {
         return null;
      }
   }

   private void runAdEventTask(String var1, String var2, List<String> var3, List<String> var4) {
      // 2026-11-16 重构（对齐 RhCoinTask.runRewardVideoAd 的 11-12 实证路径）：
      // 每轮 push 后复核 times/status——AWARD/COMPLETE 立即停，times 不再增长也停
      // （当日额度满或服务端不认，硬刷只会砸出 reach_upper_limit / RMB_CHECK）；
      // 旧逻辑一口气连推 4 次再复核，既容易撞当日上限也不利于节奏整形。
      byte var5 = 4;
      int var6 = 0;
      int var7 = 0;
      // ret 失败计数（非 reach_upper_limit）：全败且零成功 → 内存态当日拒绝，不写持久台账
      int varFail = 0;
      int prevTimes = -1;

      for (int var8 = 0; var8 < var5 && !this.abort; var8++) {
         JSONObject var9 = this.callApi("mtop.taobao.idle.play.ad.event.push", "1.0", var2, "广告事件 " + (var8 + 1));
         String var10 = firstRet(var9);
         if (var9 == null) {
            var7++;
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 无响应（7.28.30 响应不落盘，继续）\n");
            sleepJitter(5000L);
         } else if (var10 == null || !var10.contains("SUCCESS")) {
            boolean capped = var10 != null && var10.contains("reach_upper_limit");
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 -> ")
                  .append(capped ? "已达上限（按已达成处理）" : var10).append('\n');
            if (!capped) {
               varFail++;
               break;
            }
            sleepJitter(2000L);
         } else {
            var6++;
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 push -> ").append(var10).append('\n');
            sleepJitter(2500L);
         }

         int[] tp = adTaskProgress(var1);
         if (tp[2] > 0) {
            break; // AWARD/COMPLETE，收口交给尾部复核
         }
         if (tp[1] > 0 && tp[0] >= tp[1]) {
            break; // loopTimes 推满
         }
         if (prevTimes >= 0 && tp[0] <= prevTimes) {
            this.report.append("[广告] times 未增长(").append(tp[0]).append('/').append(tp[1])
                  .append(")，停止推送\n");
            break;
         }
         prevTimes = tp[0];
      }

      int var18 = -1;
      int var19 = -1;
      String var20 = null;
      List var11 = this.extractTasks(this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "广告复核"));
      if (var11 != null) {
         for (Object var13o : var11) {
            JSONObject var13 = (JSONObject) var13o;
            if (var1.equals(str(var13, "deliveryId"))) {
               var20 = str(var13, "idleTaskStatus");
               JSONObject var14 = var13.optJSONObject("progressDTO");
               if (var14 != null) {
                  try {
                     var18 = Integer.parseInt(str(var14, "times"));
                  } catch (Throwable var17) {
                  }

                  try {
                     var19 = Integer.parseInt(str(var14, "maxTimes"));
                  } catch (Throwable var16) {
                  }
               }
               break;
            }
         }
      }

      this.report
         .append("[广告] 复核: status=")
         .append(var20)
         .append(" times=")
         .append(var18)
         .append('/')
         .append(var19)
         .append(" (respOk=")
         .append(var6)
         .append(" noResp=")
         .append(var7)
         .append(")\n");
      if ("AWARD".equals(var20) || "COMPLETE".equals(var20) || var19 > 0 && var18 >= var19 || var6 > 0) {
         this.claimOne(var1, "视频任务", var3, var4);
      }
      // 台账出台：push 有推进（var6>0）说明旧失败原因已消除，清掉毒化期遗留计数，
      // 后续轮次不再被 LvlLedger.skip 拦（配合主循环 varLvlExempt 双保险）
      if (var6 > 0 && this.lvlOnRun && LvlLedger.get(var1) > 0) {
         LvlLedger.reset(var1);
         this.report.append("[广告] ").append(var1).append(" push 推进，LVL 旧账已清\n");
      }
      // push 全 ret 败且零成功：内存态当日拒绝（进程重启复位，给新版代码次日重试机会）；
      // 不写持久台账——视频任务与 trigger 型任务不同，服务端有权威进度，撞错不该永久记仇
      if (varFail > 0 && var6 == 0) {
         markRejected(var1);
         this.report.append("[广告] ").append(var1).append(" push 全败，当日内存态拒绝\n");
      }
   }

   /** 视频任务轻量进度复核：返回 {times, maxTimes, awardFlag}；awardFlag=1 表示 AWARD/COMPLETE。
    *  供推送循环每轮调用——比整表 extractTasks 少一次对象重建，失败静默回 0。 */
   private int[] adTaskProgress(String deliveryId) {
      int[] out = new int[]{0, 0, 0};
      try {
         List var11 = this.extractTasks(this.callApi("mtop.taobao.idle.task.queryolivertasklist",
               "2.0", "{\"sceneId\":496}", "广告进度"));
         if (var11 != null) {
            for (Object var13o : var11) {
               JSONObject var13 = (JSONObject) var13o;
               if (deliveryId.equals(str(var13, "deliveryId"))) {
                  String st = str(var13, "idleTaskStatus");
                  if ("AWARD".equals(st) || "COMPLETE".equals(st)) {
                     out[2] = 1;
                  }
                  JSONObject pd = var13.optJSONObject("progressDTO");
                  if (pd != null) {
                     try {
                        out[0] = Integer.parseInt(str(pd, "times"));
                     } catch (Throwable ignored) {
                     }
                     try {
                        out[1] = Integer.parseInt(str(pd, "maxTimes"));
                     } catch (Throwable ignored) {
                     }
                  }
                  break;
               }
            }
         }
      } catch (Throwable ignored) {
      }
      return out;
   }

   private List<String> itemPool() {
      if (this.itemPool != null) {
         return this.itemPool;
      } else {
         ArrayList var1 = new ArrayList();
         JSONObject var2 = this.callApi(
            "mtop.taobao.idlehome.idle.coin.nextfresh", "1.0", "{\"enableDx\":true,\"lastResponseCount\":0,\"pageNumber\":1}", "浏览任务商品源"
         );
         if (var2 != null) {
            JSONObject var3 = var2.optJSONObject("data");
            JSONArray var4 = var3 == null ? null : var3.optJSONArray("cardList");
            if (var4 != null) {
               for (int var5 = 0; var5 < var4.length(); var5++) {
                  JSONObject var6 = var4.optJSONObject(var5);
                  JSONObject var7 = var6 == null ? null : var6.optJSONObject("cardData");
                  if (var7 != null) {
                     String var8 = optStr(var7, "bizType");
                     if (!"AD".equalsIgnoreCase(var8)) {
                        String var9 = optStr(var7, "itemId");
                        if (var9.isEmpty()) {
                           JSONObject var10 = var7.optJSONObject("trackParams");
                           if (var10 != null) {
                              var9 = optStr(var10, "itemId");
                           }
                        }

                        if (var9 != null && var9.length() > 0) {
                           var1.add(var9);
                        }
                     }
                  }
               }
            }
         }

         this.itemPool = var1;
         return var1;
      }
   }

   private void runSearchTask(String var1, String var2, String var3, String var4, String var5, List<String> var6, List<String> var7) {
      // 字段闸（P0-1）：搜索任务的 fromToken/groupId 来自列表的 fromToken/groupId 两列，
      // 缺任一项时云端固定回 PARAM_ILLEGAL，连搜索行为一起省掉。
      JSONObject var16e = new JSONObject();

      try {
         Uri var9 = Uri.parse(var3);

         for (String var13 : new String[]{"implId", "channelSrp", "adPositionCode", "adOrderPositionCode", "refpid", "k_usetanx"}) {
            String var14 = var9.getQueryParameter(var13);
            if (var14 != null) {
               var16e.put(var13, var14);
            }
         }
      } catch (Throwable var16) {
         this.report.append("[搜一搜] ").append(var2).append(" 参数组装失败: ").append(var16).append('\n');
      }

      JSONObject var8b = this.triggerBody("496", var1, var4, var5, var16e);
      String var8k = "搜一搜|" + var2;
      if (var8b == null) {
         this.report.append("[搜一搜] ").append(var2).append(" 跳过（列表未下发 fromToken/groupId）\n");
         return;
      }

      if (this.triggerBlocked(var8k)) {
         this.report.append("[搜一搜] ").append(var2).append(" 跳过（同类本轮已连败 2 次）\n");
         return;
      }

      JSONObject var8r = this.callApi("mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var8b.toString(), "搜一搜触发 " + var2);
      this.triggerNote(var8k, firstRet(var8r));
      this.callApi("mtop.taobao.idlemtopsearch.search", "1.0", "{\"q\":\"闲鱼\",\"pageNumber\":1,\"pageSize\":10}", "搜一搜行为 " + var2);
      JSONObject var17 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "搜一搜复核");
      String var18 = null;
      List var19 = this.extractTasks(var17);
      if (var19 != null) {
         for (Object var21o : var19) {
            JSONObject var21 = (JSONObject) var21o;
            if (var1.equals(str(var21, "deliveryId"))) {
               var18 = str(var21, "idleTaskStatus");
               break;
            }
         }
      }

      if (!"AWARD".equals(var18) && !"COMPLETE".equals(var18)) {
         this.report.append("[搜一搜] ").append(var2).append(" 未推进（status=").append(var18).append("）\n");
         if (this.lvlOnRun) {
            LvlLedger.bump(var1, this.lvlBumpedThisRun);
         }
      } else {
         this.claimOne(var1, var2, var6, var7);
      }
   }

   private void runEcMallTask(String var1, String var2, String var3, List<String> var4, List<String> var5) {
      String var6 = null;
      String var7 = null;
      String var8 = null;

      try {
         Uri var9 = Uri.parse(var3);
         var6 = var9.getQueryParameter("adPositionCode");
         var7 = var9.getQueryParameter("scenarioId");
         var8 = var9.getQueryParameter("sourceType");
      } catch (Throwable var25) {
      }

      if (var6 != null && var6.length() != 0) {
         String var26 = objJson("scenarioId", var7 == null ? "" : var7, "sourceType", var8 == null ? "FEEDS_SLOT" : var8);
         JSONObject var10 = this.callApi("mtop.taobao.idle.play.ad.query", "1.0", objJson("code", var6, "extra", var26), "ecMall ad.query " + var2);
         String var11 = firstRet(var10);
         if (var10 != null && var11 != null && var11.contains("SUCCESS")) {
            JSONObject var12 = var10.optJSONObject("data");
            String var13 = var12 == null ? null : optStr(var12, "token");
            String var14 = var12 == null ? null : optStr(var12, "implId");
            int var15 = 20;
            if (var12 != null) {
               String var16 = optStr(var12, "attributes");
               if (var16.length() > 0) {
                  try {
                     JSONObject var17 = new JSONObject(var16);
                     JSONObject var18 = var17.optJSONObject("taskConfig");
                     JSONObject var19 = var18 == null ? null : var18.optJSONObject("browse");
                     if (var19 != null) {
                        int var20 = parseInt(optStr(var19, "rewardDuration"), 20);
                        if (var20 > 0) {
                           var15 = var20;
                        }
                     }
                  } catch (Throwable var24) {
                  }
               }
            }

            if (var13 != null && var13.length() != 0) {
               this.report.append("[ecMall] ").append(var2).append(" token=").append(var13).append(" 浏览时长=").append(var15).append("s，等待中\n");
               sleep((long)var15 * 1000L + 2000L);
               JSONObject var27 = this.callApi(
                  "mtop.taobao.idle.play.ad.event.query",
                  "1.0",
                  objJson("adPositionCode", var6, "eventType", "award", "token", var13, "implId", var14),
                  "ecMall 预检 " + var2
               );
               String var28 = firstRet(var27);
               this.report.append("[ecMall] 预检 -> ").append(var28 == null ? "无响应" : var28).append('\n');
               String var29 = null;

               for (int var30 = 0; var30 < 5 && !this.abort; var30++) {
                  JSONObject var32 = this.callApi(
                     "mtop.taobao.idle.play.ad.event.push",
                     "1.0",
                     objJson("adPositionCode", var6, "eventType", "award", "token", var13, "implId", var14),
                     "ecMall push " + var2 + (var30 > 0 ? "#" + (var30 + 1) : "")
                  );
                  var29 = firstRet(var32);
                  if (var29 != null && (var29.contains("SUCCESS") || var29.contains("TOKEN_MISMATCH"))) {
                     break;
                  }

                  sleepJitter(30000L);
               }

               this.report.append("[ecMall] push -> ").append(var29 == null ? "无响应" : var29).append('\n');
               sleepJitter(3000L);
               JSONObject var31 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "ecMall 复核");
               String var33 = null;
               List var21 = this.extractTasks(var31);
               if (var21 != null) {
                  for (Object var23o : var21) {
                     JSONObject var23 = (JSONObject) var23o;
                     if (var1.equals(str(var23, "deliveryId"))) {
                        var33 = str(var23, "idleTaskStatus");
                        break;
                     }
                  }
               }

               if (!"AWARD".equals(var33) && !"COMPLETE".equals(var33)) {
                  this.report.append("[ecMall] ").append(var2).append(" 未推进（status=").append(var33).append("）\n");
               } else {
                  this.claimOne(var1, var2, var4, var5);
               }
            } else {
               this.report.append("[ecMall] ").append(var2).append(" 未下发 token，跳过\n");
            }
         } else {
            this.report.append("[ecMall] ").append(var2).append(" ad.query 失败: ").append(var11 == null ? "无响应" : var11).append('\n');
         }
      } else {
         this.report.append("[ecMall] ").append(var2).append(" 无 adPositionCode，跳过\n");
      }
   }

   private void runTreasureBetTask(String var1, String var2, List<String> var3, List<String> var4) {
      JSONObject var5 = this.callApi(
         "mtop.taobao.idle.coin.lottery.feeds",
         "1.0",
         objJson("fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
         "夺宝 feeds " + var2
      );
      String var6 = null;
      String var7 = firstRet(var5);
      if (var5 != null && var7 != null && var7.contains("SUCCESS")) {
         JSONObject var8 = var5.optJSONObject("data");
         JSONArray var9 = var8 == null ? null : var8.optJSONArray("cardList");
         if (var9 != null && var9.length() > 0) {
            JSONObject var10 = var9.optJSONObject(0);
            var6 = var10 == null ? null : optStr(var10, "signUpId");
         }
      }

      if (var6 != null && var6.length() != 0) {
         String var14 = null;

         for (int var15 = 0; var15 < 3 && !this.abort; var15++) {
            JSONObject var17 = this.callApi(
               "mtop.taobao.idle.coin.lottery.bet",
               "1.0",
               objJson(
                  "asac",
                  RhLocationContext.asac(),
                  "attentionSuc",
                  "false",
                  "betNumber",
                  "1",
                  "fishAdCode",
                  RhLocationContext.fishAdCode(),
                  "gps",
                  RhLocationContext.gps(),
                  "signUpId",
                  var6
               ),
               "夺宝 bet " + var2 + (var15 > 0 ? "#" + (var15 + 1) : "")
            );
            var14 = firstRet(var17);
            if (var14 != null && var14.contains("SUCCESS")) {
               break;
            }

            sleepJitter(8000L);
         }

         this.report.append("[夺宝] ").append(var2).append(" bet(").append(var6).append(") -> ").append(var14 == null ? "无响应" : var14).append('\n');
         if (var14 != null && var14.contains("SUCCESS")) {
            sleepJitter(3000L);
            JSONObject var16 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "夺宝复核");
            String var18 = null;
            List var11 = this.extractTasks(var16);
            if (var11 != null) {
               for (Object var13o : var11) {
                  JSONObject var13 = (JSONObject) var13o;
                  if (var1.equals(str(var13, "deliveryId"))) {
                     var18 = str(var13, "idleTaskStatus");
                     break;
                  }
               }
            }

            if (!"AWARD".equals(var18) && !"COMPLETE".equals(var18)) {
               this.report.append("[夺宝] ").append(var2).append(" 未推进（status=").append(var18).append("）\n");
            } else {
               this.claimOne(var1, var2, var3, var4);
            }
         }
      } else {
         this.report.append("[夺宝] ").append(var2).append(" 未取到 signUpId（ret=").append(var7 == null ? "无响应" : var7).append("），跳过\n");
      }
   }

   /** v1.5.5 P1-3：浏览任务「需真实行为」当日标记（静态，跨轮生效；进程重启复位，按日期自动失效）。 */
   private static volatile String browseDeadDay = "";

   private static boolean browseDeadToday() {
      String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
      return today.equals(browseDeadDay);
   }

   private static void browseMarkDead() {
      browseDeadDay = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
   }

   private void browseForTask(String[] var0, List<String> var3, List<String> var4) {
      // var0 = {deliveryId, sceneId, fromToken, groupId}——列表解析时同一行的原始字段。
      // 浏览上报同属 496 场景，缺 fromToken/groupId 时云端固定回 PARAM_ILLEGAL（P0-1）。
      String var1 = var0[0];
      String var0scene = var0[1] == null ? "496" : var0[1];
      JSONObject varBase = this.triggerBody(
         var0scene, var0[0], var0.length > 2 ? var0[2] : null, var0.length > 3 ? var0[3] : null, null
      );
      if (varBase == null) {
         this.report.append("[浏览] 跳过（列表未下发 fromToken/groupId，上报必回 PARAM_ILLEGAL）\n");
         return;
      }
      List var5 = this.itemPool();
      if (var5.isEmpty()) {
         this.report.append("[浏览] 商品源为空，跳过\n");
      } else {
         this.report.append("[浏览] 商品源 ").append(var5.size()).append(" 个\n");
         int var6 = 10;
         int var7 = 0;
         int var8 = 0;
         int var9 = 0;
         long var10 = 5000L;

         for (int var12 = 0; var12 < 60 && !this.abort; var12++) {
            if (var8 >= var5.size()) {
               this.report.append("[浏览] 商品列表用尽\n");
               break;
            } else {
               String var13 = (String)var5.get(var8);
               // 每步换一个商品：基础体重新组一次 + implId 走 objJson（避免手拼字段名，兼容闸 R3）
               JSONObject varReq = this.triggerBody(
                  var0scene,
                  var0[0],
                  var0.length > 2 ? var0[2] : null,
                  var0.length > 3 ? var0[3] : null,
                  parseObj(objJson("implId", var13))
               );

               JSONObject var14 = this.callApi(
                  "mtop.taobao.idle.task.uploadolivertasktrigger",
                  "1.0",
                  varReq.toString(),
                  "浏览上报 " + (var8 + 1) + "/" + var5.size()
               );
               String var15 = firstRet(var14);
               this.triggerNote("浏览|" + var1, var15);
               if (var14 == null) {
                  this.report.append("[浏览] 上报无响应，中止\n");
                  break;
               }

               if (var15 != null && var15.contains("SUCCESS")) {
                  var9 = 0;
                  var7++;
                  JSONObject var16 = var14.optJSONObject("data");
                  JSONObject var17 = var16 == null ? null : var16.optJSONObject("taskDTO");
                  JSONObject var18 = var17 == null ? null : var17.optJSONObject("progressDTO");
                  int var19 = var18 == null ? -1 : parseInt(optStr(var18, "times"), -1);
                  int var20 = var18 == null ? -1 : parseInt(optStr(var18, "needTimes"), -1);
                  if (var20 > 0) {
                     var6 = var20;
                  }

                  this.report
                     .append("[浏览] step ")
                     .append(var8)
                     .append(" ok okCount=")
                     .append(var7)
                     .append(" times=")
                     .append(var19)
                     .append("/")
                     .append(var20)
                     .append('\n');
                  if (var20 > 0 && var19 >= var20 || var7 >= var6) {
                     this.report.append("[浏览] 任务完成（").append(var7).append("次），开始领奖\n");
                     this.claimOne(var1, "浏览任务", var3, var4);
                     return;
                  }

                  var8 = var19 > var8 ? var19 : var8 + 1;
                  var10 = 5000L;
               } else {
                  if (var15 != null && var15.contains("ILLEGAL")) {
                     // v1.5.5 P1-3：3 次 → 2 次即中止并当日标记。三台日志实证该任务
                    // （deliveryId=3141）带 token 也必拒，纯真实行为类；每次中止省一轮退避 ≈13s，
                     // 当日后续轮次不再进浏览（省 ~45s/轮）。
                     if (++var9 >= 2) {
                        browseMarkDead();
                        this.report.append("[浏览] 连续 ").append(var9).append(" 次 ILLEGAL，任务需真实行为，当日不再重试\n");
                        if (this.lvlOnRun) {
                           LvlLedger.bump(var1, this.lvlBumpedThisRun);
                        }

                        return;
                     }

                     var10 = Math.min(var10 + 4000L, 16000L);
                     this.report.append("[浏览] step ").append(var8).append(" ILLEGAL ").append(var9).append("/2，退避 ").append(var10 / 1000L).append("s\n");
                     sleepJitter(var10);
                     continue;
                  }

                  this.report.append("[浏览] step ").append(var8).append(" -> ").append(var15).append("，换下一个\n");
                  var8++;
               }

               sleepJitter(var10);
            }
         }

         if (var7 >= var6) {
            this.report.append("[浏览] 达到次数（兜底判定），领奖\n");
            this.claimOne(var1, "浏览任务", var3, var4);
         }

         return;
      }
   }

   /**
    * 496 场景 trigger 请求体组装，返回 null 表示「这条别发」。
    *
    * 依据：2026-11-12 七份用户日志逐条对齐请求体与响应 —— uploadolivertasktrigger 的
    * 109 条失败里 103 条是 PARAM_ILLEGAL，失败请求恰好都是 fromToken/groupId 缺失的形状（列表
    * 没下发时旧代码照样发）。云端对这种请求固定回 PARAM_ILLEGAL，不扣任务次数，但每发一次
    * 就在报告里落一条红字，用户看到的是「满屏失败」而不是「这条本来就不该发」。
    *
    * 字段：sceneId（调用方给，496 场景传 "496"）/ deliveryId / fromToken / groupId，
    * 后三个任一为空 → null（调用方记 SKIP，不发请求）。
    * extra 用于 implId 一类附带字段，已存在的键不覆盖，保证核心字段优先级。
    */
   private JSONObject triggerBody(String var0, String var1, String var2, String var3, JSONObject var4) {
      if (var1 == null || var1.length() == 0 || var2 == null || var2.length() == 0 || var3 == null || var3.length() == 0) {
         return null;
      }
      JSONObject var5 = new JSONObject();
      try {
         try {
            var5.put("sceneId", Integer.parseInt(var0));
         } catch (Throwable var0e) {
            var5.put("sceneId", var0);
         }
         var5.put("deliveryId", var1);
         var5.put("fromToken", var2);
         try {
            var5.put("groupId", Long.parseLong(var3));
         } catch (Throwable var6) {
            var5.put("groupId", var3);
         }
      } catch (Throwable var7) {
         return null;
      }
      if (var4 != null) {
         java.util.Iterator<String> var8 = var4.keys();
         while (var8.hasNext()) {
            String var9 = var8.next();
            try {
               if (!var5.has(var9)) {
                  var5.put(var9, var4.get(var9));
               }
            } catch (Throwable ignored) {
            }
         }
      }
      return var5;
   }

   /** 毒化闸：同一任务类型连续 2 次 PARAM 类失败后本轮不再发（字段看着齐但云端不认的新变体兜底）。 */
   private boolean triggerBlocked(String var1) {
      Integer var2 = this.triggerPoison.get(var1);
      return var2 != null && var2.intValue() >= 2;
   }

   /** 记一次触发结果：SUCCESS 清零；PARAM_ILLEGAL / PARAM_INVALID 累加；其余原因不动计数。 */
   private void triggerNote(String var1, String var2) {
      if (var2 != null && var2.contains("SUCCESS")) {
         this.triggerPoison.remove(var1);
      } else if (var2 != null && var2.contains("PARAM")) {
         Integer var3 = this.triggerPoison.get(var1);
         this.triggerPoison.put(var1, Integer.valueOf((var3 == null ? 0 : var3.intValue()) + 1));
      }
   }

   private void claimOne(String var1, String var2, List<String> var3, List<String> var4) {
      // 风控锁前置：当日已判过人民币风控的 deliveryId 一次都不再打（见 rmbLocked 注释）
      if (LvlLedger.rmbLocked(var1)) {
         this.report.append("[领取] ").append(var2).append(" 跳过（人民币风控锁，需 App 内手动完成）\n");
         var4.add(var2 + "(" + var1 + ")");
         return;
      }
      // 额度闸：主列表判定过「当日额度已满」的任务，任何调用方（复核/收尾/浏览兜底）都拦在这里
      if (this.quotaFullIds.contains(var1)) {
         this.report.append("[领取] ").append(var2).append(" 跳过（当日额度已满，需 App 内手动完成）\n");
         return;
      }
      // 节奏整形（2026-11-15）：触发→领取拟人延迟 ~11.5s±40%（8~16s），提交完成立即领奖是最可疑的时序
      claimHumanGap();
      JSONObject var5 = this.callApi(
         "mtop.taobao.idle.task.getolivertaskbenefit",
         "1.0",
         // 2026-11-15 P3 逐字对比（_diag/p3_capture/00_SUMMARY.md）：
         //   App 真实请求 {"asac":"...","deliveryId":77912,"sceneId":496}，没有 fishAdCode / gps。
         //   模块原来多发这两个字段，与线上形态不一致，去掉。asac 由协议栈自动补齐，不用手写。
         objJson("sceneId", "496", "deliveryId", var1),
         "领奖 " + var2
      );
      String var6 = firstRet(var5);
      String var7 = "";
      if (var5 != null) {
         JSONObject var8 = var5.optJSONObject("data");
         JSONObject var9 = var8 == null ? null : var8.optJSONObject("taskDTO");
         if (var9 != null) {
            JSONArray var10 = var9.optJSONArray("rewards");
            var7 = var10 == null ? "" : var10.toString();
         }
      }

      boolean var11 = var6 != null && var6.contains("SUCCESS");
      // v1.4.4 修复（2026-11-11 主机侧）：SUCCESS ≠ 领到。实测 6 个任务（106456 等）
      // getolivertaskbenefit 返回 SUCCESS 但 taskBenefitDTO.needBenefit 仍 "true"、rewards 空——
      // 服务端没发奖。此场景按失败记账且【不 lvlBump】，把当日 2 次名额留给下一轮引擎重试；
      // 只有 needBenefit 变 false / 缺失（或 rewards 非空）才判真领到并 bump。
      boolean claimedReal = var11;
      if (var11) {
         try {
            JSONObject var8r = var5.optJSONObject("data");
            JSONObject var9r = var8r == null ? null : var8r.optJSONObject("taskDTO");
            if (var9r != null) {
               JSONObject var10b = var9r.optJSONObject("taskBenefitDTO");
               JSONArray var10rw = var9r.optJSONArray("rewards");
               boolean nbStill = var10b != null && "true".equals(var10b.optString("needBenefit", ""))
                     && (var10rw == null || var10rw.length() == 0);
               if (nbStill) claimedReal = false;
            }
         } catch (Throwable ignored) {
         }
      }
      if (claimedReal) {
         var3.add(var2 + "(" + var1 + ")");
         this.report.append("[领取] ").append(var2).append(" -> ").append(var6).append(" rewards=").append(var7).append('\n');
         toast("✓ " + var2 + coinSuffix(var7));
         if (this.lvlOnRun) {
            LvlLedger.bump(var1, this.lvlBumpedThisRun);
         }
      } else {
         var4.add(var2 + "(" + var1 + ")");
         if (var11) {
            // v1.5.5 P1-2 归因细分：SUCCESS 但未生效时按 ret / 任务状态分四类，
            // 报告直接可判「能不能等下一轮」，不再三台设备同一句文案。
            // 实测分布：needBenefit=true 时 status 多为 ACCEPTED/COMPLETE（批次2日志 12/22/7 次）。
            String varSt = "";
            try {
               JSONObject var8d = var5.optJSONObject("data");
               JSONObject var9d = var8d == null ? null : var8d.optJSONObject("taskDTO");
               varSt = var9d == null ? "" : var9d.optString("idleTaskStatus", "");
            } catch (Throwable ignored) {
            }
            String varWhy;
            if (var6 != null && var6.contains("RMB_CHECK")) {
               varWhy = "人民币风控，需 App 内手动完成";
            } else if (var6 != null && (var6.contains("TIMES") || var6.contains("LIMIT") || var6.contains("UPPER"))) {
               varWhy = "当日次数耗尽，今日不再重试";
            } else if ("COMPLETE".equals(varSt)) {
               varWhy = "任务已完成，奖励待云控发放，下轮自动复核";
            } else if ("AWARD".equals(varSt)) {
               varWhy = "已到可领状态，发放被云控拦截，下轮重试";
            } else {
               varWhy = "需真实行为或服务端延迟，下轮重试";
            }
            this.report.append("[领取] ").append(var2).append(" -> 未生效：").append(varWhy)
                  .append("（status=").append(varSt.length() == 0 ? "?" : varSt).append("）\n");
            log("[claim] not-effective: " + var2 + " (" + var1 + ") why=" + varWhy);
         } else {
            if (var6 != null && var6.contains("RMB_CHECK")) {
               // 服务端行为凭证闸：一次判定即上锁，本轮/收尾/后续跑批都不再重打
               LvlLedger.rmbLock(var1);
               this.report.append("[领取] ").append(var2)
                     .append(" -> 人民币风控拒绝（需真实广告/浏览行为），当日锁定不再重试\n");
            } else if (var6 != null && var6.contains("GATEWAY_REJECT")) {
               // v1.5.5 改进 C 衔接：网关拒绝不是业务失败，不锁不重试本轮，下一轮自然再来
               this.report.append("[领取] ").append(var2).append(" -> 网关拒绝（").append(var6)
                     .append("），下轮重试\n");
            } else if (var6 != null && var6.contains("NETWORK_LOST")) {
               // v1.5.71 P1-C: 连接层失败（-205/-405 包未到达）——弱网/换网/时段性断连，
               // 与网关拒绝分开写报告：用户看到「弱网」会去查网络，看到「网关拒绝」只会干等
               this.report.append("[领取] ").append(var2)
                     .append(" -> 连接层失败（弱网/断连，请求未到达），下轮重试\n");
            } else {
               this.report.append("[领取] ").append(var2).append(" -> ").append(var6 == null ? "无响应" : var6).append('\n');
            }
         }
      }
   }

   JSONObject callApi(String var1, String var2, String var3, String var4) {
      return callApi(var1, var2, var3, var4, false);
   }

   /**
    * @param skipCache true = 跳过 map.init 的 60s 复用，强制真实请求。
    *   只给「失败重试」路径用（LOCATION_NOT_MATCH 重 init / 位置未推进重 init / isNeedRefresh 刷新）——
    *   这些路径的语义就是「要拿服务端当前账本」，命中缓存等于没重试，
    *   11-19 日志里 6 次同参数空转就是这么来的。
    */
   JSONObject callApi(String var1, String var2, String var3, String var4, boolean skipCache) {
      // v1.5.5 改进 D：map.init 结果 60s 内复用（响应 6.9KB、分钟级内容不变）。
      // 静态缓存——coinBalanceLogger 每 30s new 一个实例，实例字段会失效。
      // 收益：探针请求量减半以上，直调频率降下来直接降低被 WAF 拦的概率（见批次2日志分析）。
      if (!skipCache && MapTasks.MAP_INIT_API.equals(var1)) {
         long varN = System.currentTimeMillis();
         long[] varT = MapTasks.MAP_INIT_TS;
         JSONObject varC = MapTasks.MAP_INIT_CACHE;
         if (varC != null && varN - varT[0] < 60000L) {
            log("[tasks] " + var4 + " -> (cache 60s) " + firstRet(varC));
            return varC;
         }
      }

      String var5 = this.respFile(var1);
      // v1.5.5 改进 C：清掉上一次请求可能遗留的 .bad，bad 只对「本次发送之后到达的拒绝」生效
      try {
         String varBase = com.piggyhand.fish.net.RespChannel.respBase(var1); // 方案 A:与 hook 写侧同规则
         java.io.File varBad0 = new java.io.File(this.cacheDir, "rh_resp_" + varBase + ".json.bad");
         if (varBad0.exists()) varBad0.delete();
      } catch (Throwable ignored) {
      }

      // 方案 B（备忘录 2026-11-13）：api 级互斥——同 api（oliver.show 多活动等）跨引擎串行，
      // 先发先收；不同 api 完全并行。锁覆盖 send+轮询全程。
      synchronized (com.piggyhand.fish.net.RespChannel.lockFor(var1)) {

      pace(var1); // 节奏整形：替代原固定 sleep(300L)——按 api 类别取用户设定的中心值 ±抖动
      String var6 = this.fileFp(var5);
      long varSentAt = System.currentTimeMillis();
      boolean var7 = ApiDirect.send(this.cl, var1, var2, var3);
      if (!var7) {
         log("[tasks] direct send failed: " + var1);
         return null;
      } else {
         long var8 = System.currentTimeMillis() + 30000L;

         while (System.currentTimeMillis() < var8 && !this.abort) {
            sleep(400L);
            // v1.5.5 改进 C：网关拒绝的响应非 JSON 会被 hook 丢弃并落 .bad——见到它立即短路，
            // 返回带归因的构造 ret，不再空等 30s。mtime 必须晚于发送时刻才算本次的拒绝。
            try {
               String varTailB = com.piggyhand.fish.net.RespChannel.respBase(var1);
               java.io.File varBad = new java.io.File(this.cacheDir, "rh_resp_" + varTailB + ".json.bad");
               if (varBad.exists() && varBad.lastModified() >= varSentAt) {
                  String varHead = varBad.length() > 0 ? this.readFile(varBad.getAbsolutePath()) : "";
                  varBad.delete();
                  // v1.5.71 P1-C: 连接层失败分流——.bad 里是 MtopResponse toString
                  // （responseCode=-205/-405、bytedata=null、headerFields=null）说明包根本没到服务端，
                  // 这不是网关拒绝：不误导熔断计数、不在报告里写「网关拒绝」。真网关拒绝才有 HTML/JSON 拒绝页原文。
                  boolean varNetLost = varHead.contains("responseCode=-205")
                          || varHead.contains("responseCode=-405")
                          || (varHead.contains("bytedata=null") && varHead.contains("headerFields=null"));
                  String varKind = varNetLost ? "NETWORK_LOST" : "GATEWAY_REJECT";
                  log("[tasks] " + var4 + " -> " + varKind + " (" + varHead.length() + "B)");
                  JSONObject varR = new JSONObject();
                  varR.put("ret", new JSONArray().put(varNetLost
                          ? "FAIL_NETWORK_LOST::连接层失败（非网关拒绝，包未到达）: "
                            + varHead.substring(0, Math.min(60, varHead.length()))
                          : "FAIL_GATEWAY_REJECT::网关拒绝非JSON响应: "
                            + varHead.substring(0, Math.min(60, varHead.length()))));
                  return varR;
               }
            } catch (Throwable varBadErr) {
            }

            String var10 = this.fileFp(var5);
            if (var10 != null && !var10.equals(var6)) {
               String var11 = this.readFile(var5);

               try {
                  JSONObject var12 = new JSONObject(var11);
                  String var13 = firstRet(var12);
                  log("[tasks] " + var4 + " -> " + var13);
                  // 改进 D：map.init 成功响应进 60s 缓存
                  if (MapTasks.MAP_INIT_API.equals(var1) && var13 != null && var13.contains("SUCCESS")) {
                     MapTasks.MAP_INIT_CACHE = var12;
                     MapTasks.MAP_INIT_TS[0] = System.currentTimeMillis();
                  }
                  return var12;
               } catch (Throwable var14) {
                  log("[tasks] resp parse fail " + var1 + ": " + var14);
                  return null;
               }
            }
         }

         log("[tasks] timeout: " + var4);
         return null;
      }
      } // synchronized lockFor(api)——方案 B 同 api 串行
   }

   private String respFile(String var1) {
      // 方案 A：api 全名短哈希命名，与 hook 写侧 writeTaskResp 同规则——
      // 替换原「api 末段」命名（五组尾段跨引擎撞车，见备忘录 2026-11-13）
      return this.cacheDir + "/rh_resp_" + com.piggyhand.fish.net.RespChannel.respBase(var1) + ".json";
   }

   private String fileFp(String var1) {
      try {
         // 优先用宿主写的 .stamp（毫秒戳）：/sdcard 的 mtime 只有 1~2s 精度，
         // 连续两次同接口且响应等长时 length:mtime 会撞成同一个值，被误判"没新响应"而超时。
         File var0 = new File(var1 + ".stamp");
         if (var0.exists() && var0.length() > 0L) {
            String var4 = new String(Files.readAllBytes(var0.toPath()), "UTF-8").trim();
            if (var4.length() > 0) {
               return "stamp:" + var4;
            }
         }
         File var2 = new File(var1);
         return var2.exists() && var2.length() != 0L ? var2.length() + ":" + var2.lastModified() : null;
      } catch (Throwable var3) {
         return null;
      }
   }

   String readFile(String var1) {
      try {
         FileInputStream var2 = new FileInputStream(var1);
         byte[] var3 = new byte[var2.available()];
         int var4 = var2.read(var3);
         var2.close();
         return var4 > 0 ? new String(var3, "UTF-8") : "";
      } catch (Throwable var5) {
         return "";
      }
   }

   void writeFile(String var1, String var2) {
      try {
         FileWriter var3 = new FileWriter(var1, false);
         var3.write(var2);
         var3.close();
      } catch (Throwable var4) {
         log("writeFile fail: " + var4);
      }
   }

   private List<JSONObject> extractTasks(JSONObject var1) {
      if (var1 == null) {
         return null;
      } else {
         JSONObject var2 = var1.optJSONObject("data");
         JSONArray var3 = var2 == null ? null : var2.optJSONArray("taskList");
         if (var3 == null) {
            return null;
         } else {
            ArrayList var4 = new ArrayList();

            for (int var5 = 0; var5 < var3.length(); var5++) {
               JSONObject var6 = var3.optJSONObject(var5);
               if (var6 != null) {
                  var4.add(var6);
               }
            }

            return var4;
         }
      }
   }

   private void finish(long var1) {
      this.mapTasks.doMapRoll();
      this.mapTasks.maybeClaimCycleReward(); // 收尾再查一次：跑批期间浏览/游戏攒的步数可能又过档
      this.report.append("耗时 ").append((System.currentTimeMillis() - var1) / 1000L).append("s\n");
      String var3 = this.report.toString();
      this.writeFile(this.reportDir + "/rh_tasks_result.txt", var3);
      log("=== task auto done ===\n" + var3);
      // 状态栏通知已移除（2026-11-16 用户指令）：报告仍落 rh_tasks_result.txt 供 REPORT 回读，toast 汇总保留
      int var4 = 0;
      int var5 = 0;

      for (String var9 : var3.split("\n")) {
         if (var9.startsWith("领取成功")) {
            var4 = parseCount(var9);
         } else if (var9.startsWith("领取失败")) {
            var5 = parseCount(var9);
         }
      }

      toast("钓鱼完成 · 成功 " + var4 + (var5 > 0 ? " · 失败 " + var5 : "") + " · 用时 " + (System.currentTimeMillis() - var1) / 1000L + "s");
   }

   static void sleep(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }
   }

   /** 设备节奏系数：已废弃（2026-11-19 定稿）。
    *  原实现 [0.7,1.5] 会乘走用户在滑块上填的值——填 2000 实际跑 1400~3000，
    *  「填了不准」。设备差异化改由首跑随机默认值 fixedPace() 承担。 */

   /** 中心值 ±paceAmp 抖动，不乘系数、不做硬地板截断 */
   private static long jitterMs(long center) {
      double amp = center * paceAmp;
      long v = center + (long) ((java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2D - 1D) * amp);
      return Math.max(1L, v); // 中心值最低 800、amp ≤ 60%，理论不会到 0；兜底防 sleep(负数) 抛 IllegalArgument
   }

   /** 发送前节奏等待：按 api 类别取中心值（领取 / 触发 / 查询），再叠用户设定的抖动百分比。
    *  收口点——所有 mtop 直调只经 callApi 一处，任务执行器零改动。 */
   private static void pace(String api) {
      long center;
      if (api.contains("benefit") || api.contains("claim") || api.contains("signin")) {
         center = paceClaimMs;
      } else if (api.contains("trigger") || api.contains("polish") || api.contains("bet") || api.contains("lottery")) {
         center = paceSubmitMs;
      } else {
         center = paceQueryMs;
      }
      sleep(jitterMs(center));
   }

   /** 触发→领取拟人延迟：用户设定中心值 ±抖动（默认 11.5s ±40%，约 7~16s） */
   private static void claimHumanGap() {
      sleep(jitterMs(paceClaimGapMs));
   }

   /** 执行器节奏等待：任意固定等待过 jitterMs（叠用户设定的抖动）——
    *  2026-11-15 节奏整形补全：执行器内固定字面量与浏览流步进间隔统一走这里，
    *  消除任务间等间隔脉冲；<1000ms 的轮询小间隔保持原 sleep 不抖。 */
   static void sleepJitter(long centerMs) {
      sleep(jitterMs(centerMs));
   }

   /**
    * 注入用户配置的节奏参数（launch 时每轮刷新一次）。
    * 五个键各自独立兜底：配置有值用配置值 → 没有取本机固化值 → 再没有随机生成并固化。
    * 引擎侧独立固化是为了覆盖「从不打开模块界面、只在 LSPosed 里勾选作用域」的设备，
    * 这类设备同样需要一组与他人不同的节奏，否则全量设备回到同一拍。
    */
   static void applyPace(ConfigManager cfg) {
      paceQueryMs = cfg != null && cfg.paceQueryMs > 0 ? cfg.paceQueryMs : fixedPace("q", 1200, 3000, PACE_Q_DEF);
      paceSubmitMs = cfg != null && cfg.paceSubmitMs > 0 ? cfg.paceSubmitMs : fixedPace("s", 1800, 4200, PACE_S_DEF);
      paceClaimMs = cfg != null && cfg.paceClaimMs > 0 ? cfg.paceClaimMs : fixedPace("c", 3000, 6000, PACE_C_DEF);
      paceClaimGapMs = cfg != null && cfg.paceClaimGapMs > 0 ? cfg.paceClaimGapMs : fixedPace("g", 8000, 15000, PACE_G_DEF);
      int amp = cfg != null && cfg.paceAmpPercent > 0
            ? cfg.paceAmpPercent
            : (int) fixedPace("a", 30, 50, PACE_A_DEF);
      paceAmp = Math.max(10, Math.min(60, amp)) / 100D;
      log("[pace] q=" + paceQueryMs + " s=" + paceSubmitMs + " c=" + paceClaimMs
            + " gap=" + paceClaimGapMs + " amp=" + Math.round(paceAmp * 100) + "%"
            + (cfg != null && cfg.paceManual ? " (manual)" : " (auto)"));
   }

   /**
    * 本机固化值：首跑在 [lo,hi] 内随机取一个写进 pref（rh_pace/<key>），之后每轮读同一个。
    * 用 int 存，与旧版 device_factor（String）不同键，互不影响。
    */
   private static long fixedPace(String key, int lo, int hi, long def) {
      try {
         Context c = appCtx;
         if (c != null) {
            android.content.SharedPreferences sp = c.getSharedPreferences("rh_pace", 0);
            int saved = sp.getInt(key, -1);
            if (saved > 0) return saved;
            int v = lo + java.util.concurrent.ThreadLocalRandom.current().nextInt(hi - lo + 1);
            sp.edit().putInt(key, v).apply();
            return v;
         }
      } catch (Throwable ignored) {
      }
      return def;
   }

   /**
    * v1.5.3 清除全部当日任务标记（socket CLEAR_MARKS 入口，实现在 LvlLedger——
    * 台账与标记文件同域；RUNNING 生命周期属引擎，参数化传入）。
    * @return 逐项结果清单（回显给 UI）
    */
   public static String clearDailyMarks() {
      Context ctx = appCtx != null ? appCtx : com.piggyhand.fish.core.RhRuntime.appCtx;
      return LvlLedger.clearDailyMarks(RUNNING.get(), ctx);
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }
}
