package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译期 STUB —— 只用于 javac classpath，严禁打进 APK dex。
 * 运行时由 LSPosed 提供真实实现。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp);
}
