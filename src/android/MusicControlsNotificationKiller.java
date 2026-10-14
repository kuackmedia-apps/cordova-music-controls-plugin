package com.homerours.musiccontrols;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.app.NotificationManager;
import android.content.Intent;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import android.content.pm.ServiceInfo;

import java.util.List;

public class MusicControlsNotificationKiller extends Service {

    // kuackmedia (fgs-grace, N2): resultado verificado de setForeground.
    static final int FGS_OK = 0;
    // startForeground tiró una excepción (ForegroundServiceStartNotAllowedException en 12+ u otra).
    static final int FGS_DENIED = 1;
    // Sin excepción, pero el sistema no dejó el servicio en primer plano (batería "Restringida", app-op).
    static final int FGS_IGNORED = 2;

    private String lastError = "";
    // startForeground no tiró y no se lo bajó después: el sistema pudo haberlo dejado en primer plano aunque la
    // verificación diga que no (un OEM que reporta mal una señal). MusicControlsServiceConnection lo suelta al pasar
    // a OFF. Sólo el main looper.
    private boolean mayBeForeground = false;

    private static int NOTIFICATION_ID;
    private NotificationManager mNM;
    private final IBinder mBinder = new KillBinder(this);
    private static final String CHANNEL_ID = "music_controls_channel";

    @Override
    public IBinder onBind(Intent intent) {
        this.NOTIFICATION_ID = intent.getIntExtra("notificationID", 1);
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.removeNotification();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.removeNotification();
    }

    /**
     * kuackmedia (fgs-grace, N2): promueve el servicio y devuelve lo que pasó de verdad. Antes el catch vacío se
     * tragaba el rechazo de Android 12+ y quien llamaba daba el servicio por promovido. Sólo lo llama
     * MusicControlsServiceConnection, desde el main looper.
     */
    int setForeground(Notification notification) {
        this.lastError = "";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                this.startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Por nombre: la clase existe recién en la API 31 y así no se la referencia en equipos viejos.
            boolean notAllowed = Build.VERSION.SDK_INT >= 31
                    && "android.app.ForegroundServiceStartNotAllowedException".equals(e.getClass().getName());
            this.lastError = (notAllowed ? "not-allowed" : e.getClass().getSimpleName()) + ": " + e.getMessage();
            Log.w(MusicControlsServiceConnection.TAG, "startForeground rechazado: " + this.lastError);
            return FGS_DENIED;
        }
        this.mayBeForeground = true;
        Boolean listed = this.listedForeground();
        Integer type = this.foregroundType();
        // Manda la lista de servicios; sin ella, el tipo; sin ninguna, alcanza con que startForeground no haya tirado.
        boolean notForeground = listed != null ? !listed : type != null && type == 0;
        if (notForeground) {
            this.lastError = "running-service foreground=" + listed + " type=" + type;
            Log.w(MusicControlsServiceConnection.TAG, "startForeground ignorado: " + this.lastError);
            if (Boolean.FALSE.equals(listed) && type != null && type == 0) {
                // Las dos señales coinciden: el sistema no lo dejó arriba. El stop deja lo reportado igual a lo que
                // hay. Con una sola (o en desacuerdo) no se baja: con un OEM que reporte mal (MIUI/HyperOS, sin
                // verificar), el stop perdería un FGS real.
                this.clearForeground(false);
            }
            return FGS_IGNORED;
        }
        return FGS_OK;
    }

    String getLastError() {
        return this.lastError;
    }

    /** kuackmedia (fgs-grace, N2): ¿puede estar en primer plano? (ver mayBeForeground). */
    boolean mayBeForeground() {
        return this.mayBeForeground;
    }

    /**
     * kuackmedia (fgs-grace, N2): el `foreground` de getRunningServices(), que todavía devuelve los servicios propios
     * y es el isForeground del ServiceRecord en todas las versiones. null si el servicio no aparece.
     */
    private Boolean listedForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services = am != null ? am.getRunningServices(Integer.MAX_VALUE) : null;
            if (services != null) {
                String name = MusicControlsNotificationKiller.class.getName();
                int pid = Process.myPid();
                for (ActivityManager.RunningServiceInfo info : services) {
                    if (info.pid == pid && info.service != null && name.equals(info.service.getClassName())) {
                        return info.foreground;
                    }
                }
            }
        } catch (Exception e) {
            // Sin la lista se decide con el tipo.
        }
        return null;
    }

    /**
     * kuackmedia (fgs-grace, N2): getForegroundServiceType() en la API 29+ (en Android 14 el stop lo pone en 0 y sólo
     * el éxito lo vuelve a setear; en 12/13 no está verificado, por eso va segundo). null si no hay.
     */
    private Integer foregroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            return this.getForegroundServiceType();
        } catch (Exception e) {
            return null;
        }
    }

    /** kuackmedia (fgs-grace, N2): `remove` saca también la notificación (destroy, reset sin create nuevo). */
    void clearForeground(boolean remove) {
        this.mayBeForeground = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                this.stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            } else {
                this.stopForeground(remove);
            }
        } catch (Exception e) {
            Log.w(MusicControlsServiceConnection.TAG, "stopForeground falló: " + e);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        this.removeNotification();
        this.stopSelf();
    }

    private void removeNotification() {
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mNM != null) {
            mNM.cancel(NOTIFICATION_ID);
        }
    }
}
