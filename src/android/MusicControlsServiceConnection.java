package com.homerours.musiccontrols;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * kuackmedia (fgs-grace): dueño del servicio en primer plano (FGS) del app, desacoplado del botón de play/pausa.
 *
 * Máquina de estados (todo su estado se toca sólo en el main looper):
 * - OFF: sin FGS y sin intención de reproducir.
 * - ON: en FGS y reproduciendo.
 * - GRACE: en FGS, en pausa (o promovido por un comando y sin confirmar). La baja espera graceMs.
 * - DEFERRED: el JS quiere reproducir pero no hay FGS: no hubo exención para promover, o el intento falló.
 *
 * Reglas: una pausa no baja el FGS al instante (gracia de 10 min por default, como Media3); el FGS se promueve
 * sólo con exención (SDK < 31, Activity visible o recién oculta, ventana de un comando del usuario, proceso TOP o
 * batería sin optimizar elegida por el usuario), nunca desde atrás sin ella; lo reportado es lo verificado.
 * `legacy` (setForegroundPolicy) es el kill-switch por OTA: vuelve a la lógica del plugin viejo.
 * Detalle, contrato con el JS y pruebas: KUACKMEDIA.md.
 */
public class MusicControlsServiceConnection implements ServiceConnection {
    static final String TAG = "KuackFGS";
    static final long DEFAULT_GRACE_MS = 600000L;
    static final long MAX_GRACE_MS = 3600000L;
    // Ventana de un comando: AOSP da 10 s (sesión, teclas) y 30 s (PendingIntent de la notificación); 9 s por los OEM.
    static final long COMMAND_WINDOW_MS = 9000L;
    // AOSP deja promover hasta 5 s después de que la Activity dejó de verse, contados desde antes del onStop (que en
    // un equipo lento llega tarde): 3 s por margen.
    static final long VISIBLE_GRACE_MS = 3000L;
    private static final long BATTERY_OPT_TTL_MS = 60000L;
    private static final String NO_EXEMPTION = "no-exemption";

    enum State {
        OFF,
        ON,
        GRACE,
        DEFERRED
    }

    protected MusicControlsNotificationKiller service;
    protected Activity activity;
    private final int notificationId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Main looper.
    private State state = State.OFF;
    private boolean wantPlaying = false;
    private Notification notification;
    private boolean bound = false;
    // onDestroy real del plugin: lo que llegue después (un create del thread pool) se ignora y no se vuelve a ligar.
    private boolean closed = false;
    // Hubo un reload del WebView y la página nueva todavía no hizo create ni reprodujo: al vencer la gracia, REMOVE.
    private boolean resetPending = false;
    // Generación de la página del WebView (N4): lo que publica una página anterior al último reload se descarta.
    private int pageGen = 0;
    private boolean visible;
    private long stoppedAt = -1L;
    private long graceMs = DEFAULT_GRACE_MS;
    private long graceStartedAt = 0L;
    // Kill-switch por OTA (setForegroundPolicy {legacy: true}): la lógica del plugin viejo.
    private boolean legacy = false;
    // Anti-loop: tras un intento fallido no se reintenta hasta una oportunidad nueva (comando, visible, bind).
    private int opportunity = 0;
    private int failedOpportunity = -1;
    // Sin exención no se vuelve a consultar (binder de AMS) hasta una oportunidad nueva; el JS publica cada segundo.
    private int noExemptionOpportunity = -1;
    // Un bindService rechazado no se reintenta en cada publish, sólo ante una oportunidad nueva.
    private int bindFailedOpportunity = -1;
    private String failure = null;
    private String why = "init";
    private String err = "";
    private String lastLogged = "";
    private boolean batteryOpt = false;
    private long batteryOptCheckedAt = -1L;

    // Los escriben los callbacks de comandos (main looper; volátiles por si un OEM los entrega en otro hilo).
    private volatile long exemptUntil = 0L;
    private volatile String exemptWhy = "window";

    private final Runnable demoteRunnable = new Runnable() {
        public void run() {
            demote("grace-expired");
        }
    };

    MusicControlsServiceConnection(Activity activity, int notificationId) {
        this.activity = activity;
        this.notificationId = notificationId;
        // El plugin se instancia en el primer exec: puede no ver el onStart de la Activity.
        this.visible = processIsTop();
    }

    // ---- ServiceConnection (main looper) ----

    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
        this.service = ((KillBinder) binder).service;
        this.opportunity++;
        this.log("bound");
        // C7: lo que se publicó antes de que terminara el bind se aplica ahora.
        if (this.wantPlaying && (this.state == State.OFF || this.state == State.DEFERRED)) {
            this.tryPromote("bound");
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        this.service = null;
        this.cancelGrace();
        this.setState(this.wantPlaying ? State.DEFERRED : State.OFF, "disconnected");
    }

    @Override
    public void onBindingDied(ComponentName className) {
        // El binding no se recupera solo: se suelta y el próximo publish vuelve a ligar.
        this.onServiceDisconnected(className);
        this.unbind();
    }

    // ---- API del plugin (cualquier hilo salvo donde se aclara) ----

    /** N4: el bind que antes estaba en initialize; se repite si el servicio se perdió. */
    void ensureBound() {
        this.runOnMain(new Runnable() {
            public void run() {
                bindIfNeeded();
            }
        });
    }

    /**
     * El JS publicó un estado (create, updateIsPlaying, updateDismissable). `n` es la notificación recién publicada
     * con notify (null si no hubo). `gen` es la generación de la página que lo pidió (N4). Se llama con los locks de
     * la notificación tomados: sólo encola.
     */
    void publish(
            final Notification n, final boolean playing, final String trigger, final boolean fromCreate, final int gen) {
        this.runOnMain(new Runnable() {
            public void run() {
                if (closed) {
                    return;
                }
                if (gen < pageGen) {
                    // N4: un create de la página anterior que terminó después del reload (bajando la carátula) no
                    // vuelve a ON ni limpia el reset: nadie publicaría la pausa y el FGS quedaría sin dueño.
                    log("discard " + trigger + " gen=" + gen + "<" + pageGen);
                    return;
                }
                if (n != null) {
                    notification = n;
                }
                if (fromCreate || playing) {
                    // La página nueva usa los controles (create o reproduce): ya no es un reload sin dueño.
                    resetPending = false;
                }
                apply(playing, trigger);
            }
        });
    }

    /** N5: responde con el estado desde el main looper, después de lo que se encoló antes. */
    void answer(final CallbackContext callbackContext) {
        this.runOnMain(new Runnable() {
            public void run() {
                callbackContext.success(stateJson());
            }
        });
    }

    /**
     * N3: un comando del usuario (sesión, botón de medios, notificación) abre la ventana con exención. Se llama
     * antes de reenviar el comando al JS, aunque el callback del JS sea null. `promoteIfOff`: también promueve
     * si está en OFF con notificación (play, siguiente, anterior); el FGS queda en GRACE hasta que el JS confirme.
     * `eventUptime`: la hora del KeyEvent de una tecla (uptime; 0 si no hay): la ventana de AOSP corre desde ahí, no
     * desde que el callback llegó.
     */
    void onUserCommand(final String trigger, final boolean promoteIfOff, final long eventUptime) {
        long now = SystemClock.uptimeMillis();
        long start = eventUptime > 0 && eventUptime < now ? eventUptime : now;
        if (start + COMMAND_WINDOW_MS > this.exemptUntil) {
            this.exemptWhy = trigger;
            this.exemptUntil = start + COMMAND_WINDOW_MS;
        }
        this.runOnMain(new Runnable() {
            public void run() {
                if (closed) {
                    return;
                }
                opportunity++;
                log("window " + trigger);
                boolean optimistic =
                        promoteIfOff && state == State.OFF && notification != null && graceMs > 0 && !legacy;
                if (state == State.DEFERRED || optimistic) {
                    tryPromote(trigger);
                }
            }
        });
    }

    /** Main looper (onStart/onResume del plugin). */
    void onVisible() {
        boolean was = this.visible;
        this.visible = true;
        if (!was) {
            this.opportunity++;
            this.log("visible");
        }
        if (this.state == State.DEFERRED) {
            this.tryPromote("visible");
        }
    }

    /** Main looper (onStop del plugin). */
    void onHidden() {
        this.visible = false;
        this.stoppedAt = SystemClock.uptimeMillis();
        this.log("hidden");
    }

    /**
     * N4: el reload del WebView (onReset) se trata como una pausa: bind, sesión y notificación quedan y el FGS
     * espera la gracia (al vencer, REMOVE si no llegó un create nuevo). `gen` es la generación de la página nueva:
     * desde acá se descarta lo que publique la anterior. Corre `ifHeld` si queda un FGS que conservar (el plugin
     * publica la pausa: ▶ y sesión PAUSED) y `ifNotHeld` si no (borra la notificación, como antes del arreglo).
     */
    void onWebViewReset(final int gen, final Runnable ifHeld, final Runnable ifNotHeld) {
        // Siempre encolado (aun desde el main looper): va después de los publish de la página vieja que ya estaban en
        // la cola. Inline, un true viejo aplicado después volvía a ON sin nadie que después publique la pausa.
        this.mainHandler.post(new Runnable() {
            public void run() {
                if (closed) {
                    return;
                }
                if (gen > pageGen) {
                    pageGen = gen;
                }
                resetPending = true;
                wantPlaying = false;
                if (state == State.ON) {
                    enterGrace("reset");
                } else if (state == State.DEFERRED) {
                    setState(State.OFF, "reset");
                    releaseUncertain();
                }
                Runnable next = state == State.ON || state == State.GRACE ? ifHeld : ifNotHeld;
                if (next != null) {
                    next.run();
                }
            }
        });
    }

    /**
     * N6: destroy (del JS o de la notificación). En el main looper: primero el FGS abajo con REMOVE y después
     * `cancel` (el cancel de la notificación). `notified`/`seen`: el contador de notify de la notificación y su valor
     * cuando se pidió el destroy; si cambió, entre el destroy y este runnable se publicó una notificación más nueva
     * (destroy seguido de un create): el FGS baja con DETACH y no se cancela, para no borrarla.
     */
    void destroyForeground(final String reason, final AtomicInteger notified, final int seen, final Runnable cancel) {
        this.runOnMain(new Runnable() {
            public void run() {
                boolean newer = notified != null && notified.get() != seen;
                destroyNow(newer ? reason + "/newer" : reason, newer);
                if (cancel != null && !newer) {
                    cancel.run();
                }
            }
        });
    }

    /** N4: el onDestroy real del plugin: gracia cancelada, FGS abajo con REMOVE y recién después el unbind. */
    void shutdown() {
        this.runOnMain(new Runnable() {
            public void run() {
                destroyNow("plugin-destroy", false);
                unbind();
                service = null;
                closed = true;
            }
        });
    }

    /**
     * Acción setForegroundPolicy. `requestedGraceMs` < 0 = sin cambio; si no, entre 0 (bajar al instante en una
     * pausa) y MAX_GRACE_MS. `requestedLegacy` < 0 = sin cambio, 1 = lógica del plugin viejo, 0 = la nueva.
     */
    void setPolicy(final long requestedGraceMs, final int requestedLegacy, final CallbackContext callbackContext) {
        this.runOnMain(new Runnable() {
            public void run() {
                if (requestedGraceMs >= 0) {
                    graceMs = Math.min(requestedGraceMs, MAX_GRACE_MS);
                }
                if (requestedLegacy >= 0) {
                    legacy = requestedLegacy == 1;
                }
                log("policy graceMs=" + graceMs + " legacy=" + legacy);
                if (state == State.GRACE) {
                    cancelGrace();
                    long remaining = legacy ? 0L : graceStartedAt + graceMs - SystemClock.uptimeMillis();
                    if (remaining <= 0) {
                        demote("policy");
                    } else {
                        mainHandler.postDelayed(demoteRunnable, remaining);
                    }
                }
                if (callbackContext != null) {
                    callbackContext.success(stateJson());
                }
            }
        });
    }

    // ---- Máquina de estados (main looper) ----

    private void apply(boolean playing, String trigger) {
        this.wantPlaying = playing;
        switch (this.state) {
            case ON:
                if (!playing) {
                    this.enterGrace(trigger);
                }
                break;
            case GRACE:
                if (playing) {
                    // Retoma dentro de la gracia: el FGS nunca cayó y no hace falta startForeground.
                    this.cancelGrace();
                    this.setState(State.ON, trigger);
                }
                break;
            case DEFERRED:
                if (playing) {
                    if (this.legacy && this.failure != null) {
                        // Legacy: como el latch del plugin viejo, un intento por cada borde false→true, no uno por
                        // segundo.
                        break;
                    }
                    this.tryPromote(trigger);
                } else {
                    this.setState(State.OFF, trigger);
                    this.releaseUncertain();
                }
                break;
            default:
                if (playing) {
                    this.tryPromote(trigger);
                }
                break;
        }
    }

    private void tryPromote(String trigger) {
        if (this.service == null) {
            this.bindIfNeeded();
            this.defer(trigger, "unbound");
            return;
        }
        if (this.notification == null) {
            this.defer(trigger, "no-notification");
            return;
        }
        String exemption;
        if (this.legacy) {
            // Kill-switch: como el plugin viejo, se intenta sin consultar la exención ni el anti-loop.
            exemption = "legacy";
        } else {
            if (this.failedOpportunity == this.opportunity) {
                // Ya falló con esta oportunidad: no se reintenta (el JS publica cada segundo) hasta una nueva.
                this.defer(trigger, null);
                return;
            }
            if (this.state == State.DEFERRED
                    && NO_EXEMPTION.equals(this.why)
                    && this.noExemptionOpportunity == this.opportunity) {
                // Sin oportunidad nueva la exención no cambió: no se vuelve a consultar.
                return;
            }
            exemption = this.exemption();
            if (exemption == null) {
                // N3: sin exención no se llama a startForeground; se promueve en la próxima ventana.
                this.noExemptionOpportunity = this.opportunity;
                this.defer(trigger, NO_EXEMPTION);
                return;
            }
        }
        String reason = trigger + "/" + exemption;
        int result = this.service.setForeground(this.notification);
        if (result == MusicControlsNotificationKiller.FGS_OK) {
            this.failure = null;
            this.err = "";
            this.failedOpportunity = -1;
            if (this.wantPlaying) {
                this.setState(State.ON, reason);
            } else {
                // Promoción por un comando sin confirmar: si el JS no publica true, cae sola al vencer la gracia.
                this.enterGrace(reason);
            }
            return;
        }
        this.failure = result == MusicControlsNotificationKiller.FGS_DENIED ? "denied" : "ignored";
        this.err = this.service.getLastError();
        this.failedOpportunity = this.opportunity;
        this.setState(this.wantPlaying ? State.DEFERRED : State.OFF, reason);
        if (!this.wantPlaying) {
            this.releaseUncertain();
        }
        Log.w(TAG, this.failure + " why=" + reason + " err=" + this.err);
    }

    /** `reason` null = conservar el motivo y el fallo anteriores (reintento bloqueado por el anti-loop). */
    private void defer(String trigger, String reason) {
        State prev = this.state;
        String prevWhy = this.why;
        if (reason != null) {
            this.failure = null;
            this.err = "";
            this.why = reason;
        }
        this.state = this.wantPlaying ? State.DEFERRED : State.OFF;
        if (prev != this.state || !prevWhy.equals(this.why)) {
            String transition = prev + "->" + this.state + " why=" + this.why;
            this.log(transition, transition + " trigger=" + trigger);
        }
    }

    private void enterGrace(String reason) {
        if (this.graceMs <= 0 || this.legacy) {
            this.demote(reason);
            return;
        }
        this.cancelGrace();
        this.graceStartedAt = SystemClock.uptimeMillis();
        this.mainHandler.postDelayed(this.demoteRunnable, this.graceMs);
        this.setState(State.GRACE, reason);
    }

    private void demote(String reason) {
        this.cancelGrace();
        boolean remove = this.resetPending;
        if (this.service != null) {
            this.service.clearForeground(remove);
        }
        if (remove) {
            // Reload sin create nuevo (p. ej. logout): la notificación no queda colgada.
            this.notification = null;
            this.resetPending = false;
        }
        this.failure = null;
        this.err = "";
        this.setState(this.wantPlaying ? State.DEFERRED : State.OFF, remove ? reason + "/remove" : reason);
    }

    /**
     * `keepNotification`: hay una notificación más nueva que el destroy (N6): el FGS baja con DETACH y la
     * notificación que guarda la máquina no se toca (la reemplaza el publish de la nueva).
     */
    private void destroyNow(String reason, boolean keepNotification) {
        this.cancelGrace();
        boolean held = this.state == State.ON || this.state == State.GRACE;
        if (this.service != null && (held || this.service.mayBeForeground())) {
            this.service.clearForeground(!keepNotification);
        }
        this.wantPlaying = false;
        if (!keepNotification) {
            this.notification = null;
        }
        this.resetPending = false;
        this.failure = null;
        this.err = "";
        this.setState(State.OFF, reason);
    }

    /**
     * N2: un `ignored` con las dos señales en desacuerdo deja el servicio sin el stop defensivo (puede estar arriba
     * aunque la verificación diga que no). Al pasar a OFF se suelta, para no dejar un FGS sin dueño.
     */
    private void releaseUncertain() {
        if (this.service != null && this.service.mayBeForeground()) {
            this.service.clearForeground(false);
            this.log("release uncertain");
        }
    }

    private void cancelGrace() {
        this.mainHandler.removeCallbacks(this.demoteRunnable);
    }

    private void setState(State next, String reason) {
        State prev = this.state;
        this.state = next;
        this.why = reason;
        this.log(prev + "->" + next + " why=" + reason);
    }

    // ---- Exención (regla 2.4 del diseño) ----

    private String exemption() {
        if (Build.VERSION.SDK_INT < 31) {
            return "sdk" + Build.VERSION.SDK_INT;
        }
        long now = SystemClock.uptimeMillis();
        if (this.visible) {
            return "visible";
        }
        if (this.stoppedAt >= 0 && now - this.stoppedAt <= VISIBLE_GRACE_MS) {
            return "recent-visible";
        }
        if (now < this.exemptUntil) {
            return "window:" + this.exemptWhy;
        }
        if (processIsTop()) {
            return "top";
        }
        if (this.ignoringBatteryOptimizations(now)) {
            return "battery-opt";
        }
        return null;
    }

    /** Proceso en TOP (o BOUND_TOP): por ejemplo, otra Activity del app al frente con la de Cordova detenida. */
    private static boolean processIsTop() {
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sólo se consulta: la exención la elige el usuario, el plugin no la pide nunca. */
    private boolean ignoringBatteryOptimizations(long now) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        if (this.batteryOptCheckedAt >= 0 && now - this.batteryOptCheckedAt < BATTERY_OPT_TTL_MS) {
            return this.batteryOpt;
        }
        this.batteryOptCheckedAt = now;
        try {
            PowerManager pm = (PowerManager) this.activity.getSystemService(Context.POWER_SERVICE);
            this.batteryOpt = pm != null && pm.isIgnoringBatteryOptimizations(this.activity.getPackageName());
        } catch (Exception e) {
            this.batteryOpt = false;
        }
        return this.batteryOpt;
    }

    // ---- Bind ----

    private void bindIfNeeded() {
        if (this.bound || this.closed || this.bindFailedOpportunity == this.opportunity) {
            return;
        }
        Intent intent = new Intent(this.activity, MusicControlsNotificationKiller.class);
        intent.putExtra("notificationID", this.notificationId);
        try {
            this.bound = this.activity.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            this.bound = false;
            Log.w(TAG, "bindService falló: " + e);
        }
        if (!this.bound) {
            this.bindFailedOpportunity = this.opportunity;
            // La doc pide unbindService aun cuando bindService devuelve false.
            try {
                this.activity.unbindService(this);
            } catch (Exception e) {
                // No había nada ligado.
            }
            Log.w(TAG, "sin bind al servicio");
        }
    }

    private void unbind() {
        if (!this.bound) {
            return;
        }
        this.bound = false;
        try {
            this.activity.unbindService(this);
        } catch (Exception e) {
            Log.w(TAG, "unbindService falló: " + e);
        }
    }

    // ---- Reporte (N5) ----

    /** Main looper. El mismo JSON para el success de create/updateIsPlaying y para getForegroundState. */
    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("fgs", this.reported());
            o.put("why", this.why);
            o.put("err", this.err);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("graceMs", this.graceMs);
            o.put("legacy", this.legacy);
        } catch (JSONException e) {
            // Claves y valores fijos: no pasa.
        }
        return o.toString();
    }

    private String reported() {
        if (this.service == null) {
            return "unbound";
        }
        switch (this.state) {
            case ON:
                return "on";
            case GRACE:
                return "grace";
            case DEFERRED:
                return this.failure != null ? this.failure : "deferred";
            default:
                return "off";
        }
    }

    private void log(String message) {
        this.log(message, message);
    }

    private void log(String key, String message) {
        // El JS publica cada segundo: sólo se loguean los cambios.
        if (key.equals(this.lastLogged)) {
            return;
        }
        this.lastLogged = key;
        Log.i(TAG, message);
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            this.mainHandler.post(runnable);
        }
    }
}
