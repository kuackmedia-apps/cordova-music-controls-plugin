package android.os;
import java.util.*;
public class Handler {
    public static final List<long[]> times = new ArrayList<>();
    public static final List<Runnable> tasks = new ArrayList<>();
    public Handler(Looper l) {}
    public boolean post(Runnable r) { return postDelayed(r, 0); }
    public boolean postDelayed(Runnable r, long d) { times.add(new long[]{SystemClock.now + d, seq++}); tasks.add(r); return true; }
    public void removeCallbacks(Runnable r) { for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i) == r) { tasks.remove(i); times.remove(i); } }
    static long seq = 0;
    /** Corre lo que vence hasta `until`, en orden de tiempo y de encolado (como el main looper). */
    public static void runUntil(long until) {
        boolean prev = Looper.onMain; Looper.onMain = true;
        while (true) {
            int best = -1;
            for (int i = 0; i < tasks.size(); i++) {
                long[] t = times.get(i);
                if (t[0] <= until && (best < 0 || t[0] < times.get(best)[0] || (t[0] == times.get(best)[0] && t[1] < times.get(best)[1]))) best = i;
            }
            if (best < 0) break;
            long[] t = times.remove(best); Runnable r = tasks.remove(best);
            if (t[0] > SystemClock.now) SystemClock.now = t[0];
            r.run();
        }
        if (until > SystemClock.now) SystemClock.now = until;
        Looper.onMain = prev;
    }
    public static int pendingDelayed() { int n = 0; for (long[] t : times) if (t[0] > SystemClock.now) n++; return n; }
}
