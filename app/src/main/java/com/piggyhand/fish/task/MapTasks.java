package com.piggyhand.fish.task;

import android.content.Context;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhLocationContext;

import de.robv.android.xposed.XposedBridge;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.piggyhand.fish.task.JsonUtil.*;

/**
 * 地图/圈数挑战/背包/擦亮域——Phase 2 优化5 深拆簇4（2026-11-15）。
 * 从 FishTaskAuto 原样迁出：前进掷骰、圈数日奖、7 天圈数挑战、背包道具、宝贝擦亮
 * ——全部是「finish 阶段动作 + 探测」，与 run() 主状态机相对独立。
 * owner 模式：持有 FishTaskAuto 引用访问响应通道（callApi）/报告/文件工具/台账去重集；
 * 请求体、API 字面量、报告文案、日志格式逐字未动（compat 红线）。
 * 流量类 runners（search/ecmall/adEvent/browse）仍在 FishTaskAuto——它们是 run()
 * 状态机的有机部分，Phase 3 接口化时再动。
 */
final class MapTasks {

   private final FishTaskAuto owner;

   MapTasks(FishTaskAuto owner) {
      this.owner = owner;
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }

   /* ---------------- API 常量与共享缓存（原 FishTaskAuto 静态态） ---------------- */

   static final String MAP_INIT_API = "mtop.taobao.idle.treasure.hunt.map.init";
   private static final String MAP_ROLL_API = "mtop.taobao.idle.treasure.hunt.map.roll";
   // 圈数挑战每日步数奖励领取（11-10 抓包：宿主走到目标后弹领取按钮时发的就是这个）
   private static final String MAP_DAILY_REWARD_API = "mtop.taobao.idle.treasure.hunt.map.daily.reward";
   // 7天圈数挑战（11-10 实抓）：info 查档位状态，tierList[].status==AWARD 即可领；reward 按 cycle+recordId 领取
   private static final String CYCLE_INFO_API = "mtop.idle.interact.cycle.challenge.info";
   private static final String CYCLE_REWARD_API = "mtop.idle.interact.cycle.challenge.reward";
   // 探针发现「无任务可跑但圈数挑战有 AWARD 档」时置位，launch 切 claim_only 迷你跑批（跳浏览）
   static volatile boolean cycleClaimOnly = false;
   private static final String PROP_LIST_API = "mtop.taobao.idle.play.prop.list";
   private static final String PROP_USE_API = "mtop.taobao.idle.play.prop.use";
   private static final String POLISH_API = "mtop.taobao.idle.item.polish";
   /** v1.5.5 改进 D：map.init 60s 结果缓存（静态——coinBalanceLogger 每 30s 新建实例）。
    *  只缓存 SUCCESS 响应；GATEWAY_REJECT/失败不缓存，失败立刻可重试。 */
   static volatile JSONObject MAP_INIT_CACHE = null;
   static final long[] MAP_INIT_TS = new long[]{0L};

   /**
    * 地图账本已变（map.roll 成功 / 骰子核销）→ 立即作废 60s init 缓存。
    * 缓存只对「只读探针」语义安全；写操作之后继续读缓存 = 拿旧的 location/remainStep 去拼请求，
    * 这是 11-19 日志里 6 次 LOCATION_NOT_MATCH 空转、以及 remain=0 后仍继续掷骰的来源。
    */
   static void invalidateMapCache() {
      MAP_INIT_CACHE = null;
      MAP_INIT_TS[0] = 0L;
   }

   /* ---------------- 圈数探测（probeCycleAward，static——autoRunDaily 探针调用） ---------------- */

   /** 圈数挑战 tierList 里 status==AWARD 的档位数；info 不可用/通道撞车返回 0 */
   static int probeCycleAward(FishTaskAuto var0) {
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
         java.util.Set<String> var7 = var0.mapTasks.readCycleClaimed();
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

   /* ---------------- 前进掷骰（doMapRoll） ---------------- */

   void doMapRoll() {
      try {
         JSONObject var1 = owner.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图状态");
         JSONObject var2 = var1 == null ? null : var1.optJSONObject("data");
         // 圈数挑战日奖：init 拿到 data 就顺手查一次可领状态（todayHasIssue=false 且服务端判定到点才发）
         maybeClaimDailyReward(var2);
         JSONObject var3 = var2 == null ? null : var2.optJSONObject("userInfo");
         if (var3 == null) {
            owner.report.append("[前进钓鱼] 地图状态获取失败，跳过\n");
            return;
         }

         long var4 = (long) parseNum(var3.optString("location", "0"));
         int var6 = parseNum(var3.optString("remainStep", "0"));
         String var7 = "11";
         JSONArray var8 = var2.optJSONArray("mapResource");
         if (var8 != null && var8.length() > 0) {
            var7 = var8.optJSONObject(0).optString("mapVersion", "11");
         }

         if (var6 <= 0) {
            owner.report.append("[前进钓鱼] 无剩余步数，跳过\n");
            log("[map-roll] skip: remainStep=0 location=" + var4);
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
            JSONObject var15 = owner.callApi("mtop.taobao.idle.treasure.hunt.map.roll", "1.0", var14, "前进掷骰");
            String var16 = firstRet(var15);
            if (var16 == null || !var16.contains("SUCCESS")) {
               // LOCATION_NOT_MATCH 专项兜底（与背包掷骰同款）：账本错位时重 init 拿新状态重试，
               // 拒绝发生在消耗前（不扣步数），复用 var12 既有封顶（≥5 break），失败路径才触发
               if (var16 != null && var16.contains("LOCATION_NOT_MATCH") && var12 < 5) {
                  JSONObject varRj = owner.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0",
                     "{" + RhLocationContext.jsonPair() + "\"version\":4}", "前进地图重试", true);
                  JSONObject varRk = varRj == null ? null : varRj.optJSONObject("data");
                  JSONObject varRl = varRk == null ? null : varRk.optJSONObject("userInfo");
                  if (varRl != null) {
                     var4 = (long) parseNum(varRl.optString("location", String.valueOf(var4)));
                     JSONArray varRm = varRk.optJSONArray("mapResource");
                     if (varRm != null && varRm.length() > 0) {
                        var7 = varRm.optJSONObject(0).optString("mapVersion", var7);
                     }
                     var12++;
                     FishTaskAuto.sleep(600L);
                     continue; // 重试同一次掷骰（var9 不计成功次数）
                  }
               }
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
               : (long) parseNum(var17.optJSONObject("userInfo").optString("location", String.valueOf(var4)));
            var6 = stepOf(var17);
            boolean var22 = "true".equalsIgnoreCase(var17.optString("isNeedRefresh", "false"));
            if (var30 == var4 && var6 > 0) {
               if (var12 >= 5) {
                  var13 = "位置未推进";
                  break;
               }

               var12++;
               JSONObject var31 = owner.callApi("mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图重试", true);
               JSONObject var32 = var31 == null ? null : var31.optJSONObject("data");
               JSONObject var33 = var32 == null ? null : var32.optJSONObject("userInfo");
               if (var33 == null) {
                  var13 = "重试初始化失败";
                  break;
               }

               var4 = (long) parseNum(var33.optString("location", String.valueOf(var4)));
               var6 = stepOf(var31);
            } else {
               var4 = var30;
               if (var22) {
                  if (var12 >= 5) {
                     var13 = "刷新超限";
                     break;
                  }

                  var12++;
                  JSONObject var23 = owner.callApi(
                     "mtop.taobao.idle.treasure.hunt.map.init", "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "地图刷新", true
                  );
                  JSONObject var24 = var23 == null ? null : var23.optJSONObject("data");
                  JSONObject var25 = var24 == null ? null : var24.optJSONObject("userInfo");
                  if (var25 == null) {
                     var13 = "刷新地图失败";
                     break;
                  }

                  var4 = (long) parseNum(var25.optString("location", String.valueOf(var30)));
                  var6 = stepOf(var23);
                  JSONArray var26 = var24.optJSONArray("mapResource");
                  if (var26 != null && var26.length() > 0) {
                     var7 = var26.optJSONObject(0).optString("mapVersion", var7);
                  }
               }
            }
         }

         if (var9 == 0) {
            owner.report.append("[前进钓鱼] ").append(var13 == null ? "掷骰失败" : var13).append('\n');
         } else {
            owner.report
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
            FishTaskAuto.toast("✓ 前进钓鱼 +" + var11 + "币");
            log("[map-roll] rolls=" + var9 + " steps=" + var10 + " coins=" + var11 + " remain=" + var6);
         }
         // 本轮掷过骰子 → 服务端 location/remainStep 已变，作废 init 缓存；
         // 下一轮跑批或收尾再进来时拿的是真账本，不再用旧位置拼 locationBefore
         if (var9 > 0) invalidateMapCache();
      } catch (Throwable var27) {
         owner.report.append("[前进钓鱼] 异常 ").append(var27).append('\n');
      }
   }

   /* ---------------- 圈数挑战日奖 / 7 天圈数挑战 ---------------- */

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
         if (owner.lvlOnRun && LvlLedger.skip(FishTaskAuto.K_DAILY_REWARD)) {
            return;
         }
         JSONObject var1 = var0 == null ? null : var0.optJSONObject("dailyReward");
         if (var1 == null) {
            return; // 老版本页面无此字段，直接跳过
         }
         if ("true".equalsIgnoreCase(var1.optString("todayHasIssue", "false"))) {
            // 今日已发放 = 服务端判定完成，记一次 LVL（与 signin 的 SIGNED 分支同款语义）
            if (owner.lvlOnRun) {
               LvlLedger.bump(FishTaskAuto.K_DAILY_REWARD, owner.lvlBumpedThisRun);
            }
            return;
         }
         String var2 = RhLocationContext.jsonPairObj();
         JSONObject var3 = owner.callApi(MAP_DAILY_REWARD_API, "1.0", var2, "圈数日奖");
         String var4 = firstRet(var3);
         boolean var5 = var4 != null && var4.contains("SUCCESS");
         int var6 = parseNum(var1.optString("todayNum", "0"));
         if (var5) {
            owner.report.append("[圈数日奖] SUCCESS +").append(var6).append("币\n");
            FishTaskAuto.toast("✓ 圈数挑战日奖 +" + var6 + "币");
            log("[daily-reward] claimed +" + var6);
            // 领取成功记一次 LVL；FAIL（未到点/步数不足）不记，后续跑批继续重试
            if (owner.lvlOnRun) {
               LvlLedger.bump(FishTaskAuto.K_DAILY_REWARD, owner.lvlBumpedThisRun);
            }
         } else {
            // 未到点/无可领，只落日志不进报告，避免噪音
            log("[daily-reward] not claimable: " + (var4 == null ? "无响应" : var4));
         }
      } catch (Throwable var7) {
         owner.report.append("[圈数日奖] 异常 ").append(var7).append('\n');
      }
   }

   /**
    * 7天圈数挑战奖励：cycle.challenge.info 返回 tierList，status==AWARD 的档位逐个领。
    * 请求体 {cycle, recordId, fishAdCode, gps}；响应 {rewardTitle, success}。
    * 调用时机：doMapRoll 之后（掷骰推进圈数后查才有新 AWARD）+ finish 收尾。
    * 注意：info 尾段与 treasure.layout.info 同落 rh_resp_info.json，用响应自带 api 字段校验防误读。
    */
   void maybeClaimCycleReward() {
      try {
         // UI 开关（常用功能「7天圈数挑战」）：关了就不查档不领取
         ConfigManager var15 = ConfigManager.load();
         if (var15 != null && !var15.cycleChallenge) {
            log("[cycle-challenge] 开关关闭，跳过");
            return;
         }
         JSONObject var0 = owner.callApi(CYCLE_INFO_API, "1.0", RhLocationContext.jsonPairObj(), "圈数挑战");
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
               JSONObject var10 = owner.callApi(CYCLE_REWARD_API, "1.0", var9, "圈数奖励");
               String var11 = firstRet(var10);
               boolean var12 = var10 != null && var11 != null && var11.contains("SUCCESS");
               if (var12) {
                  markCycleClaimed(var7);
                  owner.report.append("[圈数挑战] 领取 第").append(var7).append("圈 ")
                     .append(var8).append("（当前").append(var3).append("圈）\n");
                  FishTaskAuto.toast("✓ 圈数挑战 " + var8);
                  log("[cycle-challenge] claimed cycle=" + var7 + " " + var8);
               } else {
                  log("[cycle-challenge] claim fail cycle=" + var7 + ": " + (var11 == null ? "无响应" : var11));
               }
            }
         }
      } catch (Throwable var13) {
         owner.report.append("[圈数挑战] 异常 ").append(var13).append('\n');
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
         String var1 = owner.readFile(owner.cacheDir + "/rh_cycle_claim.txt");
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
         String var1 = owner.readFile(owner.cacheDir + "/rh_cycle_claim.txt");
         String var2 = (var1 == null ? "" : var1.trim() + "\n") + cycleDay() + "|" + var0 + "\n";
         owner.writeFile(owner.cacheDir + "/rh_cycle_claim.txt", var2);
      } catch (Throwable ignored) {
      }
   }

   /* ---------------- 背包道具（doBagUse） ---------------- */

   void doBagUse() {
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
            if ((Boolean) var7[1]) {
               var3 = true;
               break;
            }
         }

         if (!var3) {
            return;
         }

         JSONObject var39 = owner.callApi(
            PROP_LIST_API,
            "1.0",
            objJson("bizCode", "coinMap", "fishAdCode", RhLocationContext.fishAdCode(), "gps", RhLocationContext.gps()),
            "背包列表"
         );
         JSONObject var40 = var39 == null ? null : var39.optJSONObject("data");
         JSONArray var41 = var40 == null ? null : var40.optJSONArray("propInfos");
         if (var41 == null) {
            owner.report.append("[背包] 列表获取失败，跳过\n");
            return;
         }

         long var42 = 0L;
         String var9 = "11";
         boolean var10 = false;
         JSONObject var11 = owner.callApi(MAP_INIT_API, "1.0", "{" + RhLocationContext.jsonPair() + "\"version\":4}", "背包地图");
         JSONObject var12 = var11 == null ? null : var11.optJSONObject("data");
         JSONObject var13 = var12 == null ? null : var12.optJSONObject("userInfo");
         if (var13 != null) {
            var42 = (long) parseNum(var13.optString("location", "0"));
            var10 = true;
         }

         JSONArray var14 = var12 == null ? null : var12.optJSONArray("mapResource");
         if (var14 != null && var14.length() > 0) {
            var9 = var14.optJSONObject(0).optString("mapVersion", var9);
         }

         for (Object[] var18 : var2) {
            String var19 = (String) var18[0];
            boolean var20 = (Boolean) var18[1];
            int var21 = (Integer) var18[2];
            String var22 = (String) var18[3];
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
                     owner.report.append("[背包] ").append(var22).append(" 已达目标（剩 ").append(var23).append("）\n");
                  } else {
                     // GGK 与 DZP 走 prop.use 直兑；其余（骰子）走地图掷骰，需地图状态可用
                     boolean var44 = !"BENEFIT_GGK".equals(var19) && !"BENEFIT_DZP_SPRING".equals(var19);
                     if (var44 && !var10) {
                        owner.report.append("[背包] ").append(var22).append(" 地图状态不可用，跳过\n");
                     } else {
                        int var28 = var23 - var43;
                        int var29 = 0;
                        int var30 = 0;
                        int var31 = 0;
                        String var32 = null;
                        int var33 = 0;
                        int varRef = 0; // LOCATION_NOT_MATCH 重 init 计数（独立于 var33 循环上限，封顶 3 次）

                        while (var29 < var28 && var33++ < 120) {
                           if (var44) {
                              JSONObject var45 = owner.callApi(
                                 MAP_ROLL_API,
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
                                 // LOCATION_NOT_MATCH 专项兜底：与 doMapRoll「位置未推进→重新 init」同款。
                                 // 账本错位（locationBefore/mapVersion 与服务端不一致）时重新 init 拿新状态，
                                 // 重试同一次掷骰（var29 不消耗）。LOCATION_NOT_MATCH 是消耗前预校验拒绝，
                                 // 不扣骰子（2026-11-16/11-18 两份日志实证：反复全灭背包数量不变），无重试双扣风险。
                                 if (var47 != null && var47.contains("LOCATION_NOT_MATCH") && varRef < 3) {
                                    JSONObject varRi = owner.callApi(
                                       MAP_INIT_API, "1.0",
                                       "{" + RhLocationContext.jsonPair() + "\"version\":4}", "背包地图重试", true
                                    );
                                    JSONObject varRd = varRi == null ? null : varRi.optJSONObject("data");
                                    JSONObject varRu = varRd == null ? null : varRd.optJSONObject("userInfo");
                                    if (varRu != null) {
                                       var42 = (long) parseNum(varRu.optString("location", String.valueOf(var42)));
                                       JSONArray varRr = varRd.optJSONArray("mapResource");
                                       if (varRr != null && varRr.length() > 0) {
                                          var9 = varRr.optJSONObject(0).optString("mapVersion", var9);
                                       }
                                       var10 = true;
                                       varRef++;
                                       FishTaskAuto.sleep(600L);
                                       continue; // 同一次掷骰带新账本状态重打
                                    }
                                 }
                                 var32 = var47 == null ? "无响应" : var47;
                                 break;
                              }

                              JSONObject var36 = var45.optJSONObject("data");
                              int var37 = var36 == null ? 0 : parseNum(var36.optString("diceResult", "0"));
                              var30 += var37;
                              var42 += (long) var37;
                              var31 += rollCoins(var36);
                           } else {
                              // DZP：第 var29 次使用取第 var29 张的 id（快照序）；GGK 沿用首个 id
                              String varUseId = var25;
                              if ("BENEFIT_DZP_SPRING".equals(var19) && varIds.size() > 0) {
                                 varUseId = (String) varIds.get(Math.min(var29, varIds.size() - 1));
                              }

                              JSONObject var34 = owner.callApi(
                                 PROP_USE_API,
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
                              FishTaskAuto.sleep(400L);
                           }

                           var29++;
                           FishTaskAuto.sleepJitter(1200L);
                        }

                        // 掷过骰子 → 地图账本已变，作废 init 缓存（刮卡/转盘只动道具，不动位置，无需失效）
                        if (var44 && var29 > 0) invalidateMapCache();

                        StringBuilder var46 = new StringBuilder("[背包] ").append(var22).append(" 使用 ").append(var29).append("/").append(var28);
                        if (var44) {
                           var46.append(" · 前进 ").append(var30).append(" 步");
                        }

                        var46.append(" · 收益 ").append(var31);
                        if (var32 != null) {
                           var46.append(" · 中断:").append(var32);
                        }

                        owner.report.append(var46).append('\n');
                        if (var29 > 0) {
                           FishTaskAuto.toast("✓ 背包 " + var22 + " 用 " + var29 + " 个");
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
                  owner.report.append("[背包] ").append(var22).append(" 背包无货，跳过\n");
               }
            }
         }
      } catch (Throwable var38) {
         owner.report.append("[背包] 异常 ").append(var38).append('\n');
      }
   }

   /* ---------------- 纯函数：rollCoins / ggkReward / stepOf ---------------- */

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

   /* ---------------- 宝贝擦亮（runPolish / markPolished） ---------------- */

   void runPolish() {
      String var1 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
      String var2 = owner.cacheDir + "/rh_pref.json";
      JSONObject var3 = parseObj(owner.readFile(var2).trim().isEmpty() ? "{}" : owner.readFile(var2));
      if (var1.equals(var3.optString("polish_date"))) {
         owner.report.append("[擦亮] 今日已擦亮（每日一次标记），跳过\n");
      } else {
         JSONObject var4 = owner.callApi(POLISH_API, "2.0", "{\"type\":\"originaljson\"}", "擦亮");
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
            owner.report.append(var6 > 0 ? "[擦亮] SUCCESS：已擦亮 " + var6 + " 个宝贝\n" : "[擦亮] SUCCESS：服务端受理（今日已擦过或无可擦宝贝）\n");
            if (var6 > 0) {
               FishTaskAuto.toast("✓ 擦亮 " + var6 + " 个宝贝");
            }

            markPolished(var2, var3, var1);
         } else if (var5 != null && var5.contains("POLISH_AGAIN")) {
            owner.report.append("[擦亮] 今日已擦亮过（服务端判重）\n");
            markPolished(var2, var3, var1);
         } else if (var5 != null && var5.contains("POLISH_FAILED3")) {
            owner.report.append("[擦亮] 今日擦亮次数已用完（服务端限制）\n");
            markPolished(var2, var3, var1);
         } else {
            owner.report.append("[擦亮] ").append(var5 == null ? "无响应，下次重试" : var5).append('\n');
         }
      }
   }

   private void markPolished(String var1, JSONObject var2, String var3) {
      try {
         var2.put("polish_date", var3);
         owner.writeFile(var1, var2.toString());
      } catch (Throwable var5) {
         log("markPolished fail: " + var5);
      }
   }
}
