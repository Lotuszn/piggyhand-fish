package com.piggyhand.fish;

import android.content.Context;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;

public final class ApiMtop {
   private ApiMtop() {
   }

   public static boolean fire(Context var0, ClassLoader var1, String var2, String var3, String var4, Map<String, String> var5, Map<String, String> var6) {
      try {
         Class var7 = Class.forName("mtopsdk.mtop.intf.Mtop", true, var1);
         Object var8 = instance(var7, var0);
         if (var8 == null) {
            log("[mtop] no instance");
            return false;
         } else {
            String var9 = String.valueOf(call(var8, "getTtid"));
            log("[mtop] instance ttid=" + var9 + " uid=" + call(var8, "getUserId") + " sid=" + mask(String.valueOf(call(var8, "getSid"))));
            Class var10 = Class.forName("mtopsdk.mtop.domain.MtopRequest", true, var1);
            Object var11 = var10.getDeclaredConstructor().newInstance();
            var10.getMethod("setApiName", String.class).invoke(var11, var2);
            var10.getMethod("setVersion", String.class).invoke(var11, var3);
            var10.getMethod("setData", String.class).invoke(var11, var4 == null ? "{}" : var4);
            var10.getMethod("setNeedEcode", boolean.class).invoke(var11, false);
            var10.getMethod("setNeedSession", boolean.class).invoke(var11, false);
            Object var12 = var7.getMethod("build", var10, String.class).invoke(var8, var11, var9);
            if (var12 == null) {
               log("[mtop] build -> null");
               return false;
            } else {
               if (var5 != null && !var5.isEmpty()) {
                  Method var13 = var12.getClass().getMethod("headers", Map.class);
                  Object var14 = var13.invoke(var12, var5);
                  if (var14 != null) {
                     var12 = var14;
                  }

                  log("[mtop] headers " + var5);
               }

               if (var6 != null && !var6.isEmpty()) {
                  Method var18 = var12.getClass().getMethod("addHttpQueryParameter", String.class, String.class);

                  for (Entry var15 : var6.entrySet()) {
                     Object var16 = var18.invoke(var12, var15.getKey(), var15.getValue());
                     if (var16 != null) {
                        var12 = var16;
                     }
                  }

                  log("[mtop] query " + var6);
               }

               Method var19 = var12.getClass().getMethod("asyncRequest");
               Object var21 = var19.invoke(var12);
               log("[mtop] fired " + var2 + " v" + var3 + " apiId=" + var21);
               return true;
            }
         }
      } catch (Throwable var17) {
         log("[mtop] FAIL " + var2 + ": " + var17);
         return false;
      }
   }

   private static Object instance(Class<?> var0, Context var1) {
      Method var2 = null;

      for (Method var6 : var0.getDeclaredMethods()) {
         if ("instance".equals(var6.getName())) {
            Class[] var7 = var6.getParameterTypes();
            if (var7.length == 1 && var7[0] == Context.class) {
               var2 = var6;
               break;
            }
         }
      }

      if (var2 == null) {
         return null;
      } else {
         try {
            var2.setAccessible(true);
            return var2.invoke(null, var1);
         } catch (Throwable var8) {
            log("[mtop] instance() fail: " + var8);
            return null;
         }
      }
   }

   private static Object call(Object var0, String var1) {
      try {
         Method var2 = var0.getClass().getMethod(var1);
         return var2.invoke(var0);
      } catch (Throwable var3) {
         return "?";
      }
   }

   private static String mask(String var0) {
      return var0 != null && var0.length() > 8 ? var0.substring(0, 4) + "***" + var0.substring(var0.length() - 4) : var0;
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }
}
