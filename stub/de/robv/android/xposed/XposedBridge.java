package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期 STUB —— 运行时由 LSPosed 提供真实实现。方法签名必须与真实 API 一致（ART 按全描述符解析） */
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }
}
