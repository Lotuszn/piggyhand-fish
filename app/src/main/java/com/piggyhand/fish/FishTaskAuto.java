package com.piggyhand.fish;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

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
   private static final String PROP_LIST_API = "mtop.taobao.idle.play.prop.list";
   private static final String PROP_USE_API = "mtop.taobao.idle.play.prop.use";
   private static final String POLISH_API = "mtop.taobao.idle.item.polish";
   private static final String SHOP_BENEFIT_API = "mtop.taobao.idle.startup.shop.daily.benefit";
   private static final String LOTTERY_FEEDS_API = "mtop.taobao.idle.coin.lottery.feeds";
   private static final String LOTTERY_BET_API = "mtop.taobao.idle.coin.lottery.bet";
   private static final int REQ_TIMEOUT_S = 30;
   private static final int BROWSE_MAX_STEPS = 60;
   private static final long BROWSE_GAP_MS = 5000L;
   private final ClassLoader cl;
   private final String cacheDir;
   private final Set<String> processed = new HashSet<>();
   private final RhReport report = new RhReport();
   private volatile boolean abort = false;
   private static Context appCtx;
   private static volatile boolean toastOn = true;
   private static volatile boolean polishOn = true;
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
   private static final long PROBE_INTERVAL_MS = 900000L;
   private static final long PROBE_BURST_MS = 300000L;
   private static final long PROBE_FAIL_MS = 180000L;
   private static final Set<String> rejectedToday = ConcurrentHashMap.newKeySet();
   private static volatile String rejectedDay = "";
   private static final String LVL_FILE = "rh_task_lvl.json";
   private static final String K_SIGNIN = "@signin";
   private static final String K_PLAY_SIGN = "@playSignin";
   private static final int LVL_MAX = 2;
   private static volatile String lvlDay = "";
   private static volatile String lvlDir = null;
   private static final Map<String, Integer> lvlMap = new ConcurrentHashMap<>();
   private final Set<String> lvlBumpedThisRun = new HashSet<>();
   private volatile boolean lvlOnRun = true;
   private static volatile boolean lvlLoaded = false;
   private List<String> itemPool = null;
   private static final Object COIN_LOG_LOCK = new Object();
   private static volatile long coinLogLastVal = -1L;
   private static volatile long coinLogLastAt = 0L;
   private static String coinLogLastDay = "";
   private static String coinLogCleanedDay = "";

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

   private FishTaskAuto(ClassLoader var1, String var2) {
      this.cl = var1;
      this.cacheDir = var2;
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

   private static String dayOf(long var0) {
      return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(var0));
   }

   private static String lvlDirPath(Context var0) {
      if (var0 != null) {
         try {
            File var1 = var0.getExternalFilesDir(null);
            if (var1 != null) {
               if (!var1.exists()) {
                  var1.mkdirs();
               }

               if (var1.isDirectory() && var1.canWrite()) {
                  return var1.getAbsolutePath();
               }
            }
         } catch (Throwable var3) {
         }

         try {
            File var4 = var0.getFilesDir();
            if (var4 != null && var4.isDirectory()) {
               return var4.getAbsolutePath();
            }
         } catch (Throwable var2) {
         }
      }

      return cacheDirPath(var0);
   }

   private static synchronized void lvlLoadOnce(Context var0) {
      if (!lvlLoaded) {
         if (lvlDir == null) {
            lvlDir = lvlDirPath(var0 != null ? var0 : appCtx);
         }

         lvlLoaded = lvlLoad(var0 != null ? var0 : appCtx);
      }
   }

   private static synchronized boolean lvlLoad(Context var0) {
      String var1 = lvlDir;
      if (var1 == null) {
         log("[lvl] load skip: dir=null");
         return false;
      } else {
         try {
            File var2 = new File(var1, "rh_task_lvl.json");
            boolean var3 = false;
            if (!var2.exists()) {
               File var4 = new File(cacheDirPath(var0), "rh_task_lvl.json");
               if (var4.exists()) {
                  var2 = var4;
                  var3 = true;
               }
            }

            if (!var2.exists()) {
               log("[lvl] load skip: no file " + var2.getAbsolutePath());
               return true;
            } else {
               String var10 = new String(Files.readAllBytes(var2.toPath()), "UTF-8");
               JSONObject var5 = new JSONObject(var10);
               lvlDay = var5.optString("day", "");
               JSONObject var6 = var5.optJSONObject("lvl");
               lvlMap.clear();
               if (var6 != null) {
                  Iterator var7 = var6.keys();

                  while (var7.hasNext()) {
                     String var8 = (String)var7.next();
                     lvlMap.put(var8, var6.optInt(var8, -1));
                  }
               }

               log("[lvl] loaded day=" + lvlDay + " n=" + lvlMap.size() + (var3 ? " (migrated from cache)" : ""));
               if (var3) {
                  lvlSave();
               }

               return true;
            }
         } catch (Throwable var9) {
            log("[lvl] load fail: " + var9);
            return false;
         }
      }
   }

   private static synchronized void lvlSave() {
      String var0 = lvlDir;
      if (var0 == null) {
         try {
            lvlDir = var0 = lvlDirPath(appCtx);
         } catch (Throwable var6) {
         }
      }

      if (var0 == null) {
         log("[lvl] save skip: dir=null");
      } else {
         try {
            JSONObject var1 = new JSONObject();

            for (Entry var3 : lvlMap.entrySet()) {
               var1.put((String)var3.getKey(), var3.getValue());
            }

            JSONObject var8 = new JSONObject();
            var8.put("day", lvlDay);
            var8.put("lvl", var1);
            byte[] var9 = var8.toString().getBytes("UTF-8");
            File var4 = new File(var0, "rh_task_lvl.json");
            File var5 = new File(var0, "rh_task_lvl.json.tmp");
            Files.write(var5.toPath(), var9);
            if (!var5.renameTo(var4)) {
               Files.write(var4.toPath(), var9);
               var5.delete();
            }
         } catch (Throwable var7) {
            log("[lvl] save fail: " + var7);
         }
      }
   }

   private static synchronized void lvlEnsure(String var0) {
      if (var0 != null && var0.length() != 0) {
         if (!var0.equals(lvlDay)) {
            int var1 = lvlMap.size();
            lvlMap.clear();
            lvlDay = var0;
            lvlSave();
            if (var1 > 0) {
               log("[lvl] day -> " + var0 + ", cleared " + var1);
            }
         }
      }
   }

   private static int lvlGet(String var0) {
      Integer var1 = lvlMap.get(var0);
      return var1 == null ? -1 : var1;
   }

   private static boolean lvlSkip(String var0) {
      return lvlGet(var0) >= 2;
   }

   private boolean lvlCountedThisRun(String var1) {
      return this.lvlBumpedThisRun.contains(var1);
   }

   private void lvlBump(String var1) {
      if (var1 != null && var1.length() != 0) {
         if (this.lvlBumpedThisRun.add(var1)) {
            int var2 = lvlGet(var1);
            if (var2 < 2) {
               lvlMap.put(var1, var2 + 1);
               lvlSave();
            }
         }
      }
   }

   private void lvlReset(String var1) {
      if (var1 != null && var1.length() != 0) {
         if (lvlGet(var1) != 0) {
            lvlMap.put(var1, 0);
            lvlSave();
         }
      }
   }

   private static String cacheDirPath(Context var0) {
      File var1 = FishMtopHook.cacheDir();
      return var1 != null ? var1.getAbsolutePath() : ConfigManager.fishExternal("cache");
   }

   private static int probeRunnableCount(Context var0) {
      try {
         lvlLoadOnce(var0);
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
               if (var9 != null && !lvlSkip(var9)) {
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

            return var6;
         }
      } catch (Throwable var12) {
         log("[probe] error: " + var12);
         return -1;
      }
   }

   public static void launch(Context var0, String var1) {
      if (!RUNNING.compareAndSet(false, true)) {
         log("[tasks] engine already running, trigger ignored");
      } else {
         appCtx = var0.getApplicationContext() != null ? var0.getApplicationContext() : var0;

         try {
            ConfigManager var2 = ConfigManager.load();
            toastOn = var2 == null || var2.toastNotify;
            polishOn = var2 == null || var2.polish;
         } catch (Throwable var4) {
         }

         ClassLoader var5 = var0.getClassLoader();
         String var3 = lvlDirPath(var0);   // 持久目录
         lvlDir = var3;
         lvlLoaded = false;
         lvlLoadOnce(var0);
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
               File var8 = FishMtopHook.cacheDir();
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
                        FishTaskAuto.launch(var0, "{}");
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
      if (var10) {
         var9 = false;
      }

      try {
         if (var11) {
            lvlLoadOnce(appCtx);
            lvlEnsure(dayOf(System.currentTimeMillis()));
         }

         if (var5 && (!var11 || !lvlSkip("@signin"))) {
            JSONObject var12 = this.callApi("mtop.taobao.idle.coin.signin", "1.0", "{}", "签到");
            String var13 = firstRet(var12);
            boolean var14 = var13 != null && var13.contains("SUCCESS");
            this.report.append("[签到] ").append(var14 ? "SUCCESS " + coinSuffix(signinRewards(var12)) : (var13 == null ? "无响应" : var13)).append('\n');
            if (var14) {
               toast("✓ 每日签到" + coinSuffix(signinRewards(var12)));
               if (var11) {
                  this.lvlBump("@signin");
               }
            } else if (var11 && var13 != null && (var13.contains("SIGNED") || var13.contains("已签"))) {
               this.lvlBump("@signin");
            }
         } else if (var5) {
            this.report.append("[签到] 跳过：标识=2（今日已判定完成 3 次）\n");
         }

         if (var5 && (!var11 || !lvlSkip("@playSignin"))) {
            JSONObject var46 = this.callApi("mtop.taobao.idle.play.sign.in", "1.0", playSignData(), "玩一玩签到");
            String var50 = firstRet(var46);
            boolean var53 = var50 != null && var50.contains("SUCCESS");
            boolean var15 = var46 != null && var46.optJSONObject("data") != null && var46.optJSONObject("data").optBoolean("signInAwarded", false);
            this.report.append("[玩一玩签到] ").append(var53 ? "SUCCESS" + (var15 ? " 今日已签+奖" : " 幂等(今日已签)") : (var50 == null ? "无响应" : var50)).append('\n');
            if (var53) {
               toast("✓ 玩一玩签到");
               if (var11) {
                  this.lvlBump("@playSignin");
               }
            } else if (var11 && var50 != null && var50.contains("SIGNED_IN")) {
               this.lvlBump("@playSignin");
            }
         } else if (var5) {
            this.report.append("[玩一玩签到] 跳过：标识=2（今日已判定完成 3 次）\n");
         }

         if (opt(var4, "mapRoll", true)) {
            this.doMapRoll();
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

            lvlEnsure(dayOf(var54 > 0L ? var54 : System.currentTimeMillis()));
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
            if (var27 != null && !var23.contains(var27) && !rejectedToday.contains(var27)) {
               if (var11 && lvlSkip(var27)) {
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
                        String var36 = extractAdEventPush(var33);
                        if (var36 != null) {
                           var57.add(new String[]{var27, var36, optStr(var28, "subTitle")});
                        } else {
                           this.report.append("[广告] ").append(var32).append(" 无 adEventPush 参数，跳过\n");
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
                           var20 = new String[]{var27, var34 == null ? "496" : var34};
                        }
                     } else {
                        var56.add(new String[]{var27, var32, str(var26, "fromToken"), str(var26, "groupId")});
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
         this.report.append("[lvl] 跳过=").append(var24).append(" 记录=").append(lvlMap.size()).append(" day=").append(lvlDay).append('\n');

         for (Object var65o : var55) {
            String[] var65 = (String[]) var65o;
            if (this.abort) {
               break;
            }

            this.claimOne(var65[0], var65[1], var21, var22);
            this.processed.add(var65[0]);
            sleep(1200L);
         }

         for (Object var66o : var57) {
            String[] var66 = (String[]) var66o;
            if (this.abort) {
               break;
            }

            this.runAdEventTask(var66[0], var66[1], var21, var22);
            sleep(2000L);
         }

         for (Object var67o : var17) {
            String[] var67 = (String[]) var67o;
            if (this.abort || var10) {
               break;
            }

            this.runSearchTask(var67[0], var67[1], var67[2], var67[3], var67[4], var21, var22);
            sleep(2000L);
         }

         for (Object var68o : var18) {
            String[] var68 = (String[]) var68o;
            if (this.abort || var10) {
               break;
            }

            this.runEcMallTask(var68[0], var68[1], var68[2], var21, var22);
            sleep(2000L);
         }

         for (Object var69o : var19) {
            String[] var69 = (String[]) var69o;
            if (this.abort || var10) {
               break;
            }

            this.runTreasureBetTask(var69[0], var69[1], var21, var22);
            sleep(2000L);
         }

         for (Object var70o : var56) {
            String[] var70 = (String[]) var70o;
            if (this.abort || var10) {
               break;
            }

            JSONObject var72 = new JSONObject();

            try {
               var72.put("sceneId", 496);
               var72.put("deliveryId", var70[0]);
               if (var70[2] != null && var70[2].length() > 0) {
                  var72.put("fromToken", var70[2]);
               }

               if (var70[3] != null && var70[3].length() > 0) {
                  try {
                     var72.put("groupId", Long.parseLong(var70[3]));
                  } catch (Throwable var42) {
                     var72.put("groupId", var70[3]);
                  }
               }
            } catch (Throwable var43) {
            }

            JSONObject var74 = this.callApi("mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var72.toString(), "触发任务 " + var70[1]);
            String var76 = firstRet(var74);
            if (var74 != null && var76 != null) {
               if (!var76.contains("SUCCESS")) {
                  this.report.append("[触发] ").append(var70[1]).append(" -> ").append(var76).append("（云控判定需真实行为，协议跳过）\n");
                  markRejected(var70[0]);
                  if (this.lvlOnRun) {
                     this.lvlBump(var70[0]);
                  }
               } else {
                  this.report.append("[触发] ").append(var70[1]).append(" -> ").append(var76).append('\n');
                  if (this.lvlOnRun) {
                     this.lvlReset(var70[0]);
                  }

                  int var78 = -1;
                  int var80 = 0;

                  while (var80 < 10 && !this.abort) {
                     sleep(2000L);
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

            sleep(1200L);
         }

         if (var9 && var20 != null && !this.abort) {
            this.browseForTask(var20[0], var20[1], var21, var22);
         }

         if (!this.abort) {
            for (int var64 = 0; var64 < 2; var64++) {
               sleep(6000L);
               var48 = this.callApi("mtop.taobao.idle.task.queryolivertasklist", "2.0", "{\"sceneId\":496}", "任务列表复核" + (var64 + 1));
               List var71 = this.extractTasks(var48);
               if (var71 != null) {
                  for (Object var75o : var71) {
                     JSONObject var75 = (JSONObject) var75o;
                     String var77 = str(var75, "deliveryId");
                     if (var77 != null && !this.processed.contains(var77) && (!var11 || !lvlSkip(var77))) {
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
            sleep(5000L);
         } else {
            if (var10 == null || !var10.contains("SUCCESS")) {
               this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 -> ").append(var10).append('\n');
               break;
            }

            var6++;
            this.report.append("[广告] 第 ").append(var8 + 1).append(" 次 complete -> ").append(var10).append('\n');
            sleep(5000L);
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
      JSONObject var8 = new JSONObject();

      try {
         var8.put("sceneId", 496);
         var8.put("deliveryId", var1);
         Uri var9 = Uri.parse(var3);

         for (String var13 : new String[]{"implId", "channelSrp", "adPositionCode", "adOrderPositionCode", "refpid", "k_usetanx"}) {
            String var14 = var9.getQueryParameter(var13);
            if (var14 != null) {
               var8.put(var13, var14);
            }
         }

         if (var4 != null && var4.length() > 0) {
            var8.put("fromToken", var4);
         }

         if (var5 != null && var5.length() > 0) {
            try {
               var8.put("groupId", Long.parseLong(var5));
            } catch (Throwable var15) {
               var8.put("groupId", var5);
            }
         }
      } catch (Throwable var16) {
         this.report.append("[搜一搜] ").append(var2).append(" 参数组装失败: ").append(var16).append('\n');
      }

      this.callApi("mtop.taobao.idle.task.uploadolivertasktrigger", "1.0", var8.toString(), "搜一搜触发 " + var2);
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
            this.lvlBump(var1);
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

                  sleep(30000L);
               }

               this.report.append("[ecMall] push -> ").append(var29 == null ? "无响应" : var29).append('\n');
               sleep(3000L);
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

            sleep(8000L);
         }

         this.report.append("[夺宝] ").append(var2).append(" bet(").append(var6).append(") -> ").append(var14 == null ? "无响应" : var14).append('\n');
         if (var14 != null && var14.contains("SUCCESS")) {
            sleep(3000L);
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

   public static void startCoinBalanceLogger(final Context var0) {
      try {
         String var1 = (String)Class.forName("android.app.Application").getMethod("getProcessName").invoke(null);
         if (var1 != null && var1.contains(":")) {
            return;
         }
      } catch (Throwable var2) {
      }

      ScheduledExecutorService var3 = Executors.newSingleThreadScheduledExecutor();
      var3.scheduleAtFixedRate(new Runnable() {
         @Override
         public void run() {
            try {
               FishTaskAuto.coinBalanceSnapshot(var0);
            } catch (Throwable var2) {
            }
         }
      }, 20L, 30L, TimeUnit.SECONDS);
      log("[coinlog] balance logger armed (every 30s, first in 20s)");
   }

   public static void coinBalanceSnapshot(Context var0) {
      try {
         ClassLoader var1 = var0.getClassLoader();
         String var2 = cacheDirPath(var0);
         FishTaskAuto var3 = new FishTaskAuto(var1, var2);
         JSONObject var4 = var3.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "币余额");
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

   public static void recordCoinBalance(Context var0, long var1) {
      if (var1 > 0L) {
         try {
            synchronized (COIN_LOG_LOCK) {
               long var4 = System.currentTimeMillis();
               SimpleDateFormat var6 = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
               String var7 = var6.format(new Date(var4));
               boolean var8 = !var7.equals(coinLogLastDay);
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

   public static long parseCoinNum(String var0) {
      if (var0 == null) {
         return -1L;
      } else {
         try {
            String var1 = var0.trim().replace("\"", "");
            if (var1.length() == 0) {
               return -1L;
            } else {
               int var2 = var1.indexOf(46);
               if (var2 >= 0) {
                  var1 = var1.substring(0, var2);
               }

               var1 = var1.replaceAll("[^0-9]", "");
               return var1.length() == 0 ? -1L : Long.parseLong(var1);
            }
         } catch (Throwable var3) {
            return -1L;
         }
      }
   }

   private void doMapRoll() {
      try {
         JSONObject var1 = this.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图状态");
         JSONObject var2 = var1 == null ? null : var1.optJSONObject("data");
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
            {"BENEFIT_GGK", var1.bagGgkOn, var1.bagGgkNum, "刮刮卡"}
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
                  }
               }

               if (var24 != null && var23 > 0) {
                  int var43 = var21 < 0 ? 0 : Math.max(0, var23 - var21);
                  if (var23 <= var43) {
                     this.report.append("[背包] ").append(var22).append(" 已达目标（剩 ").append(var23).append("）\n");
                  } else {
                     boolean var44 = !"BENEFIT_GGK".equals(var19);
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
                              JSONObject var34 = this.callApi(
                                 "mtop.taobao.idle.play.prop.use",
                                 "1.0",
                                 objJson(
                                    "id",
                                    var25,
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
                                 "背包刮卡"
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
                           sleep(1200L);
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

   private static int parseNum(String var0) {
      if (var0 == null) {
         return 0;
      } else {
         try {
            StringBuilder var1 = new StringBuilder();

            for (char var5 : var0.toCharArray()) {
               if (var5 >= '0' && var5 <= '9') {
                  var1.append(var5);
               }
            }

            return var1.length() == 0 ? 0 : Integer.parseInt(var1.toString());
         } catch (Throwable var6) {
            return 0;
         }
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

   private void browseForTask(String var1, String var2, List<String> var3, List<String> var4) {
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
               JSONObject var14 = this.callApi(
                  "mtop.taobao.idle.task.uploadolivertasktrigger",
                  "1.0",
                  objJson("sceneId", var2, "implId", var13, "deliveryId", var1),
                  "浏览上报 " + (var8 + 1) + "/" + var5.size()
               );
               String var15 = firstRet(var14);
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
                     if (++var9 >= 3) {
                        this.report.append("[浏览] 连续 ").append(var9).append(" 次 ILLEGAL，任务需真实行为，中止\n");
                        if (this.lvlOnRun) {
                           this.lvlBump(var1);
                        }

                        return;
                     }

                     var10 = Math.min(var10 + 4000L, 16000L);
                     this.report.append("[浏览] step ").append(var8).append(" ILLEGAL ").append(var9).append("/3，退避 ").append(var10 / 1000L).append("s\n");
                     sleep(var10);
                     continue;
                  }

                  this.report.append("[浏览] step ").append(var8).append(" -> ").append(var15).append("，换下一个\n");
                  var8++;
               }

               sleep(var10);
            }
         }

         if (var7 >= var6) {
            this.report.append("[浏览] 达到次数（兜底判定），领奖\n");
            this.claimOne(var1, "浏览任务", var3, var4);
         }

         return;
      }
   }

   private void claimOne(String var1, String var2, List<String> var3, List<String> var4) {
      JSONObject var5 = this.callApi(
         "mtop.taobao.idle.task.getolivertaskbenefit",
         "1.0",
         objJson("sceneId", "496", "deliveryId", var1, "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
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
      if (var11) {
         var3.add(var2 + "(" + var1 + ")");
         this.report.append("[领取] ").append(var2).append(" -> ").append(var6).append(" rewards=").append(var7).append('\n');
         toast("✓ " + var2 + coinSuffix(var7));
         if (this.lvlOnRun) {
            this.lvlBump(var1);
         }
      } else {
         var4.add(var2 + "(" + var1 + ")");
         this.report.append("[领取] ").append(var2).append(" -> ").append(var6 == null ? "无响应" : var6).append('\n');
      }
   }

   private JSONObject callApi(String var1, String var2, String var3, String var4) {
      String var5 = this.respFile(var1);
      sleep(300L);
      String var6 = this.fileFp(var5);
      boolean var7 = ApiDirect.send(this.cl, var1, var2, var3);
      if (!var7) {
         log("[tasks] direct send failed: " + var1);
         return null;
      } else {
         long var8 = System.currentTimeMillis() + 30000L;

         while (System.currentTimeMillis() < var8 && !this.abort) {
            sleep(400L);
            String var10 = this.fileFp(var5);
            if (var10 != null && !var10.equals(var6)) {
               String var11 = this.readFile(var5);

               try {
                  JSONObject var12 = new JSONObject(var11);
                  String var13 = firstRet(var12);
                  log("[tasks] " + var4 + " -> " + var13);
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
   }

   private String respFile(String var1) {
      String var2 = var1;
      int var3 = var1.lastIndexOf(46);
      if (var3 >= 0) {
         var2 = var1.substring(var3 + 1);
      }

      return this.cacheDir + "/rh_resp_" + var2 + ".json";
   }

   private String fileFp(String var1) {
      try {
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

   private static String firstRet(JSONObject var0) {
      if (var0 == null) {
         return null;
      } else {
         JSONArray var1 = var0.optJSONArray("ret");
         return var1 != null && var1.length() > 0 ? var1.optString(0) : String.valueOf(var0.opt("ret"));
      }
   }

   private static String objJson(String... var0) {
      try {
         JSONObject var1 = new JSONObject();

         for (byte var2 = 0; var2 + 1 < var0.length; var2 += 2) {
            var1.put(var0[var2], var0[var2 + 1]);
         }

         return var1.toString();
      } catch (Throwable var3) {
         return "{}";
      }
   }

   private static JSONObject parseObj(String var0) {
      try {
         return var0 == null ? new JSONObject() : new JSONObject(var0);
      } catch (Throwable var2) {
         return new JSONObject();
      }
   }

   private static boolean opt(JSONObject var0, String var1, boolean var2) {
      return var0 == null ? var2 : var0.optBoolean(var1, var2);
   }

   private static String str(JSONObject var0, String var1) {
      return var0 == null ? null : var0.optString(var1, null);
   }

   private static String optStr(JSONObject var0, String var1) {
      return var0.optString(var1, "");
   }

   private static int parseInt(String var0, int var1) {
      try {
         return Integer.parseInt(var0.trim());
      } catch (Throwable var3) {
         return var1;
      }
   }

   private void finish(long var1) {
      this.doMapRoll();
      this.report.append("耗时 ").append((System.currentTimeMillis() - var1) / 1000L).append("s\n");
      String var3 = this.report.toString();
      this.writeFile(this.cacheDir + "/rh_tasks_result.txt", var3);
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

   private static int parseCount(String var0) {
      try {
         int var1 = var0.indexOf(58);
         return Integer.parseInt(var0.substring("领取成功".length(), var1).trim());
      } catch (Throwable var2) {
         return 0;
      }
   }

   private static void sleep(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }
}
