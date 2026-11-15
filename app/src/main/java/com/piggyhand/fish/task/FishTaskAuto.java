package com.piggyhand.fish.task;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;
import com.piggyhand.fish.hook.FishMtopHook;
import com.piggyhand.fish.net.ApiDirect;
import com.piggyhand.fish.net.RhReport;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification.BigTextStyle;
import android.app.Notification.Builder;
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
   private static final String MAP_INIT_API = "mtop.taobao.idle.treasure.hunt.map.init";
   private static final String MAP_ROLL_API = "mtop.taobao.idle.treasure.hunt.map.roll";
   // 圈数挑战每日步数奖励领取（11-10 抓包：宿主走到目标后弹领取按钮时发的就是这个）
   private static final String MAP_DAILY_REWARD_API = "mtop.taobao.idle.treasure.hunt.map.daily.reward";
   // 7天圈数挑战（11-10 实抓）：info 查档位状态，tierList[].status==AWARD 即可领；reward 按 cycle+recordId 领取
   private static final String CYCLE_INFO_API = "mtop.idle.interact.cycle.challenge.info";
   private static final String CYCLE_REWARD_API = "mtop.idle.interact.cycle.challenge.reward";
   // 探针发现「无任务可跑但圈数挑战有 AWARD 档」时置位，launch 切 claim_only 迷你跑批（跳浏览）
   private static volatile boolean cycleClaimOnly = false;
   private static final String PROP_LIST_API = "mtop.taobao.idle.play.prop.list";
   private static final String PROP_USE_API = "mtop.taobao.idle.play.prop.use";
   private static final String POLISH_API = "mtop.taobao.idle.item.polish";
   private static final String SHOP_BENEFIT_API = "mtop.taobao.idle.startup.shop.daily.benefit";
   private static final String LOTTERY_FEEDS_API = "mtop.taobao.idle.coin.lottery.feeds";
   private static final String LOTTERY_BET_API = "mtop.taobao.idle.coin.lottery.bet";
   private static final int REQ_TIMEOUT_S = 30;
   private static final int BROWSE_MAX_STEPS = 60;
   private final ClassLoader cl;
   /** 响应通道目录：宿主 FishMtopHook.writeCacheFile 落 rh_resp_*.json 的地方，必须是 getExternalCacheDir。
    *  只用来读写 rh_resp_*、rh_pref.json，不承载任何需要跨冷启动保留的东西。 */
   private final String cacheDir;
   /** 报告目录：持久区（files/）。宿主的 cache/ 会被冷启动清空，报告必须住这里。 */
   private final String reportDir;
   private final Set<String> processed = new HashSet<>();
   private final RhReport report = new RhReport();
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
   /** v1.5.5 改进 D：map.init 60s 结果缓存（静态——coinBalanceLogger 每 30s 新建实例）。
    *  只缓存 SUCCESS 响应；GATEWAY_REJECT/失败不缓存，失败立刻可重试。 */
   private static volatile JSONObject MAP_INIT_CACHE = null;
   private static final long[] MAP_INIT_TS = new long[]{0L};
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
   /** ---- 风控节奏整形（2026-11-15 用户定参）：触发 3s / 领取 5s / 查询 2s 中心值，
    *  ±40% 抖动，设备系数 [0.7,1.5] 首跑固化（pref rh_pace/device_factor）——
    *  消除「300ms 等间隔脉冲」与「全量设备同一节奏」两类最强机器特征。
    *  pace() 统一收口在 callApi 发送前，任务执行器零改动。 ---- */
   private static final long PACE_SUBMIT_MS = 3000L;
   private static final long PACE_CLAIM_MS = 5000L;
   private static final long PACE_QUERY_MS = 2000L;
   private static final double PACE_AMP = 0.4D;
   private static volatile double devicePaceFactor = Double.NaN;
   private static final Set<String> rejectedToday = ConcurrentHashMap.newKeySet();
   private static volatile String rejectedDay = "";
   // LVL 当日台账（lvlDay/lvlDir/lvlMap/lvlLoaded/LVL_FILE）与 RMB 锁已迁 LvlLedger（优化5簇1）；
   // K_* 键常量留这里（键的使用方在任务执行器）。
   private static final String K_SIGNIN = "@signin";
   private static final String K_PLAY_SIGN = "@playSignin";
   private static final String K_DAILY_REWARD = "@dailyReward";
   private final Set<String> lvlBumpedThisRun = new HashSet<>();
   private volatile boolean lvlOnRun = true;
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
            int var13 = (var14 == null || var14.cycleChallenge) ? probeCycleAward(var3) : 0;
            if (var6 == 0 && var13 > 0) {
               cycleClaimOnly = true;
               var6 = 1;
            } else {
               cycleClaimOnly = false;
            }

            return var6;
         }
      } catch (Throwable var12) {
         log("[probe] error: " + var12);
         return -1;
      }
   }

   /** 圈数挑战 tierList 里 status==AWARD 的档位数；info 不可用/通道撞车返回 0 */
   private static int probeCycleAward(FishTaskAuto var0) {
      try {
         JSONObject var1 = var0.callApi(CYCLE_INFO_API, "1.0", RhLocationContext.jsonPairObj(), "探测圈数");
         if (var1 == null || !CYCLE_INFO_API.equals(var1.optString("api"))) {
            return 0;
         }
         JSONObject var2 = var1.optJSONObject("data");
         JSONArray var3 = var2 == null ? null : var2.optJSONArray("tierList");
         if (var3 == null) {
            return 0;
         }
         int var4 = 0;
         java.util.Set<String> var7 = var0.readCycleClaimed();
         for (int var5 = 0; var5 < var3.length(); var5++) {
            JSONObject var6 = var3.optJSONObject(var5);
            if (var6 != null && "AWARD".equalsIgnoreCase(var6.optString("status", ""))
                  && !var7.contains(cycleDay() + "|" + parseNum(var6.optString("cycle", "0")))) {
               var4++;
            }
         }
         return var4;
      } catch (Throwable var7) {
         return 0;
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
            var1.maybeClaimCycleReward();
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

   private static void toast(String var0) {
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
                        String var13x = cycleClaimOnly ? "{\"claim_only\":true,\"polish\":false}" : "{}";
                        if (cycleClaimOnly) {
                           FishTaskAuto.log("[probe] cycle award pending -> claim_only run");
                        }
                        cycleClaimOnly = false;
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
      // 默认关；确需跑广告任务时显式传 {"adEvent":true}。
      boolean varAdEvent = opt(var4, "adEvent", false);
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
            this.doMapRoll();
            this.maybeClaimCycleReward(); // 掷骰推进圈数后查 AWARD 档位并领取
         }

         if (opt(var4, "bagUse", true)) {
            this.doBagUse();
         }

         if (var6) {
            this.runPolish();
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
            boolean varQuotaFull = LvlLedger.quotaFull(var26);
            if (varQuotaFull && var27 != null) {
               // 记入 var23 去重集，避免同一批内重复判定同一任务
               var23.add(var27);
               // 落台账：复核 / 收尾 / claimOne 三条后续路径都要认这张表，见 quotaFullIds 注释
               this.quotaFullIds.add(var27);
               this.report.append("[跳过] ").append(var27).append(" 当日额度已满（")
                     .append(varProg.optInt("times", 0)).append("/")
                     .append(varProg.optInt("maxTimes", 0)).append("），不发请求\n");
            } else if (var27 != null && !var23.contains(var27) && !rejectedToday.contains(var27)) {
               if (var11 && LvlLedger.skip(var27)) {
                  var24++;
               } else {
                  var23.add(var27);
                  JSONObject var28 = var26.optJSONObject("materialDTO");
                  JSONObject var29 = var26.optJSONObject("taskBenefitDTO");
                  String var30 = str(var26, "idleTaskStatus");
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
            var5.put("eventType", "complete");
            return var5.toString();
         }
      } catch (Throwable var6) {
         return null;
      }
   }

   private void runAdEventTask(String var1, String var2, List<String> var3, List<String> var4) {
      byte var5 = 4;
      int var6 = 0;
      int var7 = 0;

      for (int var8 = 0; var8 < var5 && !this.abort; var8++) {
         JSONObject var9 = this.callApi("mtop.taobao.idle.play.ad.event.push", "1.0", var2, "广告事件 " + (var8 + 1));
         String var10 = firstRet(var9);
         if (var9 == null) {
            var7++;
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 无响应（7.28.30 响应不落盘，继续）\n");
            sleepJitter(5000L);
         } else {
            if (var10 == null || !var10.contains("SUCCESS")) {
               this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 -> ").append(var10).append('\n');
               break;
            }

            var6++;
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 complete -> ").append(var10).append('\n');
            sleepJitter(5000L);
         }
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

   private void doMapRoll() {
      try {
         JSONObject var1 = this.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图状态");
         JSONObject var2 = var1 == null ? null : var1.optJSONObject("data");
         // 圈数挑战日奖：init 拿到 data 就顺手查一次可领状态（todayHasIssue=false 且服务端判定到点才发）
         this.maybeClaimDailyReward(var2);
         JSONObject var3 = var2 == null ? null : var2.optJSONObject("userInfo");
         if (var3 == null) {
            this.report.append("[前进钓鱼] 地图状态获取失败，跳过\n");
            return;
         }

         long var4 = (long)parseNum(var3.optString("location", "0"));
         int var6 = parseNum(var3.optString("remainStep", "0"));
         String var7 = "11";
         JSONArray var8 = var2.optJSONArray("mapResource");
         if (var8 != null && var8.length() > 0) {
            var7 = var8.optJSONObject(0).optString("mapVersion", "11");
         }

         if (var6 <= 0) {
            this.report.append("[前进钓鱼] 无剩余步数，跳过\n");
            return;
         }

         int var9 = 0;
         int var10 = 0;
         int var11 = 0;
         int var12 = 0;
         String var13 = null;

         while (var6 > 0 && var9 < 60) {
            String var14 = "{\"diceType\":\"FORWARD_STEP\","
               + RhLocationContext.jsonPair()
               + "\"locationBefore\":"
               + var4
               + ",\"mapVersion\":\""
               + var7
               + "\",\"outId\":"
               + System.currentTimeMillis()
               + ",\"version\":3}";
            JSONObject var15 = this.callApi("mtop.taobao.idle.treasure.hunt.map.roll", "1.0", var14, "前进掷骰");
            String var16 = firstRet(var15);
            if (var16 == null || !var16.contains("SUCCESS")) {
               var13 = var16 == null ? "无响应" : var16;
               break;
            }

            var9++;
            JSONObject var17 = var15.optJSONObject("data");
            if (var17 == null) {
               var13 = "响应无 data";
               break;
            }

            var10 += parseNum(var17.optString("diceResult", "0"));
            JSONArray var18 = var17.optJSONArray("contentList");
            if (var18 != null) {
               for (int var19 = 0; var19 < var18.length(); var19++) {
                  JSONObject var20 = var18.optJSONObject(var19);
                  if (var20 != null && "coin".equals(var20.optString("contentType"))) {
                     var11 += parseNum(var20.optString("rewardNum", "0"));
                  }
               }
            }

            JSONArray var28 = var17.optJSONArray("settleList");
            if (var28 != null) {
               for (int var29 = 0; var29 < var28.length(); var29++) {
                  JSONObject var21 = var28.optJSONObject(var29);
                  if (var21 != null && "treasureMapPoint".equals(var21.optString("contentType"))) {
                     var11 += parseNum(var21.optString("rewardNum", "0"));
                  }
               }
            }

            long var30 = var17.optJSONObject("userInfo") == null
               ? var4
               : (long)parseNum(var17.optJSONObject("userInfo").optString("location", String.valueOf(var4)));
            var6 = stepOf(var17);
            boolean var22 = "true".equalsIgnoreCase(var17.optString("isNeedRefresh", "false"));
            if (var30 == var4 && var6 > 0) {
               if (var12 >= 5) {
                  var13 = "位置未推进";
                  break;
               }

               var12++;
               JSONObject var31 = this.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图重试");
               JSONObject var32 = var31 == null ? null : var31.optJSONObject("data");
               JSONObject var33 = var32 == null ? null : var32.optJSONObject("userInfo");
               if (var33 == null) {
                  var13 = "重试初始化失败";
                  break;
               }

               var4 = (long)parseNum(var33.optString("location", String.valueOf(var4)));
               var6 = stepOf(var31);
            } else {
               var4 = var30;
               if (var22) {
                  if (var12 >= 5) {
                     var13 = "刷新超限";
                     break;
                  }

                  var12++;
                  JSONObject var23 = this.callApi(
                     "mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图刷新"
                  );
                  JSONObject var24 = var23 == null ? null : var23.optJSONObject("data");
                  JSONObject var25 = var24 == null ? null : var24.optJSONObject("userInfo");
                  if (var25 == null) {
                     var13 = "刷新地图失败";
                     break;
                  }

                  var4 = (long)parseNum(var25.optString("location", String.valueOf(var30)));
                  var6 = stepOf(var23);
                  JSONArray var26 = var24.optJSONArray("mapResource");
                  if (var26 != null && var26.length() > 0) {
                     var7 = var26.optJSONObject(0).optString("mapVersion", var7);
                  }
               }
            }
         }

         if (var9 == 0) {
            this.report.append("[前进钓鱼] ").append(var13 == null ? "掷骰失败" : var13).append('\n');
         } else {
            this.report
               .append("[前进钓鱼] 掷骰 ")
               .append(var9)
               .append(" 次 · 前进 ")
               .append(var10)
               .append(" 步 · 收币 ")
               .append(var11)
               .append(" · 剩余步数 ")
               .append(var6)
               .append(var13 != null ? "（中断:" + var13 + "）" : "")
               .append('\n');
            toast("✓ 前进钓鱼 +" + var11 + "币");
            log("[map-roll] rolls=" + var9 + " steps=" + var10 + " coins=" + var11 + " remain=" + var6);
         }
      } catch (Throwable var27) {
         this.report.append("[前进钓鱼] 异常 ").append(var27).append('\n');
      }
   }

   /**
    * 圈数挑战每日步数奖励：map.init 的 data.dailyReward 携带今日发放状态。
    * todayHasIssue=true → 今日已发，跳过；否则打一发 daily.reward 让服务端判定
    * （未到点服务端回 FAIL，无副作用）。主跑批 + finish 收尾各有一次 init，天然一日最多两次尝试。
    * LVL 机制（@dailyReward）：领取成功 / 已发放各记一次，累计 2 次后当日所有后续批次直接跳过；
    * FAIL 不记档，后续批次照常重试（步数可能晚点才达标）。
    */
   private void maybeClaimDailyReward(JSONObject var0) {
      try {
         // LVL 闸门：今日累计判定 2 次（领取成功 / 已发放）后整天不再尝试，省掉每轮跑批的重复调用
         if (this.lvlOnRun && LvlLedger.skip(K_DAILY_REWARD)) {
            return;
         }
         JSONObject var1 = var0 == null ? null : var0.optJSONObject("dailyReward");
         if (var1 == null) {
            return; // 老版本页面无此字段，直接跳过
         }
         if ("true".equalsIgnoreCase(var1.optString("todayHasIssue", "false"))) {
            // 今日已发放 = 服务端判定完成，记一次 LVL（与 signin 的 SIGNED 分支同款语义）
            if (this.lvlOnRun) {
               LvlLedger.bump(K_DAILY_REWARD, this.lvlBumpedThisRun);
            }
            return;
         }
         String var2 = RhLocationContext.jsonPairObj();
         JSONObject var3 = this.callApi(MAP_DAILY_REWARD_API, "1.0", var2, "圈数日奖");
         String var4 = firstRet(var3);
         boolean var5 = var4 != null && var4.contains("SUCCESS");
         int var6 = parseNum(var1.optString("todayNum", "0"));
         if (var5) {
            this.report.append("[圈数日奖] SUCCESS +").append(var6).append("币\n");
            toast("✓ 圈数挑战日奖 +" + var6 + "币");
            log("[daily-reward] claimed +" + var6);
            // 领取成功记一次 LVL；FAIL（未到点/步数不足）不记，后续跑批继续重试
            if (this.lvlOnRun) {
               LvlLedger.bump(K_DAILY_REWARD, this.lvlBumpedThisRun);
            }
         } else {
            // 未到点/无可领，只落日志不进报告，避免噪音
            log("[daily-reward] not claimable: " + (var4 == null ? "无响应" : var4));
         }
      } catch (Throwable var7) {
         this.report.append("[圈数日奖] 异常 ").append(var7).append('\n');
      }
   }

   /**
    * 7天圈数挑战奖励：cycle.challenge.info 返回 tierList，status==AWARD 的档位逐个领。
    * 请求体 {cycle, recordId, fishAdCode, gps}；响应 {rewardTitle, success}。
    * 调用时机：doMapRoll 之后（掷骰推进圈数后查才有新 AWARD）+ finish 收尾。
    * 注意：info 尾段与 treasure.layout.info 同落 rh_resp_info.json，用响应自带 api 字段校验防误读。
    */
   private void maybeClaimCycleReward() {
      try {
         // UI 开关（常用功能「7天圈数挑战」）：关了就不查档不领取
         ConfigManager var15 = ConfigManager.load();
         if (var15 != null && !var15.cycleChallenge) {
            log("[cycle-challenge] 开关关闭，跳过");
            return;
         }
         JSONObject var0 = this.callApi(CYCLE_INFO_API, "1.0", RhLocationContext.jsonPairObj(), "圈数挑战");
         if (var0 == null || !CYCLE_INFO_API.equals(var0.optString("api"))) {
            log("[cycle-challenge] info 不可用（超时或通道撞车）");
            return;
         }
         JSONObject var1 = var0.optJSONObject("data");
         if (var1 == null) {
            log("[cycle-challenge] info 无 data（活动未开启）");
            return;
         }
         String var2 = var1.optString("recordId", "");
         int var3 = parseNum(var1.optString("currentCycle", "0"));
         JSONArray var4 = var1.optJSONArray("tierList");
         if (var4 == null || var2.length() == 0) {
            log("[cycle-challenge] tierList/recordId 缺失");
            return;
         }
         // 本地领取去重：服务端发奖异步，status 短时间仍显示 AWARD，必须按档记档防重复领取
         java.util.Set<String> var14 = readCycleClaimed();
         for (int var5 = 0; var5 < var4.length(); var5++) {
            JSONObject var6 = var4.optJSONObject(var5);
            // v1.4.4 修复（2026-11-11 主机侧）：REACHED（已到达未领）也算可领——
            // 实测 cycle=18 REACHED 2000 币手动 CALL reward 成功到账，原逻辑只认 AWARD 漏领
            if (var6 != null && ("AWARD".equalsIgnoreCase(var6.optString("status", ""))
                    || "REACHED".equalsIgnoreCase(var6.optString("status", "")))) {
               int var7 = parseNum(var6.optString("cycle", "0"));
               if (var14.contains(cycleDay() + "|" + var7)) {
                  log("[cycle-challenge] cycle=" + var7 + " 今日已领（本地档），跳过");
                  continue;
               }
               String var8 = var6.optString("rewardTitle", "");
               String var9 = "{\"cycle\":" + var7
                  + "," + RhLocationContext.jsonPair()
                  + "\"recordId\":\"" + var2 + "\"}";
               JSONObject var10 = this.callApi(CYCLE_REWARD_API, "1.0", var9, "圈数奖励");
               String var11 = firstRet(var10);
               boolean var12 = var10 != null && var11 != null && var11.contains("SUCCESS");
               if (var12) {
                  markCycleClaimed(var7);
                  this.report.append("[圈数挑战] 领取 第").append(var7).append("圈 ")
                     .append(var8).append("（当前").append(var3).append("圈）\n");
                  toast("✓ 圈数挑战 " + var8);
                  log("[cycle-challenge] claimed cycle=" + var7 + " " + var8);
               } else {
                  log("[cycle-challenge] claim fail cycle=" + var7 + ": " + (var11 == null ? "无响应" : var11));
               }
            }
         }
      } catch (Throwable var13) {
         this.report.append("[圈数挑战] 异常 ").append(var13).append('\n');
      }
   }

   /** 本地领取档 key：yyMMdd|cycle，文件 cacheDir/rh_cycle_claim.txt，跨天自然失效（读时按 day 前缀过滤） */
   private static String cycleDay() {
      java.text.SimpleDateFormat var0 = new java.text.SimpleDateFormat("yyMMdd");
      return var0.format(new java.util.Date());
   }

   private java.util.Set<String> readCycleClaimed() {
      java.util.Set<String> var0 = new java.util.HashSet<>();
      try {
         String var1 = this.readFile(this.cacheDir + "/rh_cycle_claim.txt");
         if (var1 != null) {
            for (String var3 : var1.split("\n")) {
               if (var3.startsWith(cycleDay() + "|")) {
                  var0.add(var3.trim());
               }
            }
         }
      } catch (Throwable ignored) {
      }
      return var0;
   }

   private void markCycleClaimed(int var0) {
      try {
         String var1 = this.readFile(this.cacheDir + "/rh_cycle_claim.txt");
         String var2 = (var1 == null ? "" : var1.trim() + "\n") + cycleDay() + "|" + var0 + "\n";
         this.writeFile(this.cacheDir + "/rh_cycle_claim.txt", var2);
      } catch (Throwable ignored) {
      }
   }

   private void doBagUse() {
      try {
         ConfigManager var1 = ConfigManager.load();
         if (var1 == null) {
            return;
         }

         Object[][] var2 = new Object[][]{
            {"LUCKY_DICE", var1.bagLuckOn, var1.bagLuckNum, "好运骰"},
            {"SURFACE8_DICE", var1.bag8On, var1.bag8Num, "八面骰"},
            {"SURFACE12_DICE", var1.bag12On, var1.bag12Num, "十二面骰"},
            {"ROCKET_DICE", var1.bagRocketOn, var1.bagRocketNum, "火箭骰子"},
            {"DOUBLE_DICE", var1.bagDoubleOn, var1.bagDoubleNum, "双倍骰子"},
            {"BENEFIT_GGK", var1.bagGgkOn, var1.bagGgkNum, "刮刮卡"},
            {"BENEFIT_DZP_SPRING", var1.bagDzpOn, var1.bagDzpNum, "大转盘"}
         };
         boolean var3 = false;

         for (Object[] var7 : var2) {
            if ((Boolean)var7[1]) {
               var3 = true;
               break;
            }
         }

         if (!var3) {
            return;
         }

         JSONObject var39 = this.callApi(
            "mtop.taobao.idle.play.prop.list",
            "1.0",
            objJson("bizCode", "coinMap", "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
            "背包列表"
         );
         JSONObject var40 = var39 == null ? null : var39.optJSONObject("data");
         JSONArray var41 = var40 == null ? null : var40.optJSONArray("propInfos");
         if (var41 == null) {
            this.report.append("[背包] 列表获取失败，跳过\n");
            return;
         }

         long var42 = 0L;
         String var9 = "11";
         boolean var10 = false;
         JSONObject var11 = this.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "背包地图");
         JSONObject var12 = var11 == null ? null : var11.optJSONObject("data");
         JSONObject var13 = var12 == null ? null : var12.optJSONObject("userInfo");
         if (var13 != null) {
            var42 = (long)parseNum(var13.optString("location", "0"));
            var10 = true;
         }

         JSONArray var14 = var12 == null ? null : var12.optJSONArray("mapResource");
         if (var14 != null && var14.length() > 0) {
            var9 = var14.optJSONObject(0).optString("mapVersion", var9);
         }

         for (Object[] var18 : var2) {
            String var19 = (String)var18[0];
            boolean var20 = (Boolean)var18[1];
            int var21 = (Integer)var18[2];
            String var22 = (String)var18[3];
            if (var20) {
               int var23 = 0;
               String var24 = null;
               String var25 = null;
               // 大转盘每张独立 propId（amount 各 1），逐张核销必须按张取 id；GGK 仍沿用首个 id
               java.util.ArrayList<String> varIds = new java.util.ArrayList<String>();

               for (int var26 = 0; var26 < var41.length(); var26++) {
                  JSONObject var27 = var41.optJSONObject(var26);
                  if (var27 != null && var19.equals(var27.optString("propType"))) {
                     var23 += parseNum(var27.optString("amount", "0"));
                     if (var24 == null) {
                        var24 = var27.optString("configId", null);
                     }

                     if (var25 == null) {
                        var25 = var27.optString("id", null);
                     }

                     if (var27.optString("id", null) != null) {
                        varIds.add(var27.optString("id"));
                     }
                  }
               }

               if (var24 != null && var23 > 0) {
                  int var43 = var21 < 0 ? 0 : Math.max(0, var23 - var21);
                  if (var23 <= var43) {
                     this.report.append("[背包] ").append(var22).append(" 已达目标（剩 ").append(var23).append("）\n");
                  } else {
                     // GGK 与 DZP 走 prop.use 直兑；其余（骰子）走地图掷骰，需地图状态可用
                     boolean var44 = !"BENEFIT_GGK".equals(var19) && !"BENEFIT_DZP_SPRING".equals(var19);
                     if (var44 && !var10) {
                        this.report.append("[背包] ").append(var22).append(" 地图状态不可用，跳过\n");
                     } else {
                        int var28 = var23 - var43;
                        int var29 = 0;
                        int var30 = 0;
                        int var31 = 0;
                        String var32 = null;
                        int var33 = 0;

                        while (var29 < var28 && var33++ < 120) {
                           if (var44) {
                              JSONObject var45 = this.callApi(
                                 "mtop.taobao.idle.treasure.hunt.map.roll",
                                 "1.0",
                                 "{\"diceId\":"
                                    + var24
                                    + ",\"diceType\":\""
                                    + var19
                                    + "\","
                                    + RhLocationContext.jsonPair()
                                    + "\"locationBefore\":"
                                    + var42
                                    + ",\"mapVersion\":\""
                                    + var9
                                    + "\",\"outId\":"
                                    + System.currentTimeMillis()
                                    + ",\"version\":3}",
                                 "背包掷骰"
                              );
                              String var47 = firstRet(var45);
                              if (var47 == null || !var47.contains("SUCCESS")) {
                                 var32 = var47 == null ? "无响应" : var47;
                                 break;
                              }

                              JSONObject var36 = var45.optJSONObject("data");
                              int var37 = var36 == null ? 0 : parseNum(var36.optString("diceResult", "0"));
                              var30 += var37;
                              var42 += (long)var37;
                              var31 += rollCoins(var36);
                           } else {
                              // DZP：第 var29 次使用取第 var29 张的 id（快照序）；GGK 沿用首个 id
                              String varUseId = var25;
                              if ("BENEFIT_DZP_SPRING".equals(var19) && varIds.size() > 0) {
                                 varUseId = (String)varIds.get(Math.min(var29, varIds.size() - 1));
                              }

                              JSONObject var34 = this.callApi(
                                 "mtop.taobao.idle.play.prop.use",
                                 "1.0",
                                 objJson(
                                    "id",
                                    varUseId,
                                    "bizCode",
                                    "coinMap",
                                    "configId",
                                    var24,
                                    "outId",
                                    "rh" + System.currentTimeMillis(),
                                    "fishAdCode",
                                    RhLocationContext.fishAdCode(),
                                    "gps",
                                    RhLocationContext.gps()
                                 ),
                                 "背包" + ("BENEFIT_DZP_SPRING".equals(var19) ? "转盘" : "刮卡")
                              );
                              String var35 = firstRet(var34);
                              if (var35 == null || !var35.contains("SUCCESS")) {
                                 var32 = var35 == null ? "无响应" : var35;
                                 break;
                              }

                              var31 += ggkReward(var34.optJSONObject("data"));
                              sleep(400L);
                           }

                           var29++;
                           sleepJitter(1200L);
                        }

                        StringBuilder var46 = new StringBuilder("[背包] ").append(var22).append(" 使用 ").append(var29).append("/").append(var28);
                        if (var44) {
                           var46.append(" · 前进 ").append(var30).append(" 步");
                        }

                        var46.append(" · 收益 ").append(var31);
                        if (var32 != null) {
                           var46.append(" · 中断:").append(var32);
                        }

                        this.report.append(var46).append('\n');
                        if (var29 > 0) {
                           toast("✓ 背包 " + var22 + " 用 " + var29 + " 个");
                        }

                        log(
                           "[bag-use] "
                              + var22
                              + " used="
                              + var29
                              + "/"
                              + var28
                              + (var44 ? " steps=" + var30 : "")
                              + " coins="
                              + var31
                              + (var32 != null ? " err=" + var32 : "")
                        );
                     }
                  }
               } else {
                  this.report.append("[背包] ").append(var22).append(" 背包无货，跳过\n");
               }
            }
         }
      } catch (Throwable var38) {
         this.report.append("[背包] 异常 ").append(var38).append('\n');
      }
   }

   private static int rollCoins(JSONObject var0) {
      if (var0 == null) {
         return 0;
      } else {
         int var1 = 0;
         JSONArray var2 = var0.optJSONArray("contentList");
         if (var2 != null) {
            for (int var3 = 0; var3 < var2.length(); var3++) {
               JSONObject var4 = var2.optJSONObject(var3);
               if (var4 != null && "coin".equals(var4.optString("contentType"))) {
                  var1 += parseNum(var4.optString("rewardNum", "0"));
               }
            }
         }

         JSONArray var6 = var0.optJSONArray("settleList");
         if (var6 != null) {
            for (int var7 = 0; var7 < var6.length(); var7++) {
               JSONObject var5 = var6.optJSONObject(var7);
               if (var5 != null && "treasureMapPoint".equals(var5.optString("contentType"))) {
                  var1 += parseNum(var5.optString("rewardNum", "0"));
               }
            }
         }

         return var1;
      }
   }

   private static int ggkReward(JSONObject var0) {
      if (var0 == null) {
         return 0;
      } else {
         JSONObject var1 = var0.optJSONObject("attributes");
         JSONObject var2 = var1 == null ? null : var1.optJSONObject("benefitResult");
         if (var2 == null) {
            return 0;
         } else {
            JSONObject var3 = var2.optJSONObject("extraData");
            return var3 == null ? 0 : parseNum(var3.optString("amount", "0"));
         }
      }
   }

   private static int stepOf(JSONObject var0) {
      int var1 = parseNum(var0.optString("remainStep", ""));
      if (var1 > 0) {
         return var1;
      } else {
         JSONObject var2 = var0.optJSONObject("userInfo");
         return var2 == null ? 0 : parseNum(var2.optString("remainStep", "0"));
      }
   }

   private void runPolish() {
      String var1 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
      String var2 = this.cacheDir + "/rh_pref.json";
      JSONObject var3 = parseObj(this.readFile(var2).trim().isEmpty() ? "{}" : this.readFile(var2));
      if (var1.equals(var3.optString("polish_date"))) {
         this.report.append("[擦亮] 今日已擦亮（每日一次标记），跳过\n");
      } else {
         JSONObject var4 = this.callApi("mtop.taobao.idle.item.polish", "2.0", "{\"type\":\"originaljson\"}", "擦亮");
         String var5 = firstRet(var4);
         int var6 = 0;
         if (var4 != null) {
            JSONObject var7 = var4.optJSONObject("data");
            JSONArray var8 = var7 == null ? null : var7.optJSONArray("exposureItems");
            if (var8 != null) {
               var6 = var8.length();
            }
         }

         if (var5 != null && var5.contains("SUCCESS")) {
            this.report.append(var6 > 0 ? "[擦亮] SUCCESS：已擦亮 " + var6 + " 个宝贝\n" : "[擦亮] SUCCESS：服务端受理（今日已擦过或无可擦宝贝）\n");
            if (var6 > 0) {
               toast("✓ 擦亮 " + var6 + " 个宝贝");
            }

            this.markPolished(var2, var3, var1);
         } else if (var5 != null && var5.contains("POLISH_AGAIN")) {
            this.report.append("[擦亮] 今日已擦亮过（服务端判重）\n");
            this.markPolished(var2, var3, var1);
         } else if (var5 != null && var5.contains("POLISH_FAILED3")) {
            this.report.append("[擦亮] 今日擦亮次数已用完（服务端限制）\n");
            this.markPolished(var2, var3, var1);
         } else {
            this.report.append("[擦亮] ").append(var5 == null ? "无响应，下次重试" : var5).append('\n');
         }
      }
   }

   private void markPolished(String var1, JSONObject var2, String var3) {
      try {
         var2.put("polish_date", var3);
         this.writeFile(var1, var2.toString());
      } catch (Throwable var5) {
         log("markPolished fail: " + var5);
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
            } else {
               this.report.append("[领取] ").append(var2).append(" -> ").append(var6 == null ? "无响应" : var6).append('\n');
            }
         }
      }
   }

   JSONObject callApi(String var1, String var2, String var3, String var4) {
      // v1.5.5 改进 D：map.init 结果 60s 内复用（响应 6.9KB、分钟级内容不变）。
      // 静态缓存——coinBalanceLogger 每 30s new 一个实例，实例字段会失效。
      // 收益：探针请求量减半以上，直调频率降下来直接降低被 WAF 拦的概率（见批次2日志分析）。
      if (MAP_INIT_API.equals(var1)) {
         long varN = System.currentTimeMillis();
         long[] varT = MAP_INIT_TS;
         JSONObject varC = MAP_INIT_CACHE;
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

      pace(var1); // 节奏整形：替代原固定 sleep(300L)——按 api 类别 2/3/5s ×设备系数 ±40% 抖动
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
                  log("[tasks] " + var4 + " -> GATEWAY_REJECT (" + varHead.length() + "B)");
                  JSONObject varR = new JSONObject();
                  varR.put("ret", new JSONArray().put("FAIL_GATEWAY_REJECT::网关拒绝非JSON响应: "
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
                  if (MAP_INIT_API.equals(var1) && var13 != null && var13.contains("SUCCESS")) {
                     MAP_INIT_CACHE = var12;
                     MAP_INIT_TS[0] = System.currentTimeMillis();
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

   private String readFile(String var1) {
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

   private void writeFile(String var1, String var2) {
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
      this.doMapRoll();
      this.maybeClaimCycleReward(); // 收尾再查一次：跑批期间浏览/游戏攒的步数可能又过档
      this.report.append("耗时 ").append((System.currentTimeMillis() - var1) / 1000L).append("s\n");
      String var3 = this.report.toString();
      this.writeFile(this.reportDir + "/rh_tasks_result.txt", var3);
      log("=== task auto done ===\n" + var3);
      this.notifyDone(var3);
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

   private void notifyDone(String var1) {
      try {
         Context var2 = appCtx;
         if (var2 == null) {
            return;
         }

         NotificationManager var3 = (NotificationManager)var2.getSystemService("notification");
         if (var3 == null) {
            return;
         }

         String var4 = "reward_hook";
         if (VERSION.SDK_INT >= 26) {
            var3.createNotificationChannel(new NotificationChannel(var4, "任务引擎结果", 3));
         }

         int var5 = 0;
         int var6 = 0;

         for (String var10 : var1.split("\n")) {
            if (var10.startsWith("领取成功")) {
               var5 = parseCount(var10);
            } else if (var10.startsWith("领取失败")) {
               var6 = parseCount(var10);
            }
         }

         String var12 = var5 <= 0 && var6 <= 0 ? "钓鱼完成（无领取动作）" : "领取 " + var5 + " 笔" + (var6 > 0 ? "，失败 " + var6 + " 笔" : "");
         Builder var13 = VERSION.SDK_INT >= 26 ? new Builder(var2, var4) : new Builder(var2);
         var13.setSmallIcon(17301584)
            .setContentTitle("咸鱼小猪手 · " + var12)
            .setContentText(var12)
            .setStyle(new BigTextStyle().bigText(var1.length() > 3500 ? var1.substring(0, 3500) : var1))
            .setAutoCancel(true);
         var3.notify(1001, var13.build());
         log("notification sent: " + var12);
      } catch (Throwable var11) {
         log("notify FAILED: " + var11);
      }
   }

   private static void sleep(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }
   }

   /** 设备节奏系数：首跑随机 [0.7,1.5] 并固化到 pref（rh_pace/device_factor），
    *  之后每台设备有自己稳定的中心节奏——同参数设备轨迹互不相同。 */
   private static double paceFactor() {
      double f = devicePaceFactor;
      if (f == f) return f;
      synchronized (FishTaskAuto.class) {
         if (devicePaceFactor == devicePaceFactor) return devicePaceFactor;
         double v = 0.7D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.8D;
         try {
            Context c = appCtx;
            if (c != null) {
               android.content.SharedPreferences sp = c.getSharedPreferences("rh_pace", 0);
               String saved = sp.getString("device_factor", null);
               if (saved != null) {
                  try {
                     v = Double.parseDouble(saved);
                  } catch (Throwable ignored) {
                  }
               } else {
                  sp.edit().putString("device_factor", String.valueOf(v)).apply();
               }
            }
         } catch (Throwable ignored) {
         }
         devicePaceFactor = v;
         return v;
      }
   }

   /** 中心值 ±40% 抖动，下限 800ms 防抖出瞬时连发 */
   private static long jitterMs(long center) {
      long v = (long) (center * paceFactor());
      double amp = v * PACE_AMP;
      return Math.max(800L, v + (long) ((java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2D - 1D) * amp));
   }

   /** 发送前节奏等待：按 api 类别取中心值（领取 5s / 触发 3s / 查询 2s）×设备系数±40%。
    *  收口点——所有 mtop 直调只经 callApi 一处，任务执行器零改动。 */
   private static void pace(String api) {
      long center;
      if (api.contains("benefit") || api.contains("claim") || api.contains("signin")) {
         center = PACE_CLAIM_MS;
      } else if (api.contains("trigger") || api.contains("polish") || api.contains("bet") || api.contains("lottery")) {
         center = PACE_SUBMIT_MS;
      } else {
         center = PACE_QUERY_MS;
      }
      sleep(jitterMs(center));
   }

   /** 触发→领取拟人延迟：中心 11.5s±40%（约 7~16s），模拟「人完成任务再回来领」的间隔 */
   private static void claimHumanGap() {
      sleep(jitterMs(11500L));
   }

   /** 执行器节奏等待：任意固定等待过 jitterMs（设备系数×±40% 抖动）——
    *  2026-11-15 节奏整形补全：执行器内 18 处固定字面量与浏览流步进间隔统一走这里，
    *  消除任务间等间隔脉冲；<1000ms 的轮询小间隔保持原 sleep 不抖。 */
   private static void sleepJitter(long centerMs) {
      sleep(jitterMs(centerMs));
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
