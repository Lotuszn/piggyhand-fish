package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 编译期 STUB —— 运行时由 LSPosed 提供真实实现 */
public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {}
    public static Object newInstance(Class<?> clazz, Object... args) { return null; }
    public static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) { return null; }
    public static java.lang.reflect.Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) { return null; }
    public static Object findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
}