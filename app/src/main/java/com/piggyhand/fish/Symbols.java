package com.piggyhand.fish;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

/**
 * 符号解析器（COMPAT_RULES.md 规则 10）。
 *
 * 存在的理由：宿主每次发版重排混淆，类名/方法名会漂移，而业务逻辑没变。
 * 硬编码一个名字 = 名字变了全死；候选表 + 形状校验 = 形状没变就还能活。
 *
 * 判据是 verify，不是名字：
 *   protocolCenter 命中后必须满足「返回对象含 send(2 参方法)」，
 *   否则视为没命中，继续试下一个候选。这样即使撞上同名但语义不同的方法也不会误用。
 */
public final class Symbols {

    private Symbols() {}

    /** 宿主包名：凡是需要按包名查版本/权限的地方都引这个常量，
     *  不允许在业务代码里再写字面量（门禁 R1 会拦）。 */
    public static final String HOST_PKG = "com.taobao.idlefish";

    /** 解析结果：obj 为 null 时 diag 说明全部候选为何失败 */
    public static final class Hit {
        public final Object obj;
        public final int cand;      // 命中候选下标，-1 = 全灭
        public final String via;    // 命中的 类.方法
        public final String diag;

        Hit(Object obj, int cand, String via, String diag) {
            this.obj = obj;
            this.cand = cand;
            this.via = via;
            this.diag = diag;
        }

        public boolean ok() {
            return obj != null;
        }

        public String probe() {
            return ok() ? ("ok#" + cand + " " + via) : ("MISS " + diag);
        }
    }

    /**
     * 协议中心候选表。第一条是 7.25 ~ 7.29 实测可用的写法，
     * 后面几条是同类命名族（不同混淆批次/不同产品线可能换名字）。
     * 新增候选的门槛：必须能通过 hasSend 校验，否则加了也只是噪音。
     */
    private static final String[][] PROTOCOL_CENTER = {
            {"com.taobao.idlefish.xmc.XModuleCenter", "moduleForProtocol"},
            {"com.taobao.idlefish.xmc.XModuleCenter", "getModule"},
            {"com.taobao.idlefish.protocol.Xmc", "moduleForProtocol"},
            {"com.taobao.idlefish.protocol.ModuleRegistry", "find"},
    };

    /** 最近一次命中的候选下标，用于日志降噪（只在变化时打点） */
    private static volatile int lastCand = -2;

    /**
     * 取 sender = 协议中心.moduleForProtocol(PApiContext.class)
     *
     * @param cl      宿主 ClassLoader
     * @param ctxCls  PApiContext 的 Class 对象
     */
    public static Hit protocolCenter(ClassLoader cl, Class<?> ctxCls) {
        if (cl == null || ctxCls == null) {
            return new Hit(null, -1, null, "classloader or PApiContext null");
        }
        for (int i = 0; i < PROTOCOL_CENTER.length; i++) {
            String clsName = PROTOCOL_CENTER[i][0];
            String mName = PROTOCOL_CENTER[i][1];
            Class<?> c = findClass(clsName, cl);
            if (c == null) continue;
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(mName)) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || ps[0] != Class.class) continue;
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    Object r;
                    try {
                        r = m.invoke(null, ctxCls);
                    } catch (Throwable t) {
                        continue;   // 该方法存在但调用失败，换下一个
                    }
                    // 形状校验：拿到的必须是"能发请求的东西"，不是随便一个非空对象
                    if (r != null && hasSend(r.getClass())) {
                        if (lastCand != i) {
                            log("[sym] protocol_center -> " + (i + 1) + "/" + PROTOCOL_CENTER.length
                                    + " " + clsName + "." + mName);
                            lastCand = i;
                        }
                        return new Hit(r, i, clsName + "." + mName, null);
                    }
                }
            } catch (Throwable t) {
                // getDeclaredMethods 可能因为缺依赖类抛 NoClassDefFoundError，换候选
            }
        }
        return new Hit(null, -1, null, "all " + PROTOCOL_CENTER.length + " candidates failed");
    }

    /** sender 的类是否具备 send(2 参) —— 这是"能发请求"的唯一硬判据 */
    public static boolean hasSend(Class<?> c) {
        if (c == null) return false;
        try {
            for (Method m : c.getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterTypes().length == 2) return true;
            }
            for (Method m : c.getDeclaredMethods()) {
                if ("send".equals(m.getName()) && m.getParameterTypes().length == 2) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 按顺序试多个类名，返回第一个能加载的（单类名 = 硬编码，规则 4 不允许新写） */
    public static Class<?> resolveClass(ClassLoader cl, String... names) {
        if (cl == null || names == null) return null;
        for (String n : names) {
            Class<?> c = findClass(n, cl);
            if (c != null) return c;
        }
        return null;
    }

    /* ---------- 其余宿主符号：候选串集中在本文件（门禁白名单），业务代码只调方法 ---------- */

    public static Class<?> papiClass(ClassLoader cl) {
        return resolveClass(cl,
                "com.taobao.idlefish.protocol.net.PApiContext",
                "com.taobao.idlefish.protocol.PApiContext");
    }

    public static Class<?> apiProtocolClass(ClassLoader cl) {
        return resolveClass(cl,
                "com.taobao.idlefish.protocol.net.api.ApiProtocol",
                "com.taobao.idlefish.protocol.api.ApiProtocol");
    }

    public static Class<?> apiCallbackClass(ClassLoader cl) {
        return resolveClass(cl,
                "com.taobao.idlefish.protocol.net.ApiCallBack",
                "com.taobao.idlefish.protocol.ApiCallBack");
    }

    public static Class<?> mtopLauncherClass(ClassLoader cl) {
        return resolveClass(cl,
                "com.taobao.android.remoteobject.easy.MtopLauncher");
    }

    /** 静默加载：宿主混淆/裁剪后类可能真的不存在，不能抛给调用方 */
    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }
}
