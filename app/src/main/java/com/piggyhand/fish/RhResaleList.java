package com.piggyhand.fish;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;

/**
 * 倒卖者名单热加载。
 *
 * 数据源是 Gitee 公开仓库的 raw 直链，包里不带任何 token —— 客户端一旦存凭据，
 * 等于把整个仓库的写权限发给所有装机用户。
 *
 * 三条硬规则：
 *   1. 本地缓存优先；网络失败绝不清空缓存，只保留上一份能用的
 *   2. 响应必须能以 "# rh-resale" 开头，否则整份丢弃 —— raw 跟随 302 之后若文件不存在，
 *      拿到的是 HTML 错误页，不校验就会把 HTML 当名字渲染
 *   3. 条数 / 单行长度封顶，防止有人往公开文件里塞超长内容把 UI 撑爆
 */
public final class RhResaleList {
   public static final String URL_RAW =
      "https://gitee.com/lotuszn/piggyhand-fish/raw/main/resale/list.txt";
   private static final String HEAD = "# rh-resale";
   private static final String PREF = "rh_notice";
   private static final String KEY_AT = "resale_at";
   private static final long MIN_GAP_MS = 21600000L;   // 6h，别把 Gitee 匿名 raw 打限流
   private static final int MAX_BYTES = 65536;
   private static final int MAX_ITEMS = 500;
   private static final int MAX_LINE = 80;
   private static final String FILE = "rh_resale.txt";

   private RhResaleList() {
   }

   /** 读本地缓存；没有或读坏一律返回空数组，调用方按“无名单”处理 */
   public static String[] read(Context var0) {
      try {
         File var1 = new File(var0.getFilesDir(), FILE);
         if (!var1.exists() || var1.length() <= 0L) {
            return new String[0];
         }
         String[] var2 = parse(new String(readAll(new FileInputStream(var1), MAX_BYTES), "UTF-8"));
         return var2 == null ? new String[0] : var2;
      } catch (Throwable var3) {
         return new String[0];
      }
   }

   /**
    * 到点就在后台拉一次；回调固定切回主线程。
    * 没到点也会回调（表示“本地已是最新”），调用方不用自己判断节流。
    */
   public static void refreshAsync(final Context var0, final Runnable var1) {
      if (!due(var0)) {
         if (var1 != null) {
            var1.run();
         }
         return;
      }
      new Thread(new Runnable() {
         @Override
         public void run() {
            String var1x = fetch();
            String[] var2x = parse(var1x);
            if (var2x != null) {
               // 校验通过才落盘：坏响应绝不能盖掉上一份好名单
               save(var0, var1x);
               var0.getSharedPreferences(PREF, 0)
                  .edit()
                  .putLong(KEY_AT, System.currentTimeMillis())
                  .apply();
            }
            if (var1 != null) {
               new Handler(Looper.getMainLooper()).post(var1);
            }
         }
      }, "rh-resale").start();
   }

   private static boolean due(Context var0) {
      try {
         SharedPreferences var1 = var0.getSharedPreferences(PREF, 0);
         long var2 = var1.getLong(KEY_AT, 0L);
         if (var2 <= 0L) {
            return true;
         }
         long var4 = System.currentTimeMillis() - var2;
         // 差值为负 = 系统时间被回拨，当作到期重拉，否则回拨一次就能永久拿旧名单
         return var4 < 0L || var4 >= MIN_GAP_MS;
      } catch (Throwable var6) {
         return true;
      }
   }

   private static String fetch() {
      HttpURLConnection var0 = null;
      try {
         var0 = (HttpURLConnection)new URL(URL_RAW).openConnection();
         var0.setConnectTimeout(6000);
         var0.setReadTimeout(8000);
         var0.setInstanceFollowRedirects(true);
         var0.setRequestProperty("User-Agent", "RhResale/1.0 (Android)");
         var0.setRequestProperty("Accept", "text/plain");
         if (var0.getResponseCode() != 200) {
            return null;
         }
         return new String(readAll(var0.getInputStream(), MAX_BYTES), "UTF-8");
      } catch (Throwable var4) {
         return null;
      } finally {
         if (var0 != null) {
            var0.disconnect();
         }
      }
   }

   /** 校验 + 解析；不合规返回 null（调用方据此判断“这份不能用”） */
   private static String[] parse(String var0) {
      try {
         if (var0 == null) {
            return null;
         }
         String var1 = var0.trim();
         if (var1.startsWith("\ufeff")) {
            var1 = var1.substring(1);
         }
         if (!var1.startsWith(HEAD)) {
            return null;
         }

         LinkedHashSet var2 = new LinkedHashSet();
         String[] var3 = var1.split("\r?\n");
         for (int var4 = 0; var4 < var3.length; var4++) {
            String var5 = var3[var4].trim();
            if (var5.length() == 0 || var5.startsWith("#")) {
               continue;
            }
            // 「名字|备注」只取名字；全角竖线、Excel 粘贴带出的制表符一并兼容
            int var6 = indexOfSep(var5);
            if (var6 > 0) {
               var5 = var5.substring(0, var6).trim();
            }
            if (var5.length() == 0) {
               continue;
            }
            if (var5.length() > MAX_LINE) {
               var5 = var5.substring(0, MAX_LINE);
            }
            var2.add(var5);
            if (var2.size() >= MAX_ITEMS) {
               break;
            }
         }
         return (String[])var2.toArray(new String[0]);
      } catch (Throwable var7) {
         return null;
      }
   }

   private static int indexOfSep(String var0) {
      int var1 = var0.indexOf('|');
      int var2 = var0.indexOf('｜');
      int var3 = var0.indexOf('\t');
      int var4 = -1;
      if (var1 >= 0) {
         var4 = var1;
      }
      if (var2 >= 0 && (var4 < 0 || var2 < var4)) {
         var4 = var2;
      }
      if (var3 >= 0 && (var4 < 0 || var3 < var4)) {
         var4 = var3;
      }
      return var4;
   }

   private static void save(Context var0, String var1) {
      File var2 = new File(var0.getFilesDir(), FILE + ".tmp");
      try {
         FileOutputStream var3 = new FileOutputStream(var2);
         var3.write(var1.getBytes("UTF-8"));
         var3.flush();
         var3.close();
         File var4 = new File(var0.getFilesDir(), FILE);
         if (var4.exists() && !var4.delete()) {
            var2.delete();
            return;
         }
         // 先写 tmp 再改名：写到一半被杀不会留下半份名单
         if (!var2.renameTo(var4)) {
            var2.delete();
         }
      } catch (Throwable var5) {
         var2.delete();
      }
   }

   private static byte[] readAll(InputStream var0, int var1) throws Exception {
      ByteArrayOutputStream var2 = new ByteArrayOutputStream();
      try {
         byte[] var3 = new byte[4096];
         int var4;
         while ((var4 = var0.read(var3)) > 0) {
            var2.write(var3, 0, var4);
            if (var2.size() > var1) {
               break;
            }
         }
         return var2.toByteArray();
      } finally {
         var0.close();
      }
   }
}
