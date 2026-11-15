package com.piggyhand.fish.hook;

import android.content.Context;

import com.piggyhand.fish.core.RhFileLog;

import de.robv.android.xposed.XposedBridge;

/**
 * 响应/报告文件仓库——Phase 2 优化6 三段拆分（2026-11-15）。
 * 从 FishMtopHook 文件通道段原样迁出：rh_resp_* 响应落盘与读取、报告文件读取、
 * writeTaskResp 双写（总线 + 分文件）+ .raw/.bad 旁档、限频日志。
 * FishMtopHook（hook 写侧）、ReportServer（命令读侧）、task 包各执行器共用。
 * 文件名、命名规则（api 全名 CRC32 短哈希）、stamp 机制逐字未动（compat 红线）。
 */
final class RespStore {

   private RespStore() {
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }

   /* ---------------- 限频日志（原 logLimit，签名去重 60s 窗口） ---------------- */

   private static volatile String lastErrSig = null;
   private static volatile long lastErrAt = 0;

   private static void logLimit(String msg) {
      long now = System.currentTimeMillis();
      String sig = msg.length() > 48 ? msg.substring(0, 48) : msg;
      synchronized (RespStore.class) {
         if (sig.equals(lastErrSig) && now - lastErrAt < 60000) return;
         lastErrSig = sig;
         lastErrAt = now;
      }
      log(msg);
   }

   /* ---------------- 响应落盘 ---------------- */

   /** 任务响应落盘：rh_resp.json 总线 + rh_resp_<api短哈希>.json 分文件（避免 PC 端清空竞争）
    *  内容校验：非 { [ 开头（对象 toString/空串等坏内容）一律丢弃不落盘——
    *  坏文件被 callApi 读到会 parse fail 报「无响应」，宁缺勿坏让轮询等好内容。
    *  @return true = 实际落盘 */
   static boolean writeTaskResp(String api, String body) {
      if (body == null || body.isEmpty()
              || (body.charAt(0) != '{' && body.charAt(0) != '[')) {
         // v1.5.5 改进 A：discard 行内联原文前 80 字符——日志里即可定性限流页/会话失效页，
         // 不再只有 len 要去设备上翻 raw 旁档
         String head = body == null ? "" : body.substring(0, Math.min(80, body.length()));
         logLimit("[task-resp] discard non-json body for " + api
                 + " (len=" + (body == null ? -1 : body.length()) + ") head=" + head.replace('\n', ' '));
         // v1.5.1 非 JSON 原文落 raw 旁档（+stamp）：2026-11-11 日志实证网关拒绝响应
         // （discard len=224）被丢弃后调用方只能看到「无响应」，拒绝原因无法定性。
         // raw 文件让 callAndWait 立即感知并把原文带回报告/日志。
         if (body != null && !body.isEmpty()) {
            String base = com.piggyhand.fish.net.RespChannel.respBase(api); // 方案 A:全名短哈希,消五组尾段撞车
            writeCacheFile("rh_resp_" + base + ".json.raw", body);
            // v1.5.5 改进 C：固定名 .bad 旁档（不带 stamp，专供 callApi 短路）。
            // callApi 轮询见到它立即返回 GATEWAY_REJECT 归因，不再空等 30s——
            // (2) 号日志实测 30 次 timeout×30s≈15 分钟纯等待。正常响应到达时删除。
            writeCacheFile("rh_resp_" + base + ".json.bad",
                    body.substring(0, Math.min(200, body.length())));
         }
         return false;
      }
      writeCacheFile("rh_resp.json", body);
      String base = com.piggyhand.fish.net.RespChannel.respBase(api); // 方案 A:全名短哈希
      writeCacheFile("rh_resp_" + base + ".json", body);
      // v1.5.5 改进 C：正常响应到达即清 .bad，防止旧拒绝档被下一次请求误配
      try {
         java.io.File bad = cacheFile("rh_resp_" + base + ".json.bad");
         if (bad != null && bad.exists()) bad.delete();
      } catch (Throwable ignored) {
      }
      return true;
   }

   /* ---------------- 基础文件通道 ---------------- */

   static java.io.File cacheFile(String name) {
      Context c = FishMtopHook.appCtx;
      if (c == null) return null;
      try {
         return new java.io.File(c.getExternalCacheDir(), name);
      } catch (Throwable t) {
         return null;
      }
   }

   static String readCacheFile(String name) {
      try {
         java.io.File f = cacheFile(name);
         if (f == null || !f.exists()) return null;
         byte[] b = new byte[(int) f.length()];
         java.io.FileInputStream in = new java.io.FileInputStream(f);
         in.read(b);
         in.close();
         return new String(b, "UTF-8");
      } catch (Throwable t) {
         log("readCacheFile " + name + " FAIL: " + t);
         return null;
      }
   }

   /** 报告类文件住持久区 files/：宿主的 cache/ 冷启动会被系统清空，报告跟着丢。
    *  读顺序 files/ -> cache/（后者兼容 v1.2.7 及以前留在 cache 的老文件）。 */
   static String readReportFile(String name) {
      Context c = FishMtopHook.appCtx;
      if (c != null) {
         try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir != null) {
               java.io.File f = new java.io.File(dir, name);
               if (f.exists() && f.length() > 0) {
                  byte[] b = new byte[(int) f.length()];
                  java.io.FileInputStream in = new java.io.FileInputStream(f);
                  in.read(b);
                  in.close();
                  return new String(b, "UTF-8");
               }
            }
         } catch (Throwable t) {
            log("readReportFile " + name + " FAIL: " + t);
         }
      }
      return readCacheFile(name);
   }

   static void writeCacheFile(String name, String content) {
      try {
         java.io.File f = cacheFile(name);
         if (f == null) return;
         java.io.FileWriter w = new java.io.FileWriter(f, false);
         w.write(content);
         w.close();
         // 同写一个毫秒戳旁路文件：/sdcard 的 mtime 只有 1~2s 精度，
         // 等待方（RhPondTask.callAndWait）靠它判断「这是本轮响应」而不是旧文件
         java.io.File s = cacheFile(name + ".stamp");
         if (s != null) {
            java.io.FileWriter sw = new java.io.FileWriter(s, false);
            sw.write(String.valueOf(System.currentTimeMillis()));
            sw.close();
         }
      } catch (Throwable t) {
         log("writeCacheFile " + name + " FAIL: " + t);
      }
   }
}
