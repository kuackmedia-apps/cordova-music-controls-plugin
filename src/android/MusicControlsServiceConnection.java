package com.homerours.musiccontrols;

import android.app.Activity;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

public class MusicControlsServiceConnection implements ServiceConnection {
    protected MusicControlsNotificationKiller service;
    protected Activity activity;
    protected boolean isForeground = false;

    MusicControlsServiceConnection(Activity activity) {
        this.activity = activity;
    }

    public void onServiceConnected(ComponentName className, IBinder binder) {
        Log.v("MusicControlsServiceConnection", "Connected to service 1");
        service = ((KillBinder) binder).service;
        Log.v("MusicControlsServiceConnection", "Connected to service 2");
        //service.startService(new Intent(activity, MusicControlsNotificationKiller.class));
        Log.v("MusicControlsServiceConnection 2", "Connected to service");
    }

    public void onServiceDisconnected(ComponentName className) {
    }

    void setNotification(Notification notification, boolean isPlaying) {
        if (this.service == null) {
            return;
        }
        Log.v("MusicControlsServiceConnection setNotification", isPlaying ? "true" : "false");

        if (isPlaying && !this.isForeground) {
            this.service.setForeground(notification);
            this.isForeground = true;
        } else {
            if (!isPlaying && this.isForeground) {
                this.service.clearForeground();
                this.isForeground = false;
            }
        }
    }
}
