package de.robv.android.xposed;

/** 编译期 STUB —— 运行时由 LSPosed 提供真实实现 */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
