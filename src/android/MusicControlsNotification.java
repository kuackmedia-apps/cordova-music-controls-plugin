package com.homerours.musiccontrols;

import org.apache.cordova.CordovaInterface;


import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.UUID;

import android.media.Session2Token;
import android.media.session.MediaSession;
import android.provider.MediaStore;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.R;
import android.content.Context;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.net.Uri;
import android.app.Notification.MediaStyle;

import android.app.NotificationChannel;
import android.util.Log;

public class MusicControlsNotification {
	private Activity cordovaActivity;
	private NotificationManager notificationManager;
	private Notification.Builder notificationBuilder;
	private int notificationID;
	protected MusicControlsInfos infos;
	private Bitmap bitmapCover;
	private String CHANNEL_ID;
	private MediaStyle mediaStyle;
	private MediaSessionCompat mediaSessionCompat;

	private final Object notificationLock = new Object();
	private volatile boolean isUpdating = false; // Prevenir updates concurrentes
	// Public Constructor

	public MusicControlsNotification(Activity cordovaActivity, int id){
		//this.CHANNEL_ID = UUID.randomUUID().toString();
		this.CHANNEL_ID = "kuackmedia-music-controls";
		this.notificationID = id;
		this.cordovaActivity = cordovaActivity;
		Context context = cordovaActivity;
		this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		this.mediaStyle = new MediaStyle();
		//this.mediaStyle.setMediaSession(cordovaActivity.getMediaController().getSessionToken());

		Log.v("MediaControllerSession", "Contructor MusicControlsNotification");


		// use channelid for Oreo and higher
		if (Build.VERSION.SDK_INT >= 26) {
			// The user-visible name of the channel.
			CharSequence name = "Audio Controls";
			// The user-visible description of the channel.
			String description = "Control Playing Audio";

			int importance = NotificationManager.IMPORTANCE_LOW;

			NotificationChannel mChannel = new NotificationChannel(this.CHANNEL_ID, name,importance);

			// Configure the notification channel.
			mChannel.setDescription(description);

			// Don't show badges for this channel
			mChannel.setShowBadge(false);

			this.notificationManager.createNotificationChannel(mChannel);
    	}
	}

	public void setMediaSessionCompat(MediaSessionCompat mediaSessionCompat) {
		this.mediaStyle.setMediaSession((MediaSession.Token) mediaSessionCompat.getSessionToken().getToken());
		this.mediaSessionCompat = mediaSessionCompat;
	}
	public void updateNotification(MusicControlsInfos newInfos){
		synchronized(notificationLock) {
			// Prevenir updates múltiples simultáneos
			if (isUpdating) {
				return; // O hacer queue del update
			}

			try {
				isUpdating = true;

				// Check if the cover has changed
				if (!newInfos.cover.isEmpty() && (this.infos == null || !newInfos.cover.equals(this.infos.cover))){
					this.getBitmapCover(newInfos.cover);
				}
				this.infos = newInfos;
				this.createBuilder();
				Notification noti = this.notificationBuilder.build();

				// Usar try-catch para capturar errores de notificación
				try {
					this.notificationManager.notify(this.notificationID, noti);
					this.onNotificationUpdated(noti);
				} catch (Exception e) {
					Log.e("MusicControls", "Error updating notification", e);
				}

			} finally {
				isUpdating = false;
			}
		}
	}

	public void updateIsPlaying(boolean isPlaying){
		synchronized(notificationLock) {
			if (isUpdating || this.infos == null) return;

			try {
				isUpdating = true;
				this.infos.isPlaying = isPlaying;
				this.createBuilder();
				Notification noti = this.notificationBuilder.build();

				try {
					this.notificationManager.notify(this.notificationID, noti);
					this.onNotificationUpdated(noti);
				} catch (Exception e) {
					Log.e("MusicControls", "Error updating notification", e);
				}
			} finally {
				isUpdating = false;
			}
		}
	}

	public void updateDismissable(boolean dismissable){
		synchronized(notificationLock) {
			if (isUpdating || this.infos == null) return;

			try {
				isUpdating = true;
				this.infos.dismissable = dismissable;
				this.createBuilder();
				Notification noti = this.notificationBuilder.build();

				try {
					this.notificationManager.notify(this.notificationID, noti);
					this.onNotificationUpdated(noti);
				} catch (Exception e) {
					Log.e("MusicControls", "Error updating notification", e);
				}
			} finally {
				isUpdating = false;
			}
		}
	}

	// Get image from url
	private void getBitmapCover(String coverURL){
		try{
			if(coverURL.matches("^(https?|ftp)://.*$"))
				// Remote image
				this.bitmapCover = getBitmapFromURL(coverURL);
			else{
				// Local image
				this.bitmapCover = getBitmapFromLocal(coverURL);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	// get Local image
	private Bitmap getBitmapFromLocal(String localURL){
		try {
			Uri uri = Uri.parse(localURL);
			File file = new File(uri.getPath());
			FileInputStream fileStream = new FileInputStream(file);
			BufferedInputStream buf = new BufferedInputStream(fileStream);
			Bitmap myBitmap = BitmapFactory.decodeStream(buf);
			buf.close();
			return myBitmap;
		} catch (Exception ex) {
			try {
				InputStream fileStream = cordovaActivity.getAssets().open("www/" + localURL);
				BufferedInputStream buf = new BufferedInputStream(fileStream);
				Bitmap myBitmap = BitmapFactory.decodeStream(buf);
				buf.close();
				return myBitmap;
			} catch (Exception ex2) {
				ex.printStackTrace();
				ex2.printStackTrace();
				return null;
			}
		}
	}

	// get Remote image
	private Bitmap getBitmapFromURL(String strURL) {
		try {
			URL url = new URL(strURL);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setDoInput(true);
			connection.connect();
			InputStream input = connection.getInputStream();
			Bitmap myBitmap = BitmapFactory.decodeStream(input);
			return myBitmap;
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
		}
	}

	private void createBuilder(){
		Context context = cordovaActivity;
		Notification.Builder builder = new Notification.Builder(context);

		// use channelid for Oreo and higher
		if (Build.VERSION.SDK_INT >= 26) {
			builder.setChannelId(this.CHANNEL_ID);
		}

		//int intentFlags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0;
        int intentFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;  // Only add on platform levels that support FLAG_MUTABLE
		//Configure builder
		builder.setContentTitle(infos.track);
		if (!infos.artist.isEmpty()){
			builder.setContentText(infos.artist);
		}
		builder.setWhen(0);

		// set if the notification can be destroyed by swiping
		if (infos.dismissable){
			builder.setOngoing(false);
			Intent dismissIntent = new Intent("music-controls-destroy");
			dismissIntent.setPackage(context.getPackageName());
			PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(context, 1, dismissIntent, intentFlags);
			builder.setDeleteIntent(dismissPendingIntent);
		} else {
			builder.setOngoing(true);
		}
		if (!infos.ticker.isEmpty()){
			builder.setTicker(infos.ticker);
		}

		builder.setPriority(Notification.PRIORITY_MAX);

		//If 5.0 >= set the controls to be visible on lockscreen
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP){
			builder.setVisibility(Notification.VISIBILITY_PUBLIC);
		}

		//Set SmallIcon
		boolean usePlayingIcon = infos.notificationIcon.isEmpty();
		if(!usePlayingIcon){
			int resId = this.getResourceId(infos.notificationIcon, 0);
			usePlayingIcon = resId == 0;
			if(!usePlayingIcon) {
				builder.setSmallIcon(resId);
			}
		}

		if(usePlayingIcon){
			if (infos.isPlaying){
				builder.setSmallIcon(this.getResourceId(infos.playIcon, android.R.drawable.ic_media_play));
			} else {
				builder.setSmallIcon(this.getResourceId(infos.pauseIcon, android.R.drawable.ic_media_pause));
			}
		}

		//Set LargeIcon
		if (!infos.cover.isEmpty() && this.bitmapCover != null){
			builder.setLargeIcon(this.bitmapCover);
		}

		//Open app if tapped
		Intent resultIntent = new Intent(context, cordovaActivity.getClass());
		resultIntent.setAction(Intent.ACTION_MAIN);
		resultIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		PendingIntent resultPendingIntent = PendingIntent.getActivity(context, 0, resultIntent, intentFlags);
		builder.setContentIntent(resultPendingIntent);

		//Controls
		int nbControls=0;

		if (infos.hasPrev){
			/* Previous  */
			nbControls++;
			Intent previousIntent = new Intent("music-controls-previous");
			previousIntent.setPackage(context.getPackageName());
			PendingIntent previousPendingIntent = PendingIntent.getBroadcast(context, 1, previousIntent, intentFlags);
			builder.addAction(this.getResourceId(infos.prevIcon, android.R.drawable.ic_media_previous), "", previousPendingIntent);
		}
		if (infos.isPlaying){
			/* Pause  */
			nbControls++;
			Intent pauseIntent = new Intent("music-controls-pause");
			pauseIntent.setPackage(context.getPackageName());
			PendingIntent pausePendingIntent = PendingIntent.getBroadcast(context, 1, pauseIntent, intentFlags);
			builder.addAction(this.getResourceId(infos.pauseIcon, android.R.drawable.ic_media_pause), "", pausePendingIntent);
		} else {
			/* Play  */
			nbControls++;
			Intent playIntent = new Intent("music-controls-play");
			playIntent.setPackage(context.getPackageName());
			PendingIntent playPendingIntent = PendingIntent.getBroadcast(context, 1, playIntent, intentFlags);
			builder.addAction(this.getResourceId(infos.playIcon, android.R.drawable.ic_media_play), "", playPendingIntent);
		}

		if (infos.hasNext){
			/* Next */
			nbControls++;
			Intent nextIntent = new Intent("music-controls-next");
			nextIntent.setPackage(context.getPackageName());
			PendingIntent nextPendingIntent = PendingIntent.getBroadcast(context, 1, nextIntent, intentFlags);
			builder.addAction(this.getResourceId(infos.nextIcon, android.R.drawable.ic_media_next), "", nextPendingIntent);
		}
		if (infos.hasClose){
			/* Close */
			nbControls++;
			Intent destroyIntent = new Intent("music-controls-destroy");
			destroyIntent.setPackage(context.getPackageName());
			PendingIntent destroyPendingIntent = PendingIntent.getBroadcast(context, 1, destroyIntent, intentFlags);
			builder.addAction(this.getResourceId(infos.closeIcon, android.R.drawable.ic_menu_close_clear_cancel), "", destroyPendingIntent);
		}

		//If 5.0 >= use MediaStyle
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP){
			int[] args = new int[nbControls];
			for (int i = 0; i < nbControls; ++i) {
				args[i] = i;
			}
			Log.v("MediaControllerSession", "createBuilder");
			//this.mediaStyle.setMediaSession(this.mediaSessionCompat.getMediaSession());
			builder.setStyle(this.mediaStyle.setShowActionsInCompactView(args));
		}

		this.notificationBuilder = builder;
	}

	private int getResourceId(String name, int fallback){
		try{
			if(name.isEmpty()){
				return fallback;
			}

			int resId = this.cordovaActivity.getResources().getIdentifier(name, "drawable", this.cordovaActivity.getPackageName());
			return resId == 0 ? fallback : resId;
		}
		catch(Exception ex){
			return fallback;
		}
	}

	public void destroy(){
		this.notificationManager.cancel(this.notificationID);
		this.onNotificationDestroyed();
	}

	protected void onNotificationUpdated(Notification notification) {}
	protected void onNotificationDestroyed() {}
}
