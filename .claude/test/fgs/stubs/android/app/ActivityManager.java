package android.app;
public class ActivityManager {
    public static int importance = 300;
    public static int calls = 0;
    public static class RunningAppProcessInfo { public int importance; public static final int IMPORTANCE_FOREGROUND = 100; }
    public static void getMyMemoryState(RunningAppProcessInfo i) { calls++; i.importance = importance; }
}
