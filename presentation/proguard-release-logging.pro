# Output methods only: never suppress callbacks, error handling, or Object methods.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# Third-party Java code may bypass Android Log and print diagnostics to stderr.
# Preserve overloads that render into a caller-provided stream for error handling.
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
    public static void wtf(...);
    public static void log(...);
}

-assumenosideeffects class timber.log.Timber$Forest {
    public void v(...);
    public void d(...);
    public void i(...);
    public void w(...);
    public void e(...);
    public void wtf(...);
    public void log(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
    public void w(...);
    public void e(...);
    public void wtf(...);
    public void log(...);
}
