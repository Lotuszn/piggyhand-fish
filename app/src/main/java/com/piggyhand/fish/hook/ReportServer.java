package com.piggyhand.fish.hook;

import android.app.Application;
import android.content.Context;

import com.piggyhand.fish.config.ConfigManager;
import com.piggyhand.fish.core.Diag;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.RhRuntime;
import com.piggyhand.fish.net.ApiDirect;

import de.robv.android.xposed.XposedBridge;

/**
 * 回环报告/命令服务——Phase 2 优化6 三段拆分（2026-11-15）。
 * 从 FishMtopHook.startReportServer 原样迁出：accept 循环、15 分支命令路由
 * （REPORT/UI/WV 星系/CAPS/PING/CALL/EVAL/各任务命令与报告读取）、渲染进程通道。
 * 对 FishMtopHook 的依赖（单向，包内）：dispatch 命令桥、WebView 控制、EVAL 执行、
 * 全局状态字段；文件 IO 走 RespStore。命令协议与响应文案逐字未动（compat 红线）。
 */
final class ReportServer {

   private ReportServer() {
   }

   private static void log(String var0) {
      XposedBridge.log("[RewardHook] " + var0);
      RhFileLog.write(var0);
   }

   /** report-server 引擎类命令：依赖主进程协议栈（XModuleCenter 只在主进程注册），渲染进程一律拒绝 */
   static boolean isEngineCmd(String req) {
      if (req == null) return false;
      String r = req.trim();
      String[] p = {"RUN", "COIN", "GAME", "POND", "OLIVER", "CASH"};
      for (int i = 0; i < p.length; i++) {
         if (r.startsWith(p[i])) return true;
      }
      return false;
   }

   /** 本机回环端口服务：accept → 收到含 REPORT 的请求 → 回写钓鱼报告全文 */
   static void start() {
      String proc = null;
      try {
         proc = (String) Application.class.getMethod("getProcessName").invoke(null);
      } catch (Throwable ignored) {
      }
      // v1.5.1 渲染进程通道：WebView 实例只在 :normal_render_proc0，WV_RESUME/WV_EVAL 必须在
      // 持有实例的进程里执行。主进程照旧 27715；渲染进程 27717（reportPort+2），只支持 PING/WV_*
      final boolean isRenderProc = proc != null && proc.contains(":");
      if (isRenderProc && !proc.contains("render")) return; // 渲染进程专用通道；其余子进程不需要

      log("[report-server] enter, proc=" + proc + " userId=" + ConfigManager.myUserId()
              + " port=" + (ConfigManager.reportPort() + (isRenderProc ? 2 : 0)));
      new Thread(new Runnable() {
         @Override
         public void run() {
            java.net.ServerSocket ss = null;
            // 端口按 userId 偏移（0 → 27715 / 分身 999 → 28714）：回环端口全设备共享，
            // 固定端口会被另一个用户的闲鱼抢占，后绑定者直接抛异常
            // 渲染进程再 +2（27717）：主/渲两个服务不能抢同一端口
            final int port = ConfigManager.reportPort() + (isRenderProc ? 2 : 0);
            try {
               ss = new java.net.ServerSocket(port, 50,
                       java.net.InetAddress.getByName("127.0.0.1"));
               log("[report-server] listening 127.0.0.1:" + port);
               if (!isRenderProc) {
                  // Phase 2b:就绪状态移入 core.RhRuntime,task 侧读它不再引 hook 包
                  RhRuntime.engineListening = true;
               }
               while (true) {
                  final java.net.Socket s = ss.accept();
                  // 每连接独立线程：单连接挂起不能堵死服务（串行循环曾致 CLOSE_WAIT 堆积）
                  new Thread(new Runnable() {
                     @Override
                     public void run() {
                        try {
                           log("[report-server] conn accepted");
                           s.setSoTimeout(3000); // 读超时防永久挂起
                           java.io.BufferedReader br = new java.io.BufferedReader(
                                   new java.io.InputStreamReader(s.getInputStream()));
                           String req = br.readLine();
                           log("[report-server] req=" + req);
                           String body = "";
                           // 引擎命令一律拒在渲染进程：COIN/GAME/COINEX 依赖
                           // XModuleCenter.moduleForProtocol(PApiContext)，而协议模块只在主进程
                           // Application.onCreate 里注册，渲染进程调用恒返回 null。
                           // 不拦会让调用方拿到 OK started 后全链路 无响应/TASK_PLATFORM_ERROR，
                           // 误判成版本不兼容（2026-11-12 7.29.10 实测踩坑）。
                           if (isRenderProc && isEngineCmd(req)) {
                              log("[report-server] reject engine cmd in render proc: " + req);
                              body = "WRONG_PROC";
                           } else if (req != null && req.startsWith("WV_RESUME")) {
                              // v1.5.1 渲染进程专用：全局恢复 WebView timers（pauseTimers 失衡冻结 H5/游戏页的抢救通道）
                              body = FishMtopHook.wvResumeAll();
                           } else if (req != null && req.startsWith("WV_DEBUG")) {
                              // v1.5.1 开 U4/系统 WebView 的 CDP 远程调试口（core 就绪后 UI 线程调用）：
                              // chrome-devtools Page.setWebLifecycleState('active') 是 renderer 深挂起的正统恢复通道
                              body = FishMtopHook.wvEnableDebug();
                           } else if (req != null && req.startsWith("WV_RELOAD")) {
                              // v1.5.1 强制重载当前页（重建 JS 上下文；U4 renderer 深度挂起时 evaluate 不响应的最后一招）
                              body = FishMtopHook.wvReloadAll();
                           } else if (req != null && req.startsWith("WV_EVAL ")) {
                              // v1.5.1 渲染进程专用：对捕获到的每个 WebView evaluateJavascript（结果落盘 rh_wv_eval.txt）
                              body = FishMtopHook.wvEvalAll(req.substring(8));
                           } else if (req != null && req.trim().startsWith("CAPS")) {
                              // 能力矩阵（COMPAT_RULES 规则 9/12）：只做静态符号探测，
                              // 不发任何网络请求——探测类请求会进风控计数，这里连只读接口都不打。
                              // 主进程与渲染进程都能出结果，对比两份即可判定 NO_PATH:wrong-proc。
                              org.json.JSONObject caps = Diag.caps(FishMtopHook.appCtx, !isRenderProc, port);
                              String js = caps.toString();
                              RespStore.writeCacheFile("rh_caps.json", js);
                              log("[caps] " + js);
                              body = js;
                           } else if (!isRenderProc && req != null && req.trim().equals("PING")) {
                              // 身份回执（2026-10-23）：回环端口跨用户互通，仅凭"连得上"无法
                              // 证明是本用户的引擎。回传自身 uid/userId，App 侧据此校验归属。
                              int uid = android.os.Process.myUid();
                              body = "RH|" + uid + "|" + ConfigManager.userIdOf(uid) + "|"
                                      + android.os.Process.myPid();
                           } else if (req != null && req.trim().equals("REPORT")) {
                              body = RespStore.readReportFile("rh_tasks_result.txt");
                              if (body == null) body = "";
                           } else if (req != null && req.trim().equals("UI")) {
                              // 桌面图标隐藏后的软件入口：显式 ComponentName 拉起配置界面。
                              // 模块跑在闲鱼进程（另一个 uid），跨包启动必须带 NEW_TASK；
                              // 宿主要在后台时被系统拦是已知限制，adb 的 am start 可作兜底。
                              try {
                                 android.content.Intent uit = new android.content.Intent();
                                 uit.setComponent(new android.content.ComponentName(
                                         "com.piggyhand.fish", "com.piggyhand.fish.ui.MainActivity"));
                                 uit.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                 FishMtopHook.appCtx.startActivity(uit);
                                 body = "OK";
                                 log("[ui] launch main activity requested");
                              } catch (Throwable t) {
                                 body = "FAIL " + t;
                                 log("[ui] launch FAIL: " + t);
                              }
                           } else if (req != null && req.startsWith("CASH_REPORT")) {
                              // v1.2.9 开红包报告读取
                              body = RespStore.readReportFile("rh_tasks_cash.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("CASHSIGN_REPORT")) {
                              // 2026-11-26 现金签到·自动领红包报告（必须先于 CASHSIGN 判断）
                              body = RespStore.readReportFile("rh_tasks_cashsign.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("CASHOPEN_REPORT")) {
                              // 2026-11-26 现金签到·自动开红包报告
                              body = RespStore.readReportFile("rh_tasks_cashopen.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("CASHSIGN_STATE")) {
                              // 面板状态条（读报告末行，不发请求）
                              body = FishMtopHook.dispatch("CASHSIGN_STATE", null, "NONE");
                           } else if (req != null && req.trim().equals("CASHSIGN")) {
                              // 手动推进一轮任务面板（结果 CASHSIGN_REPORT 读）
                              body = FishMtopHook.dispatch("CASHSIGN", null, "OK started");
                           } else if (req != null && req.trim().equals("CASHOPEN")) {
                              // 手动开红包到归零（结果 CASHOPEN_REPORT 读）
                              body = FishMtopHook.dispatch("CASHOPEN", null, "OK started");
                           } else if (req != null && req.startsWith("JOIN_REPORT")) {
                              // v1.2.9 投币报告读取（必须先于 JOIN 的前缀匹配）
                              body = RespStore.readReportFile("rh_tasks_join.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.trim().equals("JOIN")) {
                              // v1.2.9 手动投币一轮（后台执行，结果 JOIN_REPORT 读；Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("JOIN", null, "OK started");
                           } else if (req != null && req.startsWith("CASH_STATE")) {
                              // v1.2.9 两组状态合并回传：CLAIM|day|state|round|lastRet
                              //                        #JOIN|day|state|...
                              // App 侧按 '#' 拆两段、按 '|' 取 [1]=day [2]=state 定文案
                              body = FishMtopHook.dispatch("CASH_STATE", null, "NONE");
                           } else if (req != null && req.trim().equals("CLAIM")) {
                              // v1.2.9 手动开红包一轮（结果 CASH_REPORT 读；Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("CLAIM", null, "OK started");
                           } else if (req != null && req.startsWith("CYCLE_REPORT")) {
                              // v1.2.10 圈数挑战手动触发结果读取（必须先于 CYCLE 的前缀匹配）
                              body = RespStore.readReportFile("rh_tasks_cycle.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("CYCLE")) {
                              // v1.2.10 手动跑一次圈数挑战查档+领取（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("CYCLE", null, "OK started");
                           } else if (req != null && req.startsWith("CLEAR_MARKS")) {
                              // v1.5.3 清除全部当日任务标记（同步执行并回显；Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("CLEAR_MARKS", null, "FAIL no-hook");
                           } else if (req != null && req.startsWith("VD_OFF")) {
                              // v1.5.6 后台保活：UI 开关实时拆窗（必须先于 VD_ON 前缀判断无冲突，二者独立）
                              body = FishMtopHook.dispatch("VD_OFF", null, "OK");
                           } else if (req != null && req.startsWith("VD_ON")) {
                              // v1.5.6 后台保活：UI 开关实时建窗
                              body = FishMtopHook.dispatch("VD_ON", null, "OK");
                           } else if (req != null && req.startsWith("GAME_REPORT")) {
                              // v1.2.9 游戏任务报告读取（必须先于 GAME 的前缀匹配）
                              body = RespStore.readReportFile("rh_tasks_game.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("GAME")) {
                              // v1.2.9 手动跑一轮游戏任务（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("GAME", null, "OK started");
                           } else if (req != null && req.startsWith("COINEX_REPORT")) {
                              // v1.4.9 自动兑换报告读取（必须先于 COINEX / COIN 的前缀匹配）
                              body = RespStore.readReportFile("rh_tasks_coinexch.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("COINEX")) {
                              // v1.4.9 手动执行一次兑换检查（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("COINEX", null, "OK started");
                           } else if (req != null && req.startsWith("COIN_REPORT")) {
                              // v1.4.6 赚游戏币任务报告读取（必须先于 COIN 的前缀匹配）
                              body = RespStore.readReportFile("rh_tasks_coin.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("COIN")) {
                              // v1.4.6 手动跑一轮赚游戏币任务（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("COIN", null, "OK started");
                           } else if (req != null && req.startsWith("PONDFEED_REPORT")) {
                              // v1.4.1 自动投喂报告读取（必须先于 PONDFEED / POND 的前缀匹配）
                              body = RespStore.readCacheFile("rh_tasks_pondfeed.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("PONDFEED")) {
                              // v1.4.1 手动投喂（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("PONDFEED", null, "OK started");
                           } else if (req != null && req.startsWith("POND_REPORT")) {
                              // v1.0.9 鱼塘任务报告读取
                              body = RespStore.readCacheFile("rh_tasks_pond.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("OLIVER_REPORT")) {
                              // v1.1.9 会员任务报告读取（必须先于 OLIVER 判断，
                              // 否则 OLIVER_REPORT 会被前缀匹配吃掉）
                              body = RespStore.readCacheFile("rh_tasks_oliver.txt");
                              if (body == null) body = "NO_REPORT";
                           } else if (req != null && req.startsWith("OLIVER_STATE")) {
                              // v1.1.9 会员任务完成状态查询（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("OLIVER_STATE", null, "TODO");
                           } else if (req != null && req.startsWith("OLIVER")) {
                              // v1.1.9 会员任务引擎触发（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("OLIVER", null, "OK started");
                           } else if (req != null && req.startsWith("POND")) {
                              // v1.0.9 鱼塘任务引擎触发（Phase 2b 分发迁 TaskRegistry）
                              body = FishMtopHook.dispatch("POND", null, "OK started");
                           } else if (req != null && req.startsWith("RUN")) {
                              // 任务触发指令（绕过 MIUI 对跨 App 广播的拦截）：
                              // RUN            → 默认全任务
                              // RUN {"signin":true,...} → 带 extras
                              String extras = req.length() > 3 ? req.substring(3).trim() : null;
                              body = FishMtopHook.dispatch("RUN", extras, "OK");
                           } else if (req != null && req.startsWith("CALL ")) {
                              // v1.1 直调指令：CALL {"api":"mtop...","version":"1.0","data":{...}}
                              // 进程内 ApiDirect 直调 → 等全局 MtopFinishEvent 落盘 → 回传响应全文
                              String json = req.substring(5).trim();
                              try {
                                 org.json.JSONObject jo = new org.json.JSONObject(json);
                                 final String cApi = jo.optString("api");
                                 final String cVer = jo.optString("version", "1.0");
                                 Object dObj = jo.opt("data");
                                 final String cData = dObj == null ? "{}" : String.valueOf(dObj);
                                 final String respBase = com.piggyhand.fish.net.RespChannel.respBase(cApi); // 方案 A:与写侧同规则
                                 // 先删旧响应文件再轮询 exists()：部分 ROM（Android 10 sdcardfs）
                                 // lastModified 有缓存延迟，mtime 判断会漏掉新响应（MIX3 实测）
                                 try {
                                    java.io.File f0 = RespStore.cacheFile("rh_resp_" + respBase + ".json");
                                    if (f0 != null && f0.exists()) f0.delete();
                                 } catch (Throwable ignored) {
                                 }
                                 boolean ok = ApiDirect.send(FishMtopHook.appCtx.getClassLoader(), cApi, cVer, cData);
                                 if (ok) {
                                    body = "NO_RESP";
                                    for (int i = 0; i < 16; i++) {
                                       Thread.sleep(250);
                                       java.io.File f = RespStore.cacheFile("rh_resp_" + respBase + ".json");
                                       if (f != null && f.exists()) {
                                          body = RespStore.readCacheFile("rh_resp_" + respBase + ".json");
                                          break;
                                       }
                                    }
                                 } else {
                                    body = "SEND_FAIL";
                                 }
                              } catch (Throwable t) {
                                 body = "ERR " + t;
                              }
                           } else if (req != null && req.startsWith("EVAL ")) {
                              body = evalViaWebView(req.substring(5).trim());
                           }
                           java.io.OutputStream os = s.getOutputStream();
                           os.write(body.getBytes("UTF-8"));
                           os.flush();
                           log("[report-server] responded " + body.length() + " chars");
                        } catch (Throwable t) {
                           log("[report-server] conn err: " + t);
                        } finally {
                           try {
                              s.close();
                           } catch (Throwable ignored) {
                           }
                        }
                     }
                  }, "rh-report-conn").start();
               }
            } catch (Throwable t) {
               log("[report-server] FAILED: " + t);
            } finally {
               try {
                  if (ss != null) ss.close();
               } catch (Throwable ignored) {
               }
            }
         }
      }, "rh-report-srv").start();
   }

   /**
    * v1.4.5 H5 自动化：EVAL <js> → 最近 WebView 执行 → document.title 桥回传。
    * UC 多进程模式下 ValueCallback 对象无法跨 binder 传回（Proxy 静默丢失），
    * 改为 JS 侧把结果写 document.title="__RH__"+json，native 轮询 getTitle() 取回。
    * （原 startReportServer 内联段抽出，逻辑逐字未动。）
    */
   private static String evalViaWebView(String js) {
      String body;
      final Object wv = FishMtopHook.lastWebView;
      if (wv == null && FishMtopHook.allWebViews.isEmpty()) {
         body = "NO_WEBVIEW";
      } else {
         try {
            final String wrapped =
                    "(function(){var __r;try{__r=(function(){" + js
                            + "})()}catch(e){__r='JSERR '+e}"
                            + ";try{document.title='__RH__'+(typeof __r==='object'?"
                            + "JSON.stringify(__r):String(__r))}catch(e){}})()";
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final String[] out = new String[1];
            final String jsF = js;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(new Runnable() {
               @Override
               public void run() {
                  // 主线程状态机：probe 各实例 → 选 goofish 页 → 执行 EVAL
                  final android.os.Handler h =
                          new android.os.Handler(android.os.Looper.getMainLooper());
                  final java.util.List<Object> cands =
                          new java.util.ArrayList<Object>(FishMtopHook.allWebViews);
                  if (cands.isEmpty() && wv != null) cands.add(wv);
                  final int[] idx = {0};
                  final Object[] chosen = {null};
                  final String[] probeUrl = {null};
                  Runnable[] step = new Runnable[1];
                  final boolean[] probing = {true};
                  // 单实例 probe：注入标记 → 400ms 后读 title
                  step[0] = new Runnable() {
                     @Override
                     public void run() {
                        if (probing[0]) {
                           if (idx[0] >= cands.size()) {
                              // 全部 probe 完：没有 goofish 页就退回 lastWebView
                              probing[0] = false;
                              chosen[0] = (wv != null) ? wv
                                      : (cands.isEmpty() ? null
                                      : cands.get(cands.size() - 1));
                              h.post(this);
                              return;
                           }
                           Object cur = cands.get(idx[0]);
                           String mark = "__RP" + idx[0] + "__";
                           try {
                              FishMtopHook.invokeEvalOne(cur,
                                      "document.title='" + mark
                                              + "'+location.href.slice(0,160)");
                           } catch (Throwable t) {
                              out[0] = "ERR probe " + t;
                              latch.countDown();
                              return;
                           }
                           final String mk = mark;
                           final Object cw = cur;
                           h.postDelayed(new Runnable() {
                              @Override
                              public void run() {
                                 try {
                                    String t = (String) cw.getClass()
                                            .getMethod("getTitle")
                                            .invoke(cw);
                                    if (t != null && t.contains(mk)) {
                                       String href = t.substring(
                                               t.indexOf(mk) + mk.length());
                                       if (href.contains("goofish")
                                               || href.contains("h5.m.")) {
                                          chosen[0] = cw;
                                          probeUrl[0] = href;
                                          probing[0] = false;
                                          h.post(step[0]);
                                          return;
                                       }
                                    }
                                 } catch (Throwable ignored) {
                                 }
                                 idx[0]++;
                                 h.post(step[0]);
                              }
                           }, 400);
                        } else {
                           // 执行真正的 EVAL
                           Object target = chosen[0];
                           if (target == null) {
                              out[0] = "NO_TARGET";
                              latch.countDown();
                              return;
                           }
                           try {
                              FishMtopHook.invokeEvalOne(target, wrapped);
                           } catch (Throwable t) {
                              out[0] = "ERR eval " + t;
                              latch.countDown();
                              return;
                           }
                           final long deadline =
                                   System.currentTimeMillis() + 10000;
                           Runnable[] poll = new Runnable[1];
                           final Object tw = target;
                           poll[0] = new Runnable() {
                              @Override
                              public void run() {
                                 try {
                                    String t = (String) tw.getClass()
                                            .getMethod("getTitle")
                                            .invoke(tw);
                                    if (t != null && t.contains("__RH__")) {
                                       out[0] = t.substring(
                                               t.indexOf("__RH__") + 6);
                                       latch.countDown();
                                       return;
                                    }
                                 } catch (Throwable ignored) {
                                 }
                                 if (System.currentTimeMillis() < deadline
                                         && out[0] == null) {
                                    h.postDelayed(poll[0], 250);
                                 } else if (out[0] == null) {
                                    out[0] = "EVAL_TIMEOUT";
                                    latch.countDown();
                                 }
                              }
                           };
                           h.postDelayed(poll[0], 300);
                        }
                     }
                  };
                  h.post(step[0]);
               }
            });
            latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
            body = out[0] == null ? "EVAL_TIMEOUT" : out[0];
         } catch (Throwable t) {
            body = "ERR " + t;
         }
      }
      return body;
   }
}
