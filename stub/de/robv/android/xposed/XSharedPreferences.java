package de.robv.android.xposed;

/** 编译期 STUB —— 运行时由 LSPosed 提供真实实现 */
public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public void reload() {}
    public String getString(String key, String defValue) { return defValue; }
    public boolean getFile() { return false; }
}
