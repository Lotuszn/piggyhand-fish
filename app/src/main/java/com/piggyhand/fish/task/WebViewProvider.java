package com.piggyhand.fish.task;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.webkit.WebView;

import com.piggyhand.fish.core.RhFileLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * WebView 实例供给器——游戏频道任务（channel_click）的真实上下文执行通道。
 *
 * 为什么需要它（2026-11-16 取证链，见 _dev/rh_apiproto3.jsonl）：
 *   ① channel.revisit.complete 模块直调 SUCCESS 但任务不推进（只翻 channel_revisit 类）；
 *   ② 带参真实打开频道页：mtop 流完整 + behavior.idlefish.expose.user.xyhome 曝光已发，
 *      停留 90s 任务仍 ACCEPTED；
 *   ③ 页面点击 handler 的真实链 = LI('game_click') CLK 埋点（goldlog/gm.mmstat 通道，
 *      非 mtop）+ LZ 带参跳转 —— CLK 伪造走 HTTP 需 spm/cna/网关校验，成本高。
 * 结论：让真实页面自己跑完整点击链。模块在容器 WebView 里 evaluateJavascript 注入，
 * 埋点/跳转/曝光全部在真实上下文（asac/spm/cna 天然正确）发生。
 *
 * 抓取方式：hook 系统 webkit 与 U4 内核（com.uc.webview.export.WebView）的全部构造器，
 * 只留宿主为 H5 容器（WebHybridActivity 等）的实例弱引用。
 */
public final class WebViewProvider {

   private static final class Slot {
      final WeakReference<Object> ref;   // WebView 实例（WVUCWebView / 系统 webkit 均可）
      final String activity;             // 宿主 activity 类名
      final long created;
      /** 运行时解析出的 evaluateJavascript(String, XxxValueCallback)——闲鱼实际类
       *  android.taobao.windvane.extra.uc.WVUCWebView 类名不含 com.uc 前缀，
       *  不能按类名判内核，按方法签名第二参数类型走回调构造（2026-11-16 实证） */
      final Method evMethod;
      final Class<?> vcbType;            // 回调参数类型（统一动态代理实现）
      Slot(Object wv, String act, Method ev, Class<?> vcb) {
         ref = new WeakReference<>(wv);
         activity = act;
         created = System.currentTimeMillis();
         evMethod = ev;
         vcbType = vcb;
      }
   }

   private static final List<Slot> SLOTS = new CopyOnWriteArrayList<>();
   private static volatile boolean installed = false;
   /** 实例最大可信年龄：超过视为页面已废弃（WebHybridActivity 每页新建 WebView，不会复用） */
   private static final long MAX_AGE_MS = 10 * 60 * 1000L;

   private WebViewProvider() {
   }

   /** MainHook 装配点：hook 双内核 WebView 全部构造器 */
   public static synchronized void install(ClassLoader cl) {
      if (installed) return;
      installed = true;
      XC_MethodHook hook = new XC_MethodHook() {
         @Override
         protected void afterHookedMethod(MethodHookParam param) {
            try {
               Object wv = param.thisObject;
               if (wv == null) return;
               String act = hostActivity(param.args);
               if (act == null) return;
               // 只留 H5 容器页的实例（频道任务页都从这里开），避免采集到业务内嵌 WebView
               if (act.contains("WebHybrid") || act.contains("Browser") || act.contains("WebView")) {
                  // 解析本类可用的 evaluateJavascript(String, Vcb)：签名决定回调构造方式
                  Method ev = null;
                  for (Method m : wv.getClass().getMethods()) {
                     if ("evaluateJavascript".equals(m.getName()) && m.getParameterTypes().length == 2
                           && m.getParameterTypes()[0] == String.class) {
                        ev = m;
                        break;
                     }
                  }
                  Class<?> vcb = ev != null ? ev.getParameterTypes()[1] : null;
                  if (ev != null && vcb.isInterface()) {
                     SLOTS.add(new Slot(wv, act, ev, vcb));
                     while (SLOTS.size() > 8) SLOTS.remove(0);
                     RhFileLog.write("[wvp] capture " + wv.getClass().getName()
                           + " vcb=" + vcb.getName() + " @ " + act);
                  } else if (ev != null) {
                     RhFileLog.write("[wvp] capture skip: vcb not interface " + vcb);
                  } else {
                     RhFileLog.write("[wvp] capture skip: no evaluateJavascript(String,?)");
                  }
               }
            } catch (Throwable t) {
               RhFileLog.write("[wvp] capture fail " + t);
            }
         }
      };
      // ① 系统 webkit WebView（兜底路；闲鱼部分容器可能走系统内核）
      try {
         for (Constructor<?> c : WebView.class.getDeclaredConstructors()) {
            XposedBridge.hookMethod(c, hook);
         }
      } catch (Throwable t) {
         RhFileLog.write("[wvp] hook sys fail " + t);
      }
      // ② U4 内核 WebView（闲鱼正式内核；类不存在说明该版本不用 U4，静默）
      try {
         Class<?> u4 = XposedHelpers.findClass("com.uc.webview.export.WebView", cl);
         for (Constructor<?> c : u4.getDeclaredConstructors()) {
            XposedBridge.hookMethod(c, hook);
         }
      } catch (Throwable t) {
         RhFileLog.write("[wvp] hook u4 fail " + t);
      }
   }

   /** 从构造参数推断宿主 Activity（支持 wrapper context 一层解包） */
   private static String hostActivity(Object[] args) {
      if (args == null) return null;
      for (Object a : args) {
         if (a instanceof Activity) return a.getClass().getName();
         if (a instanceof Context) {
            Context c = (Context) a;
            try {
               Context base = ((ContextWrapper) c).getBaseContext();
               if (base instanceof Activity) return base.getClass().getName();
            } catch (Throwable ignored) {
            }
            if (c instanceof Activity) return c.getClass().getName();
         }
      }
      return null;
   }

   /** 最近创建且仍存活的容器 WebView；无则 null */
   private static Slot freshest() {
      long now = System.currentTimeMillis();
      for (int i = SLOTS.size() - 1; i >= 0; i--) {
         Slot s = SLOTS.get(i);
         Object wv = s.ref.get();
         if (wv == null) {
            SLOTS.remove(i);
            continue;
         }
         if (now - s.created > MAX_AGE_MS) continue;
         return s;
      }
      return null;
   }

   /** 拉起 H5 容器页（WebHybridActivity 是闲鱼通用容器，--es url 传目标地址） */
   public static void openPage(Context ctx, String url) {
      try {
         Intent it = new Intent();
         it.setClassName("com.taobao.idlefish", "com.taobao.idlefish.webview.WebHybridActivity");
         it.putExtra("url", url);
         it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         ctx.startActivity(it);
      } catch (Throwable t) {
         RhFileLog.write("[wvp] openPage fail " + t);
      }
   }

   /** 在最新容器 WebView 里执行 JS；false = 无可用实例。WebView 方法只能在创建线程（主线程）
    *  调用——COIN 跑批线程直调会抛 All WebView methods must be called on the same thread，
    *  所以统一切主线程 post，异步执行（结果只落 RhFileLog，业务判定走 channelTaskStatus）。 */
   public static boolean evalJs(String js) {
      final Slot s = freshest();
      if (s == null) return false;
      final Object wv = s.ref.get();
      if (wv == null) return false;
      final String fjs = js;
      boolean posted = false;
      try {
         new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
               try {
                  // 统一动态代理：WVUCWebView 的 evaluateJavascript(String,
                  // com.uc.webview.export.ValueCallback) 与系统 webkit 签名同形，
                  // 回调参数类型在捕获时解析（Slot.vcbType），此处按类型造代理
                  Object proxy = Proxy.newProxyInstance(s.vcbType.getClassLoader(),
                        new Class[]{s.vcbType},
                        new InvocationHandler() {
                           @Override
                           public Object invoke(Object proxy, Method method, Object[] args) {
                              if ("onReceiveValue".equals(method.getName()) && args != null && args.length > 0) {
                                 String v = String.valueOf(args[0]);
                                 RhFileLog.write("[wvp] js -> " + (v.length() > 400 ? v.substring(0, 400) : v));
                              }
                              return null;
                           }
                        });
                  s.evMethod.invoke(wv, fjs, proxy);
               } catch (Throwable t) {
                  RhFileLog.write("[wvp] evalJs main fail " + t);
               }
            }
         });
         posted = true;
      } catch (Throwable t) {
         RhFileLog.write("[wvp] evalJs post fail " + t);
         return false;
      }
      return posted;
   }

   /** 诊断串（报告回显）：none 或 vcb类型@activity@age */
   public static String describe() {
      Slot s = freshest();
      if (s == null) return "none";
      String vt = s.vcbType == null ? "?" : s.vcbType.getName();
      vt = vt.startsWith("com.uc") ? "u4" : (vt.startsWith("android.webkit") ? "sys" : vt);
      return vt + "@" + s.activity + "@"
            + ((System.currentTimeMillis() - s.created) / 1000) + "s";
   }
}
