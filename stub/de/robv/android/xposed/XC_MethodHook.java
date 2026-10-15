package de.robv.android.xposed;

/** 编译期 STUB —— 运行时由 LSPosed 提供真实实现 */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable t) { this.throwable = t; returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
    }

    /** 编译期 STUB，与真实签名保持一致（hookMethod 返回此类型） */
    public static class Unhook {
    }
}
