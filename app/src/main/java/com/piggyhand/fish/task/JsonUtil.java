package com.piggyhand.fish.task;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON/数字解析纯函数集——Phase 2 优化5 深拆簇2（2026-11-15）。
 * 从 FishTaskAuto 原样迁出，零状态、零 I/O、零请求；实现逐字未动（行为红线）。
 * 调用方约定：FishTaskAuto 经 static import 保持原裸调用形态；
 * 其他 task 类显式 JsonUtil.xxx(...)。方法可见性 package-private（同包内聚），
 * parseCoinNum 保持 public（TaskRegistry.onCoinLevel 跨类 API）。
 */
final class JsonUtil {

   private JsonUtil() {
   }

   /** mtop ret 数组首元素（SUCCESS/FAIL_XXX 判定入口） */
   static String firstRet(JSONObject var0) {
      if (var0 == null) {
         return null;
      } else {
         JSONArray var1 = var0.optJSONArray("ret");
         return var1 != null && var1.length() > 0 ? var1.optString(0) : String.valueOf(var0.opt("ret"));
      }
   }

   /** 键值对 varargs 拼 JSON 请求体；长度截齐（奇数尾键丢弃），异常返 "{}" */
   static String objJson(String... var0) {
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

   /** 宽松 parse：null/坏 JSON 一律返空对象（不抛出） */
   static JSONObject parseObj(String var0) {
      try {
         return var0 == null ? new JSONObject() : new JSONObject(var0);
      } catch (Throwable var2) {
         return new JSONObject();
      }
   }

   static boolean opt(JSONObject var0, String var1, boolean var2) {
      return var0 == null ? var2 : var0.optBoolean(var1, var2);
   }

   static String str(JSONObject var0, String var1) {
      return var0 == null ? null : var0.optString(var1, null);
   }

   static String optStr(JSONObject var0, String var1) {
      return var0.optString(var1, "");
   }

   /** 宽松 int：trim 后解析，异常返缺省值 */
   static int parseInt(String var0, int var1) {
      try {
         return Integer.parseInt(var0.trim());
      } catch (Throwable var3) {
         return var1;
      }
   }

   /** 宽松数字：抽取字符串里的数字序列（"3.5k 步" -> 3），异常返 0 */
   static int parseNum(String var0) {
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
         } catch (Throwable var3) {
            return 0;
         }
      }
   }

   /** 金币数解析："1,234" / "1.2k" / "\"567\"" -> long；无效返 -1（任务报告币值域） */
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

   /** 报告行「领取成功N笔」计数（finish 汇总用，行格式由 report 生成侧约定） */
   static int parseCount(String var0) {
      try {
         int var1 = var0.indexOf(58);
         return Integer.parseInt(var0.substring("领取成功".length(), var1).trim());
      } catch (Throwable var2) {
         return 0;
      }
   }
}
