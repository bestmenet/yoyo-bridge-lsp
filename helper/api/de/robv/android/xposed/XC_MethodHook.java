package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public Object getResult() { throw new UnsupportedOperationException(); }
        public void setResult(Object result) { throw new UnsupportedOperationException(); }
        public Throwable getThrowable() { throw new UnsupportedOperationException(); }
    }

    public static final class Unhook {
        public void unhook() { throw new UnsupportedOperationException(); }
    }
}
