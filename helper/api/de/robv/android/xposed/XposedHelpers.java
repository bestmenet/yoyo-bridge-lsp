package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static Object callMethod(Object instance, String name, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
        Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Object getObjectField(Object object, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setObjectField(Object object, String fieldName, Object value) {
        throw new UnsupportedOperationException();
    }
}
