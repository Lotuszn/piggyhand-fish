package com.piggyhand.fish.task;

import android.content.Context;

import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhRuntime;

import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * 当日任务台账（LVL 门 + 人民币风控锁）——Phase 2 优化5 深拆簇1（2026-11-15）。
 * 从 FishTaskAuto 原样迁出：静态态全部集中到这里，FishTaskAuto 只留调用点与实例去重集。
 * 台账存储 rh_task_lvl.json 的结构与文案一字未动（compat 红线），跨天由 ensure 自动清零。
 * RMB 锁复用同一份台账文件（v1.5.4-fix 设计，物理耦合故同文件管理）。
 */
final class LvlLedger {

   /** 台账文件名（lvlLoad/lvlSave 共用，消除原 FishTaskAuto 里的硬编码重复） */
   private static final String LVL_FILE = "rh_task_lvl.json";
   /** 当日计数上限：skip 判 >=MAX，bump 只在 <MAX 时 +1（原硬编码 2，语义不变） */
   private static final int LVL_MAX = 2;

   private static volatile String lvlDay = "";
   private static volatile String lvlDir = null;
   private static final Map<String, Integer> lvlMap = new ConcurrentHashMap<>();
   private static volatile boolean lvlLoaded = false;
   /** 共享 LVL 门跨线程锁（RhPondTask 自动调度线与手动 CLEAR_MARKS 并发写保护） */
   private static final Object LVL_SHARED_LOCK = new Object();

   private LvlLedger() {
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }

   /** 当日 yyyyMMdd（Locale.US 固定，避免区域设置改变台账 key 格式） */
   static String dayOf(long var0) {
      return new SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new Date(var0));
   }

   /** 宿主 cache 目录绝对路径（lvlLoad 旧档迁移 fallback 用）。
    *  与 FishTaskAuto.cacheDirPath 同源：RhRuntime.cacheDir() 优先，退 ConfigManager 推断路径。
    *  不直引 ConfigManager 是为避免 core↔config 包环——Phase 3 统一上浮 RhRuntime 时合并。 */
   private static String cachePath(Context var0) {
      File var1 = RhRuntime.cacheDir();
      return var1 != null ? var1.getAbsolutePath() : com.piggyhand.fish.config.ConfigManager.fishExternal("cache");
   }

   /** 台账目录：外部 files 优先（冷启动不清），退内部 files，再退 cache */
   static String dirPath(Context var0) {
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

      return cachePath(var0);
   }

   /** 当前台账目录（FishTaskAuto 构造器 reportDir 兜底用） */
   static String dir() {
      return lvlDir;
   }

   /** 台账条目数（finish 报告行回显用） */
   static int size() {
      return lvlMap.size();
   }

   /** 台账当日（finish 报告行回显用） */
   static String day() {
      return lvlDay;
   }

   /** 引擎启动前重开台账：重定向目录 + 强制下次惰性重载（原 launch 内三行内联逻辑） */
   static synchronized void reload(Context var0) {
      lvlDir = dirPath(var0);
      lvlLoaded = false;
      loadOnce(var0);
   }

   static synchronized void loadOnce(Context var0) {
      if (!lvlLoaded) {
         if (lvlDir == null) {
            lvlDir = dirPath(var0 != null ? var0 : RhRuntime.appCtx);
         }

         lvlLoaded = load(var0 != null ? var0 : RhRuntime.appCtx);
      }
   }

   private static synchronized boolean load(Context var0) {
      String var1 = lvlDir;
      if (var1 == null) {
         log("[lvl] load skip: dir=null");
         return false;
      } else {
         try {
            File var2 = new File(var1, LVL_FILE);
            boolean var3 = false;
            if (!var2.exists()) {
               // 旧版本落错 cache 目录的存档迁移（v1.4.x 兼容路径，保留）
               File var4 = new File(cachePath(var0), LVL_FILE);
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
                     lvlMap.put(var8, var6.optInt(var8, 0));
                  }
               }

               log("[lvl] loaded day=" + lvlDay + " n=" + lvlMap.size() + (var3 ? " (migrated from cache)" : ""));
               if (var3) {
                  save();
               }

               return true;
            }
         } catch (Throwable var9) {
            log("[lvl] load fail: " + var9);
            return false;
         }
      }
   }

   private static synchronized void save() {
      String var0 = lvlDir;
      if (var0 == null) {
         try {
            lvlDir = var0 = dirPath(RhRuntime.appCtx);
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
            File var4 = new File(var0, LVL_FILE);
            File var5 = new File(var0, LVL_FILE + ".tmp");
            // 原子写：tmp 写成功后 rename；rename 失败（跨设备等）退直接覆写
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

   /** 跨天滚动：day 变化即清空内存台账并落盘（原 lvlEnsure） */
   static synchronized void ensure(String var0) {
      if (var0 != null && var0.length() != 0) {
         if (!var0.equals(lvlDay)) {
            int var1 = lvlMap.size();
            lvlMap.clear();
            lvlDay = var0;
            save();
            if (var1 > 0) {
               log("[lvl] day -> " + var0 + ", cleared " + var1);
            }
         }
      }
   }

   static int get(String var0) {
      // v1.4.1 修复：缺省 -1 会让 lvlBump 首次计数写成 0（-1+1），实际放行 3 次而非上限 2 次。
      // 缺省 0 与「未记录」等效：lvlSkip(0>=2=false)、lvlReset(0!=0=false) 行为均不变，bump 首次正确写 1。
      Integer var1 = lvlMap.get(var0);
      return var1 == null ? 0 : var1;
   }

   static boolean skip(String var0) {
      return get(var0) >= LVL_MAX;
   }

   /** 引擎内计数 +1（runDedup = 每轮跑批的去重集，原 FishTaskAuto.lvlBumpedThisRun 实例字段参数化） */
   static void bump(String var1, Set<String> runDedup) {
      if (var1 != null && var1.length() != 0) {
         if (runDedup.add(var1)) {
            int var2 = get(var1);
            if (var2 < LVL_MAX) {
               lvlMap.put(var1, var2 + 1);
               save();
            }
         }
      }
   }

   static void reset(String var1) {
      if (var1 != null && var1.length() != 0) {
         if (get(var1) != 0) {
            lvlMap.put(var1, 0);
            save();
         }
      }
   }

   /** 本轮跑批是否已对该键计过数（原 lvlCountedThisRun，去重集参数化） */
   static boolean countedThisRun(Set<String> runDedup, String var1) {
      return runDedup.contains(var1);
   }

   /* ---------- 496 场景人民币风控锁（v1.5.4-fix，2026-11-12 日志取证） ----------
    * 证据：单次会话 25 分钟内对 6 个任务打 44 次 getolivertaskbenefit，
    *       73 条配对回包 100% 是 FAIL_BIZ_IDL_COIN_TASK_RMB_CHECK_ERROR::服务器繁忙请稍后再试~。
    * 该请求的【线上形态】已经和 App 手动领完全一致——模块传字符串 "106538"，
    *       经闲鱼协议栈 paramMap 序列化后落地为 int 106538，asac 也由协议栈自动补齐
    *       （日志 [mtop-req] 里两条路径的参数逐字相同）。所以这不是参数问题，
    *       是服务端「真实广告曝光 / 真实浏览行为」凭证闸：没有凭证，重试多少次都是同一码。
    * 危害：高频重打会把账号钉进当日风控窗口，导致 App 内手动领也一起失败（用户实测现象）。
    * 处理：当日一次判定即上锁，主循环与收尾一律不再打该 deliveryId，把名额留给手动。
    * 存储：复用 rh_task_lvl.json（跨进程持久、跨天由 ensure 自动清空）。 */
   private static final String RMB_LOCK_PREFIX = "rmb:";

   static boolean rmbLocked(String var0) {
      return var0 != null && var0.length() != 0 && lvlMap.containsKey(RMB_LOCK_PREFIX + var0);
   }

   static void rmbLock(String var0) {
      if (var0 == null || var0.length() == 0) return;
      String var1 = RMB_LOCK_PREFIX + var0;
      if (!lvlMap.containsKey(var1)) {
         lvlMap.put(var1, 1);
         save();
         log("[rmb-lock] " + var0 + " 上锁（人民币风控，需真实行为）");
      }
   }

   /** 任务当日额度是否已用尽。reachLimit 是服务端下发的权威位；
    *  times>=maxTimes 兜底服务端不下发 reachLimit 的变体（7.25.31 两处都下发了，仍保留兜底）。
    *  纯函数，只读 task JSON，不产生任何请求。 */
   static boolean quotaFull(JSONObject var0) {
      if (var0 == null) return false;
      JSONObject var1 = var0.optJSONObject("progressDTO");
      if (var1 == null) return false;
      return var1.optBoolean("reachLimit", false)
            || (var1.optInt("maxTimes", 0) > 0 && var1.optInt("times", 0) >= var1.optInt("maxTimes", 0));
   }

   /* ---------- 共享 LVL 门（v1.4.1）：RhPondTask 的自动赚饲料/自动投喂复用同一份 rh_task_lvl.json ----------
    * 语义与实例版一致：当日计数 >=2 跳过（skip），每次引擎运行计一次。
    * 鱼塘两引擎的 bump 只在自动调度线程里调用（每调度一轮 bump 一次），手动 socket 触发不经过这里
    * —— 与 POND/OLIVER 手动语义对齐。跨天由 ensure 自动清零。 */

   /** 共享 LVL 查询：当日该键已计满（>=2）→ 自动跑跳过 */
   public static boolean skipShared(Context ctx, String key) {
      try {
         loadOnce(ctx != null ? ctx : RhRuntime.appCtx);
         ensure(dayOf(System.currentTimeMillis()));
         return skip(key);
      } catch (Throwable t) {
         return false; // LVL 存档坏了不拦业务
      }
   }

   /** 共享 LVL 计数 +1：每次引擎运行调用一次，当日累计到 2 后 skipShared 开始返回 true */
   public static void bumpShared(Context ctx, String key) {
      try {
         if (key == null || key.length() == 0) return;
         synchronized (LVL_SHARED_LOCK) {
            loadOnce(ctx != null ? ctx : RhRuntime.appCtx);
            ensure(dayOf(System.currentTimeMillis()));
            int v = get(key);
            if (v < LVL_MAX) {
               lvlMap.put(key, v + 1);
               save();
               log("[lvl] shared bump " + key + " -> " + (v + 1));
            }
         }
      } catch (Throwable t) {
         log("[lvl] shared bump fail: " + t);
      }
   }

   /** v1.5.3 各引擎当日标记文件名：done=完成标记 / try=尝试计数 / date=当日已跑标记 */
   private static final String[] DAILY_MARK_FILES = {
         "rh_task_lvl.json",        // LVL 门（0/1/2 计数，含 @signin/@playSignin/@dailyReward/@pondTask/@pondFeed 等）
         "rh_game_done.txt", "rh_game_try.txt",       // 游戏任务
         "rh_oliver_done.txt", "rh_oliver_try.txt",   // 会员任务
         "rh_pond_date.txt",                            // 鱼塘赚饲料
         "rh_pondfeed_date.txt",                        // 自动投喂（重跑会真实消耗饲料）
         "rh_cash_done.txt", "rh_cash_claim.txt",       // 现金瓜分
         "rh_coin_try.txt", "rh_coin_mark.txt",         // 币任务尝试计数 + 当日标记
         "rh_coin_exch.txt",                            // 兑换每日 1 次标记（清除后会再尝试兑换，服务端限额兜底）
         "rh_cycle_claim.txt",                          // 圈数挑战本地领取档
   };

   /**
    * v1.5.3 清除全部当日任务标记（socket CLEAR_MARKS 调用，必须由闲鱼进程自己执行：
    * lvlMap 是本进程 static 内存缓存，App 进程删文件会被 save 回写——见 RhGameTask.doneFile 注释同因）。
    * 引擎运行中（engineRunning）拒绝执行，避免边跑边清 bump 把 0 写回。
    * @param engineRunning FishTaskAuto.RUNNING（生命周期属引擎，参数化传入）
    * @param ctx           调用方解析好的宿主上下文
    * @return 逐项结果清单（回显给 UI）
    */
   public static String clearDailyMarks(boolean engineRunning, Context ctx) {
      StringBuilder out = new StringBuilder();
      if (engineRunning) {
         return "拒绝：引擎正在运行中（RUNNING=true），等本轮跑完再清，否则计数会被写回";
      }
      if (ctx == null) {
         return "失败：上下文未就绪（闲鱼进程未完成初始化）";
      }
      // ① 内存 LVL 缓存清零 + 落盘空表（save 原子写）
      synchronized (LVL_SHARED_LOCK) {
         int n = lvlMap.size();
         lvlMap.clear();
         lvlDay = "";
         save();
         out.append("LVL 内存计数: 已清 ").append(n).append(" 项并落盘\n");
      }
      // ② 标记文件：在 4 个候选目录里找到即删（各引擎目录选择不一，逐个尝试不漏）
      File[] dirs;
      try {
         File ef = ctx.getExternalFilesDir(null);
         File ec = ctx.getExternalCacheDir();
         dirs = new File[]{ctx.getFilesDir(), ctx.getCacheDir(), ef, ec};
      } catch (Throwable t) {
         dirs = new File[]{new File("/data/data/com.taobao.idlefish/files")};
      }
      int deleted = 0, absent = 0, failed = 0;
      for (String name : DAILY_MARK_FILES) {
         boolean gone = false, found = false;
         if (dirs != null) {
            for (File d : dirs) {
               if (d == null) continue;
               File f = new File(d, name);
               if (!f.exists()) continue;
               found = true;
               // .tmp 伴生文件一并清（原子写残留）
               File tmp = new File(d, name + ".tmp");
               if (tmp.exists()) tmp.delete();
               gone = f.delete() || !f.exists();
               if (!gone) failed++;
               break;
            }
         }
         if (!found) absent++;
         else if (gone) deleted++;
      }
      out.append("标记文件: 删除 ").append(deleted)
            .append(" · 本就不存在 ").append(absent)
            .append(failed > 0 ? " · 删除失败 " + failed : "")
            .append('\n');
      out.append("rejectedToday/内存探针态: 随进程重启复位\n");
      out.append("DONE @").append(new SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new Date()));
      log("[marks] cleared: del=" + deleted + " absent=" + absent + " fail=" + failed);
      return out.toString();
   }
}
