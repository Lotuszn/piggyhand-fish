package com.piggyhand.fish;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/**
 * 最小化 Xposed API 依赖：只用 XposedBridge.log / XposedBridge.hookMethod 两个最底层方法，
 * 其余（hookAllMethods / findAndHookMethod / callMethod / callStaticMethod / returnConstant）
 * 全部自实现，规避宿主环境里 API 混淆副本方法缺失的问题。
 */
public final class Reflect {

    private Reflect() {}

    public static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    public static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用实例方法（自动 public/declared 两层查找） */
    public static Object callMethod(Object obj, String name, Object... args) {
        try {
            Method m = findMethod(obj.getClass(), name, args);
            if (m == null) throw new NoSuchMethodException(name + " on " + obj.getClass().getName());
            m.setAccessible(true);
            return m.invoke(obj, normalize(args));
        } catch (Throwable t) {
            XposedBridge.log("[RewardHook] callMethod " + name + " FAIL: " + t);
            return null;
        }
    }

    public static Object callStaticMethod(Class<?> cls, String name, Object... args) {
        try {
            Method m = findMethod(cls, name, args);
            if (m == null) throw new NoSuchMethodException(name + " on " + cls.getName());
            m.setAccessible(true);
            return m.invoke(null, normalize(args));
        } catch (Throwable t) {
            XposedBridge.log("[RewardHook] callStaticMethod " + name + " FAIL: " + t);
            return null;
        }
    }

    public static Object newInstance(Class<?> cls, Object... args) {
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == args.length && matchTypes(ps, args)) {
                    c.setAccessible(true);
                    return c.newInstance(args);
                }
            }
            throw new NoSuchMethodException("ctor of " + cls.getName() + " with " + args.length + " args");
        } catch (Throwable t) {
            XposedBridge.log("[RewardHook] newInstance FAIL: " + t);
            return null;
        }
    }

    /** 替代 XposedBridge.hookAllMethods：按方法名 hook 类内全部同名方法 */
    public static void hookAllMethods(Class<?> cls, String name, XC_MethodHook hook) {
        if (cls == null) return;
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    try {
                        XposedBridge.hookMethod(m, hook);
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[RewardHook] hookAllMethods " + cls.getName() + "." + name + " FAIL: " + t);
        }
        if (n == 0) {
            XposedBridge.log("[RewardHook] hookAllMethods: no method hooked for " + cls.getName() + "." + name);
        }
    }

    /** hook 类内全部构造器（getDeclaredMethods 不含 <init>，必须单独走 getDeclaredConstructors） */
    public static void hookAllConstructors(Class<?> cls, XC_MethodHook hook) {
        if (cls == null) return;
        int n = 0;
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(c, hook);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[RewardHook] hookAllConstructors " + cls.getName() + " FAIL: " + t);
        }
        XposedBridge.log("[RewardHook] hookAllConstructors " + cls.getName() + " hooked=" + n);
    }

    /** 替代 XC_MethodReplacement.returnConstant */
    public static XC_MethodReplacement replaceWith(final Object value) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) {
                return value;
            }
        };
    }

    /* ---------------- 内部工具 ---------------- */

    private static Object[] normalize(Object[] args) {
        // null 参数直接透传，invoke 可处理
        return args == null ? new Object[0] : args;
    }

    private static Method findMethod(Class<?> cls, String name, Object[] args) {
        Method best = null;
        int bestScore = -1;
        try {
            for (Method m : cls.getMethods()) {
                int score = score(m, name, args);
                if (score > bestScore) { bestScore = score; best = m; }
            }
            for (Method m : cls.getDeclaredMethods()) {
                int score = score(m, name, args);
                if (score > bestScore) { bestScore = score; best = m; }
            }
        } catch (Throwable ignored) {
        }
        return bestScore > 0 ? best : null;
    }

    private static int score(Method m, String name, Object[] args) {
        if (!m.getName().equals(name)) return -1;
        Class<?>[] ps = m.getParameterTypes();
        int n = args == null ? 0 : args.length;
        if (ps.length != n) return -1;
        if (!matchTypes(ps, args)) return -1;
        return 1 + (m.isSynthetic() ? 0 : 1);
    }

    private static boolean matchTypes(Class<?>[] ps, Object[] args) {
        int n = args == null ? 0 : args.length;
        for (int i = 0; i < n; i++) {
            Object a = args[i];
            if (a == null) {
                if (ps[i].isPrimitive()) return false;
                continue;
            }
            Class<?> p = ps[i];
            if (p.isPrimitive()) {
                p = box(p);
            }
            if (!p.isInstance(a)) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == boolean.class) return Boolean.class;
        if (p == long.class) return Long.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        return p;
    }
}
