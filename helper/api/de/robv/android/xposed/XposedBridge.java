package de.robv.android.xposed;

public final class XposedBridge {
    private XposedBridge() {}

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }

    public static void log(String text) {
        throw new UnsupportedOperationException();
    }
}
