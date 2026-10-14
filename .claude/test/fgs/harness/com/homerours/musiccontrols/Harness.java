package com.homerours.musiccontrols;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.apache.cordova.CallbackContext;

import java.util.concurrent.atomic.AtomicInteger;

public class Harness {
    static int fails = 0, checks = 0;
    static MusicControlsServiceConnection c;
    static MusicControlsNotificationKiller k;
    static Notification n = new Notification("n1");
    /** Generación de la página vigente (la que tendría MusicControls.pageGen). */
    static int gen = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL: " + what); } else System.out.println("ok:   " + what);
    }
    static String state() { CallbackContext cb = new CallbackContext(); c.answer(cb); idle(); return cb.last; }
    static boolean fgs(String v) { return state().contains("\"fgs\":\"" + v + "\""); }
    static void idle() { Handler.runUntil(SystemClock.now); }
    static void advance(long ms) { Handler.runUntil(SystemClock.now + ms); }
    /** Publish como lo hace el hilo del puente o el pool: encolado en el main looper. */
    static void publish(boolean playing, String trig, boolean fromCreate) { publishGen(playing, trig, fromCreate, gen); }
    static void publishGen(boolean playing, String trig, boolean fromCreate, int g) {
        Looper.onMain = false; c.publish(n, playing, trig, fromCreate, g); Looper.onMain = true;
    }
    /** Comando del usuario (MediaSessionCallback / BroadcastReceiver); eventUptime 0 = ahora. */
    static void command(String trig, boolean promoteIfOff) { command(trig, promoteIfOff, 0); }
    static void command(String trig, boolean promoteIfOff, long eventUptime) {
        Looper.onMain = false; c.onUserCommand(trig, promoteIfOff, eventUptime); Looper.onMain = true; idle();
    }
    /** onReset de MusicControls: generación nueva. [0] = corrió ifHeld (publica la pausa), [1] = ifNotHeld. */
    static boolean[] reset() {
        gen++;
        final boolean[] r = {false, false};
        c.onWebViewReset(gen, () -> r[0] = true, () -> r[1] = true);
        idle();
        return r;
    }
    static void policy(long graceMs, int legacy, CallbackContext cb) { c.setPolicy(graceMs, legacy, cb); idle(); }

    static void fresh(int sdk, boolean visible) {
        Handler.tasks.clear(); Handler.times.clear();
        Build.VERSION.SDK_INT = sdk;
        ActivityManager.importance = visible ? 100 : 300;
        PowerManager.ignoring = false;
        Activity.bindResult = true; Activity.binds = 0; Activity.unbinds = 0;
        gen = 0;
        c = new MusicControlsServiceConnection(new Activity(), 7824);
        ActivityManager.importance = 300;
        c.ensureBound(); idle();
        k = new MusicControlsNotificationKiller();
        c.onServiceConnected(null, new KillBinder(k));
    }

    public static void main(String[] a) {
        System.out.println("== 1. visible + play => ON; pausa => GRACE; vuelve dentro de la gracia sin startForeground");
        fresh(34, true);
        publish(true, "create", true); idle();
        check(fgs("on") && k.starts == 1, "ON con un solo startForeground " + state());
        publish(false, "pause", false); idle();
        check(fgs("grace") && k.stopsDetach == 0, "pausa => GRACE sin stopForeground");
        advance(599000);
        check(fgs("grace"), "a los 599 s sigue en GRACE");
        publish(true, "play", false); idle();
        check(fgs("on") && k.starts == 1 && k.stopsDetach == 0, "retoma => ON sin startForeground nuevo");
        publish(true, "play", false); publish(true, "play", false); idle();
        check(k.starts == 1, "true repetidos no reintentan");

        System.out.println("== 2. la gracia vence => DETACH y OFF");
        publish(false, "pause", false); idle(); advance(600000);
        check(fgs("off") && k.stopsDetach == 1 && k.stopsRemove == 0, "gracia vencida => DETACH " + state());

        System.out.println("== 3. atrás sin exención => DEFERRED sin intentar; al verse => ON");
        c.onHidden(); advance(5000);
        publish(true, "play", false); idle();
        check(fgs("deferred") && k.starts == 1 && state().contains("no-exemption"), "DEFERRED sin startForeground " + state());
        int amCalls = ActivityManager.calls, pmCalls = PowerManager.calls;
        for (int i = 0; i < 10; i++) { publish(true, "play", false); advance(1000); }
        check(k.starts == 1, "cada segundo sigue sin intentar");
        check(ActivityManager.calls == amCalls && PowerManager.calls == pmCalls,
                "sin oportunidad nueva no vuelve a consultar la exención (sin un binder de AMS por segundo)");
        c.onVisible();
        check(fgs("on") && k.starts == 2, "visible => promueve " + state());

        System.out.println("== 4. ventana de un comando: bordes, seek y hora de la tecla");
        fresh(34, true); publish(true, "create", true); idle(); c.onHidden();
        publish(false, "pause", false); idle(); advance(600000); // OFF con notificación, oculto hace 600 s
        advance(10000);
        publish(true, "play", false); idle();
        check(fgs("deferred") && k.starts == 1, "Cast desde la TV tras la gracia => DEFERRED");
        command("session:play", true);
        check(fgs("on") && k.starts == 2 && state().contains("window:session:play"), "comando => ON " + state());
        publish(false, "pause", false); idle(); advance(600000); // OFF
        command("session:seek", false);
        check(fgs("off") && k.starts == 2, "seek en OFF: abre la ventana sin promover");
        advance(8900); publish(true, "play", false); idle();
        check(fgs("on") && k.starts == 3 && state().contains("window:session:seek"), "8,9 s después del comando => promueve");
        publish(false, "pause", false); idle(); advance(600000); // OFF
        command("session:seek", false);
        advance(9100); publish(true, "play", false); idle();
        check(fgs("deferred") && k.starts == 3 && state().contains("no-exemption"), "9,1 s después del comando => no intenta");
        command("session:seek", false);
        check(fgs("on") && k.starts == 4, "seek en DEFERRED => promueve " + state());
        publish(false, "pause", false); idle(); advance(600000); advance(10000);
        publish(true, "play", false); idle(); // DEFERRED
        command("button:KEYCODE_MEDIA_PLAY", true, SystemClock.now - 9500);
        check(fgs("deferred") && k.starts == 4, "tecla despachada hace 9,5 s (callback demorado) => ventana vencida");
        command("button:KEYCODE_MEDIA_PLAY", true, SystemClock.now - 8500);
        check(fgs("on") && k.starts == 5, "tecla despachada hace 8,5 s => todavía en la ventana");

        System.out.println("== 5. promoción optimista desde OFF: GRACE hasta que el JS confirme o vence");
        fresh(34, true); publish(true, "create", true); idle(); c.onHidden(); advance(5000);
        publish(false, "pause", false); idle(); advance(600000);
        check(fgs("off"), "OFF tras la gracia");
        command("notif:play", true);
        check(fgs("grace") && k.starts == 2, "comando con OFF => promovido en GRACE " + state());
        advance(600000);
        check(fgs("off") && k.stopsDetach == 2, "sin confirmación del JS cae solo");
        command("notif:play", true);
        publish(true, "play", false); idle();
        check(fgs("on") && k.starts == 3, "con confirmación => ON");
        command("session:seek", false);
        check(k.starts == 3, "seek con ON no hace nada");

        System.out.println("== 6. anti-loop: un rechazo no se reintenta hasta una oportunidad nueva");
        fresh(34, true); c.onHidden(); advance(5000);
        k.nextResult = MusicControlsNotificationKiller.FGS_DENIED;
        command("session:play", true); // OFF sin notificación todavía => no promueve
        publish(true, "create", true); idle();         // dentro de la ventana => intenta y falla
        check(fgs("denied") && k.starts == 1, "rechazo reportado " + state());
        for (int i = 0; i < 5; i++) { publish(true, "play", false); advance(1000); }
        check(k.starts == 1 && fgs("denied"), "sin loop: sigue denied con un solo intento");
        k.nextResult = MusicControlsNotificationKiller.FGS_IGNORED;
        command("session:play", true);
        check(k.starts == 2 && fgs("ignored"), "oportunidad nueva => un intento más (ignored) " + state());
        c.onVisible();
        check(k.starts == 3 && fgs("ignored"), "visible = oportunidad nueva => un intento (sigue ignored)");
        k.nextResult = MusicControlsNotificationKiller.FGS_OK;
        publish(true, "play", false); idle();
        check(k.starts == 3, "misma oportunidad => no reintenta aunque ahora pasaría");
        c.onHidden(); c.onVisible();
        check(fgs("on") && k.starts == 4, "volver a verse => ON " + state());

        System.out.println("== 7. reload del WebView: GRACE + pausa publicada; create viejo tardío descartado; REMOVE");
        fresh(34, true); publish(true, "create", true); idle(); c.onHidden();
        publish(true, "play", false);          // un true viejo ya encolado
        boolean[] r = reset();
        check(fgs("grace") && r[0] && !r[1], "reset => GRACE (el true viejo no lo devuelve a ON) y publica la pausa " + state());
        publish(false, "pause", false); idle(); // la pausa que publica el plugin, con la generación nueva
        check(fgs("grace"), "la pausa del reset no cambia el estado");
        publishGen(true, "create", true, gen - 1); idle(); // el create de la página vieja termina tarde
        check(fgs("grace") && k.starts == 1, "create tardío de la página vieja => descartado, sigue GRACE " + state());
        publishGen(true, "play", false, gen - 1); idle();
        check(fgs("grace"), "updateIsPlaying tardío de la página vieja => descartado");
        advance(600000);
        check(fgs("off") && k.stopsRemove == 1 && state().contains("remove"), "sin create nuevo => REMOVE al vencer " + state());
        publish(true, "create", true); idle(); // la página nueva, visible de nuevo
        c.onVisible();
        check(fgs("on") && k.starts == 2, "create de la página nueva => ON");
        c.onHidden();
        reset();
        publish(true, "create", true); idle();
        check(fgs("on") && k.starts == 2, "reset y create de la página nueva => ON sin startForeground");
        publish(false, "pause", false); idle(); advance(600000);
        r = reset();
        check(r[1] && !r[0], "reset sin FGS => se borra la notificación como antes");

        System.out.println("== 8. destroy: REMOVE y después cancel; una notificación más nueva no se borra");
        fresh(34, true); publish(true, "create", true); idle();
        AtomicInteger notified = new AtomicInteger(3);
        MusicControlsNotificationKiller.trace.setLength(0);
        Runnable cancel = () -> MusicControlsNotificationKiller.trace.append("cancel ");
        Looper.onMain = false; c.destroyForeground("destroy", notified, 3, cancel); Looper.onMain = true;
        check(MusicControlsNotificationKiller.trace.length() == 0, "desde el puente no corre nada en el acto");
        idle();
        check(fgs("off") && MusicControlsNotificationKiller.trace.toString().equals("remove cancel "),
                "destroy => REMOVE y después cancel, en el main looper: " + MusicControlsNotificationKiller.trace);
        publish(true, "create", true); idle();
        check(fgs("on") && k.starts == 2, "create después del destroy => ON");
        MusicControlsNotificationKiller.trace.setLength(0);
        Looper.onMain = false;
        c.destroyForeground("destroy", notified, notified.get(), cancel);
        notified.incrementAndGet(); // un create notifica antes de que el main corra el destroy
        c.publish(n, false, "create", true, gen);
        Looper.onMain = true; idle();
        check(MusicControlsNotificationKiller.trace.toString().equals("detach ") && fgs("off"),
                "notificación más nueva => DETACH, sin REMOVE ni cancel: " + MusicControlsNotificationKiller.trace);

        System.out.println("== 9. graceMs");
        fresh(34, true); publish(true, "create", true); idle();
        CallbackContext p = new CallbackContext(); policy(0, -1, p);
        check(p.last.contains("\"graceMs\":0") && p.last.contains("\"legacy\":false"), "setForegroundPolicy(0) " + p.last);
        publish(false, "pause", false); idle();
        check(fgs("off") && k.stopsDetach == 1, "graceMs 0 => baja al instante");
        policy(600000, -1, null);
        publish(true, "play", false); idle(); publish(false, "pause", false); idle(); advance(10000);
        policy(5000, -1, null);
        check(fgs("off") && k.stopsDetach == 2, "achicar la gracia en GRACE => baja si ya venció");
        policy(99999999L, -1, p);
        check(p.last.contains("\"graceMs\":3600000"), "tope de 1 h");

        System.out.println("== 10. legacy (kill-switch por OTA): la lógica del plugin viejo");
        fresh(34, true); publish(true, "create", true); idle(); c.onHidden(); advance(5000);
        policy(-1, 1, p);
        check(p.last.contains("\"legacy\":true") && p.last.contains("\"graceMs\":600000"), "legacy sin tocar graceMs " + p.last);
        publish(false, "pause", false); idle();
        check(fgs("off") && k.stopsDetach == 1, "legacy: pausa => baja al instante");
        k.nextResult = MusicControlsNotificationKiller.FGS_DENIED;
        publish(true, "play", false); idle();
        check(k.starts == 2 && fgs("denied") && state().contains("play/legacy"), "legacy: atrás intenta sin exención " + state());
        for (int i = 0; i < 5; i++) { publish(true, "play", false); advance(1000); }
        check(k.starts == 2, "legacy: un intento por borde false→true (el latch viejo), no uno por segundo");
        publish(false, "pause", false); publish(true, "play", false); idle();
        check(k.starts == 3, "legacy: borde nuevo => otro intento");
        k.nextResult = MusicControlsNotificationKiller.FGS_OK;
        publish(false, "pause", false); idle();
        command("notif:play", true);
        check(fgs("off") && k.starts == 3, "legacy: sin promoción optimista desde OFF");
        publish(true, "play", false); idle();
        check(fgs("on") && k.starts == 4, "legacy: borde false→true => ON");
        policy(-1, 0, null);
        publish(false, "pause", false); idle();
        check(fgs("grace"), "legacy apagado => vuelve la gracia");
        policy(-1, 1, null);
        check(fgs("off") && k.stopsDetach == 2, "legacy encendido en GRACE => baja ya");

        System.out.println("== 11. ignored con las señales en desacuerdo: sin stop defensivo, se suelta al pasar a OFF");
        fresh(34, true);
        k.nextResult = MusicControlsNotificationKiller.FGS_IGNORED; k.nextUncertain = true;
        publish(true, "create", true); idle();
        check(fgs("ignored") && k.stopsDetach == 0, "ignored incierto => sin DETACH " + state());
        publish(false, "pause", false); idle();
        check(fgs("off") && k.stopsDetach == 1, "pausa => OFF y suelta el FGS que pudo quedar");

        System.out.println("== 12. SDK 30: sin restricción, promueve atrás");
        fresh(30, false); c.onHidden(); advance(60000);
        publish(true, "create", true); idle();
        check(fgs("on") && state().contains("sdk30"), "SDK 30 => ON " + state());

        System.out.println("== 13. exenciones: recién oculta (3 s), proceso TOP, batería sin optimizar");
        fresh(34, true); c.onHidden(); advance(2500);
        publish(true, "create", true); idle();
        check(fgs("on") && state().contains("recent-visible"), "2,5 s después de onStop => ON");
        fresh(34, true); c.onHidden(); advance(3500);
        publish(true, "create", true); idle();
        check(fgs("deferred") && state().contains("no-exemption"), "3,5 s después de onStop => DEFERRED");
        fresh(34, false); c.onHidden(); advance(5000); ActivityManager.importance = 100;
        publish(true, "create", true); idle();
        check(fgs("on") && state().contains("top"), "proceso TOP => ON");
        fresh(34, false); c.onHidden(); advance(5000); PowerManager.ignoring = true;
        publish(true, "create", true); idle();
        check(fgs("on") && state().contains("battery-opt"), "batería sin optimizar (elegida por el usuario) => ON");

        System.out.println("== 14. bind: tardío (C7), rechazado sin spam, rebind tras perder el servicio, shutdown");
        Handler.tasks.clear(); Handler.times.clear(); Build.VERSION.SDK_INT = 34; ActivityManager.importance = 100; PowerManager.ignoring = false;
        Activity.binds = 0; Activity.unbinds = 0; Activity.bindResult = true; gen = 0;
        c = new MusicControlsServiceConnection(new Activity(), 7824); ActivityManager.importance = 300;
        c.ensureBound(); idle();
        publish(true, "create", true); idle();
        check(fgs("unbound"), "antes del bind => unbound");
        k = new MusicControlsNotificationKiller();
        c.onServiceConnected(null, new KillBinder(k));
        check(fgs("on") && k.starts == 1, "al ligar aplica lo pendiente => ON");
        c.onBindingDied(null);
        check(fgs("unbound") && Activity.unbinds == 1, "binding muerto => unbound y se suelta");
        int binds = Activity.binds;
        publish(true, "play", false); idle();
        check(Activity.binds == binds + 1, "el próximo publish vuelve a ligar");
        k = new MusicControlsNotificationKiller();
        c.onServiceConnected(null, new KillBinder(k));
        check(fgs("on") && k.starts == 1, "rebind => ON con el servicio nuevo");
        int bindsBefore = Activity.binds;
        c.shutdown(); idle();
        check(k.stopsRemove == 1 && Activity.unbinds == 2, "shutdown => REMOVE y unbind");
        publish(true, "create", true); idle();
        check(Activity.binds == bindsBefore && k.starts == 1, "después del shutdown no liga ni promueve");
        Handler.tasks.clear(); Handler.times.clear(); ActivityManager.importance = 300;
        Activity.binds = 0; Activity.bindResult = false; gen = 0;
        c = new MusicControlsServiceConnection(new Activity(), 7824);
        c.ensureBound(); idle();
        for (int i = 0; i < 5; i++) { publish(true, "create", true); advance(1000); }
        check(Activity.binds == 1 && fgs("unbound"), "bindService rechazado => no se reintenta en cada publish");
        Activity.bindResult = true;
        c.onVisible();
        check(Activity.binds == 2, "oportunidad nueva (visible) => reintenta el bind");

        System.out.println("\n" + (checks - fails) + "/" + checks + " ok");
        if (fails > 0) System.exit(1);
    }
}
