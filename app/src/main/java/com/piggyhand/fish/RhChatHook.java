package com.piggyhand.fish;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 💬 聊天页左滑进对方主页 · 禁用开关（RhChatHook v4，事件吞除版）
 *
 * 抓包实锤（2026-10-20 触摸流 + logcat 双抓包）：
 *   聊天页 = FishFlutterBoostActivity（Flutter 容器渲染），左滑手势在 Flutter
 *   引擎层消费，原生容器（ViewPager/ViewDragHelper 等）hook 全部无效；
 *   「左滑看TA的闲鱼号」预览卡片是 Flutter 层跟手渲染的——v3 在
 *   pushRouteImpl（松手后）拦截，预览已经画出来了。v4 改在事件入口吞除。
 *
 * v4 四件套（全部明文类明文方法，零混淆依赖）：
 *   [事件吞除] hook dispatchTouchEvent（Activity+boost 子类双挂）before：
 *              聊天页(cur 含 chat)内 ACTION_MOVE 一旦出现左滑特征
 *              (dx<=-20px 且 |dx|>=1.5|dy|) -> suppressing=true，此后本次
 *              手势流(至 UP/CANCEL)全部 setResult(true)，Flutter 引擎
 *              收不到事件，预览卡片完全不出；垂直滚动起步比值不满足不受影响。
 *   [手势记录] UP 判定横向左滑(|dx|>=180 且 |dx|>=2|dy|) -> lastSwipeAt
 *              （供 pushRouteImpl 兜底；fling 快扫预览未及吞除时兜底）
 *   [路由跟踪] hook FishFlutterBoostActivity onCreate/onNewIntent/onResume：
 *              从 intent 读 Flutter 路由 url -> currentBoostUrl（栈顶即当前页）
 *   [单点拦截] hook FishFlutterBoostDelegate.pushRouteImpl before：
 *              目标 personalPage + 当前页 chat + lastSwipeAt<1200ms
 *              -> setResult(null) 兜底吞路由；点头像等点击无滑动特征不误伤。
 *
 * 开关热生效：回调内 60s 惰性重读 noChatSwipe，切开关约 1 分钟内生效。
 */
public final class RhChatHook {

    /** FishFlutterBoostActivity 类（hook 时解析，判实例/读 url 用） */
    private static Class<?> boostActCls;
    /** 当前栈顶 FlutterBoost 容器的路由 url */
    private static volatile String currentBoostUrl = null;
    /** 最近一次横向左滑手势结束时刻（uptime ms） */
    private static volatile long lastSwipeAt = 0;
    /** 手势判定中间态：DOWN 起点 */
    private static float downX = 0f, downY = 0f;
    /** v4 事件吞除态：当前手势流已判定为聊天页左滑，全部消费 */
    private static volatile boolean suppressing = false;
    /** 拦截日志限频 */
    private static volatile long lastHit = 0;
    /** 开关惰性重读 */
    private static volatile long lastCfg = 0;
    private static volatile boolean enabled = false;
    /** 校准：首见 extras key 集合只打一次日志 */
    private static final Set<String> seenExtrasKeys = new HashSet<>();
    private static volatile boolean loggedUrlProbe = false;

    private RhChatHook() {
    }

    public static void hook(final ClassLoader cl) {
        /* ---------- 1. FishFlutterBoostActivity 跟踪（路由 url + 手势特征） ---------- */
        try {
            boostActCls = Reflect.findClass(
                    "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostActivity", cl);

            XC_MethodHook urlTracker = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String url = readIntentUrl(param.thisObject);
                        if (url != null && url.length() > 0) {
                            currentBoostUrl = url;
                            if (!loggedUrlProbe) {
                                loggedUrlProbe = true;
                                RhFileLog.write("RhChat: boost url 首见 -> " + url);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Reflect.hookAllMethods(boostActCls, "onCreate", urlTracker);
            Reflect.hookAllMethods(boostActCls, "onNewIntent", urlTracker);
            Reflect.hookAllMethods(boostActCls, "onResume", urlTracker);
            RhFileLog.log("RhChat: FishFlutterBoostActivity 跟踪 armed");
        } catch (Throwable t) {
            RhFileLog.log("RhChat: boost activity hook FAILED: " + t);
        }

        /* ---------- 2. 事件吞除 + 手势特征记录（v4：预览卡片连画的机会都没有；父子类双挂防覆写） ---------- */
        XC_MethodHook gestureTracker = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (boostActCls == null || !boostActCls.isInstance(param.thisObject)) return;
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof MotionEvent)) return;
                    MotionEvent ev = (MotionEvent) param.args[0];
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        suppressing = false;
                    } else if (a == MotionEvent.ACTION_MOVE) {
                        float dx = ev.getRawX() - downX;
                        float dy = ev.getRawY() - downY;
                        // 吞除判定：聊天页内出现明确左滑意图（20px 且横向比值 1.5）
                        if (!suppressing && chatEnabled() && isChatPage()
                                && dx <= -20f && -dx >= 1.5f * Math.abs(dy)) {
                            suppressing = true;
                            logLimit2s("左滑吞除启动 dx=" + (int) dx + " dy=" + (int) dy
                                    + " cur=" + currentBoostUrl);
                        }
                        if (suppressing) {
                            param.setResult(true);   // 事件不到 Flutter，预览不画
                        }
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        boolean sup = suppressing;
                        suppressing = false;
                        float dx = ev.getRawX() - downX;
                        float dy = ev.getRawY() - downY;
                        if (sup) {
                            param.setResult(true);   // 吞掉收尾事件，手势流对 Flutter 完整消失
                            logLimit2s("左滑吞除收尾 dx=" + (int) dx + " act=" + a);
                            return;
                        }
                        // 横向意图(|dx|>50)的 UP 全打日志，暴露手势通道死活
                        if (Math.abs(dx) > 50f) {
                            boolean swipeHit = dx < 0 && -dx >= 180f && -dx >= 2f * Math.abs(dy);
                            logLimit2s("gesture UP dx=" + (int) dx + " dy=" + (int) dy
                                    + " hit=" + swipeHit + " cur=" + currentBoostUrl);
                        }
                        if (dx < 0 && -dx >= 180f && -dx >= 2f * Math.abs(dy)) {
                            lastSwipeAt = android.os.SystemClock.uptimeMillis();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            Class<?> actCls = Reflect.findClass("android.app.Activity", cl);
            Reflect.hookAllMethods(actCls, "dispatchTouchEvent", gestureTracker);
            // 子类若覆写 dispatchTouchEvent，Activity 版不会被调用——补挂 boost 子类
            Reflect.hookAllMethods(boostActCls, "dispatchTouchEvent", gestureTracker);
            RhFileLog.log("RhChat: 手势特征记录 armed (Activity+boost 双挂)");
        } catch (Throwable t) {
            RhFileLog.log("RhChat: dispatchTouchEvent hook FAILED: " + t);
        }

        /* ---------- 3. pushRouteImpl 单点拦截 ---------- */
        try {
            Class<?> delegateCls = Reflect.findClass(
                    "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostDelegate", cl);
            Reflect.hookAllMethods(delegateCls, "pushRouteImpl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!chatEnabled()) return;
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        Object opts = param.args[0];
                        String target = routeUrlOf(opts);
                        // v3 全量校准：每次触发必打日志（限频 2s），暴露真实路由 url
                        logLimit2s("pushRoute 触发 opts=" + opts.getClass().getSimpleName()
                                + " target=" + target
                                + " cur=" + currentBoostUrl);
                        if (target == null) return;
                        String lt = target.toLowerCase();
                        if (!lt.contains("personalpage")) return;
                        // 作用域：当前栈顶必须是聊天页
                        String cur = currentBoostUrl;
                        if (cur == null || !cur.toLowerCase().contains("chat")) {
                            logLimit("push personalPage 非聊天页来源 cur=" + cur);
                            return;
                        }
                        // 手势特征：仅拦「刚发生左滑」触发的路由（点头像等正常点击放行）
                        long since = android.os.SystemClock.uptimeMillis() - lastSwipeAt;
                        if (since > 1200) {
                            logLimit("push personalPage 无滑动特征 since=" + since + "ms");
                            return;
                        }
                        param.setResult(null);
                        hit("已拦截聊天页左滑进主页 cur=" + cur + " -> " + target);
                    } catch (Throwable t) {
                        logLimit("pushRoute err: " + t);
                    }
                }
            });
            RhFileLog.log("RhChat: pushRouteImpl 拦截 armed");
        } catch (Throwable t) {
            RhFileLog.log("RhChat: pushRouteImpl hook FAILED: " + t);
        }

        /* ---------- 4. PRouter 发现链（校准日志：聊天/主页经原生路由的真实去向） ---------- */
        try {
            Class<?> pr = Reflect.findClass("com.taobao.idlefish.protocol.nav.PRouter", cl);
            XC_MethodHook discover = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        Object r = param.getResult();
                        if (r == null) return;
                        String name = String.valueOf(r);
                        String url = String.valueOf(param.args[0]);
                        if (url.contains("x_chat") || url.contains("message_chat")
                                || url.contains("custom_chat") || url.contains("personalPage")) {
                            if (!loggedUrlProbe) {
                                loggedUrlProbe = true;
                                RhFileLog.write("RhChat: PRouter " + url + " -> " + name);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            Reflect.hookAllMethods(pr, "getClassNameByUrl", discover);
            Reflect.hookAllMethods(pr, "open", discover);
            RhFileLog.log("RhChat: PRouter 发现链 armed");
        } catch (Throwable t) {
            RhFileLog.log("RhChat: PRouter hook FAILED: " + t);
        }

        RhFileLog.log("RhChat: v4 armed (事件吞除 + FlutterBoost 兜底)");
    }

    /* ---------------- 工具 ---------------- */

    /** 从 Activity intent 读 Flutter 路由 url（FlutterBoost 标准 key 容错试读） */
    private static String readIntentUrl(Object activity) {
        if (!(activity instanceof Activity)) return null;
        Intent in = ((Activity) activity).getIntent();
        if (in == null) return null;
        String[] keys = {"url", "pageName", "route", "routeName"};
        for (String k : keys) {
            String v = in.getStringExtra(k);
            if (v != null && v.length() > 0) return v;
        }
        // 校准：首次打出真实 extras key，据此补 key 名单
        Bundle ex = in.getExtras();
        if (ex != null && seenExtrasKeys.add(String.valueOf(ex.keySet()))) {
            RhFileLog.write("RhChat: boost intent extras keys=" + ex.keySet()
                    + " comp=" + in.getComponent());
        }
        return null;
    }

    /** FlutterBoostRouteOptions -> url（getUrl/getPageName getter 容错，再反射找 url/pageName 字段） */
    private static String routeUrlOf(Object opts) {
        for (String m : new String[]{"getUrl", "getPageName"}) {
            try {
                Object v = Reflect.callMethod(opts, m);
                if (v instanceof String && ((String) v).length() > 0) return (String) v;
            } catch (Throwable ignored) {
            }
        }
        for (String fn : new String[]{"url", "pageName"}) {
            try {
                Class<?> k = opts.getClass();
                while (k != null) {
                    try {
                        Field f = k.getDeclaredField(fn);
                        f.setAccessible(true);
                        Object v = f.get(opts);
                        if (v instanceof String && ((String) v).length() > 0) return (String) v;
                        break;
                    } catch (NoSuchFieldException e) {
                        k = k.getSuperclass();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ---------------- 开关与日志 ---------------- */

    /** 当前栈顶是否聊天页（吞除作用域判定） */
    private static boolean isChatPage() {
        String cur = currentBoostUrl;
        return cur != null && cur.toLowerCase().contains("chat");
    }

    /** 开关热读：60s 惰性重载 noChatSwipe（首调必读） */
    private static boolean chatEnabled() {
        long now = System.currentTimeMillis();
        if (now - lastCfg > 60000) {
            lastCfg = now;
            try {
                ConfigManager c = ConfigManager.load();
                if (c != null) enabled = c.noChatSwipe;
            } catch (Throwable ignored) {
            }
        }
        return enabled;
    }

    private static void hit(String s) {
        long now = System.currentTimeMillis();
        if (now - lastHit < 3000) return;   // 连续滑动限频
        lastHit = now;
        RhFileLog.write("RhChat: " + s);
    }

    private static void logLimit(String s) {
        hit(s);
    }

    /** v3 校准专用：2s 限频（与 hit 的 3s 独立计数，避免校准日志被拦截日志顶掉） */
    private static volatile long lastCal = 0;

    private static void logLimit2s(String s) {
        long now = System.currentTimeMillis();
        if (now - lastCal < 2000) return;
        lastCal = now;
        RhFileLog.write("RhChat: [cal] " + s);
    }
}
