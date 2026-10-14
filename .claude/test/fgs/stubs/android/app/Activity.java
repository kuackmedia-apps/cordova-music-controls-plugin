package android.app;
import android.content.*;
public class Activity extends Context {
    public static boolean bindResult = true;
    public static int binds = 0, unbinds = 0;
    public static ServiceConnection lastConn;
    public boolean bindService(Intent i, ServiceConnection c, int f) { binds++; lastConn = c; return bindResult; }
    public void unbindService(ServiceConnection c) { unbinds++; }
    public Object getSystemService(String n) { return new android.os.PowerManager(); }
    public String getPackageName() { return "com.test"; }
}
