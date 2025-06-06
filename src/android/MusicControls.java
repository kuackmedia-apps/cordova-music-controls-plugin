package com.homerours.musiccontrols;
import androidx.media.VolumeProviderCompat;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import android.util.Log;
import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.content.BroadcastReceiver;
import android.media.AudioManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import java.util.List;

import android.view.View;
import android.os.PowerManager;

import static android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;

public class MusicControls extends CordovaPlugin {
	private MusicControlsBroadcastReceiver mMessageReceiver;
	private MusicControlsNotification notification;
	private MediaSessionCompat mediaSessionCompat;
	private final int notificationID=7824;
	private AudioManager mAudioManager;
	private PendingIntent mediaButtonPendingIntent;
	private boolean mediaButtonAccess=true;

  	private Activity cordovaActivity;
	private MusicControls self = this;

	private MediaSessionCallback mMediaSessionCallback = new MediaSessionCallback();

	private MusicControlsServiceConnection mConnection;
	private CallbackContext volumeCallbackContext;
	private VolumeProviderCompat volumeProvider;

	private void registerBroadcaster(MusicControlsBroadcastReceiver mMessageReceiver){
		final Context context = this.cordova.getActivity().getApplicationContext();

        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-previous"), new Integer(4));
        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-pause"), new Integer(4));
        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-play"), new Integer(4));
        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-next"), new Integer(4));
        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-media-button"), new Integer(4));
        context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter("music-controls-destroy"), new Integer(4));

		// Listen for headset plug/unplug
		context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

		// Listen for bluetooth connection state changes
		context.registerReceiver((BroadcastReceiver)mMessageReceiver, new IntentFilter(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED));
	}

	// Register pendingIntent for broacast
	public void registerMediaButtonEvent(){

		this.mediaSessionCompat.setMediaButtonReceiver(this.mediaButtonPendingIntent);

		/*if (this.mediaButtonAccess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2){
		this.mAudioManager.registerMediaButtonEventReceiver(this.mediaButtonPendingIntent);
		}*/
	}

	public void unregisterMediaButtonEvent(){
		this.mediaSessionCompat.setMediaButtonReceiver(null);
		/*if (this.mediaButtonAccess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2){
		this.mAudioManager.unregisterMediaButtonEventReceiver(this.mediaButtonPendingIntent);
		}*/
	}

	public void destroyPlayerNotification(){
		this.notification.destroy();
	}

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		final Activity activity = this.cordova.getActivity();
		final Context context = activity.getApplicationContext();

		// Notification Killer
		mConnection = new MusicControlsServiceConnection(activity);

		this.cordovaActivity = activity;
		this.notification = new MusicControlsNotification(this.cordovaActivity, this.notificationID) {
			@Override
			protected void onNotificationUpdated(Notification notification) {
				mConnection.setNotification(notification, this.infos.isPlaying);
			}

			@Override
			protected void onNotificationDestroyed() {
				mConnection.setNotification(null, false);
			}
		};

		this.mMessageReceiver = new MusicControlsBroadcastReceiver();
		this.mMessageReceiver.setMusicControls(this);
		IntentFilter filter = new IntentFilter();
		filter.addAction("com.homerours.musiccontrols.MUSIC_CONTROL_ACTION");
		this.registerBroadcaster(mMessageReceiver);


		this.mediaSessionCompat = new MediaSessionCompat(context, "cordova-music-controls-media-session", null, this.mediaButtonPendingIntent);
		this.mediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

		/*
		VOLUMEN CONTROL
		 */

		// En el método onAdjustVolume de VolumeProviderCompat:
		this.volumeProvider = new VolumeProviderCompat(
				VolumeProviderCompat.VOLUME_CONTROL_RELATIVE,  // Tipo de control
				100,                                           // Volumen máximo
				50                                             // Volumen inicial
		) {
			@Override
			public void onAdjustVolume(int direction) {
				Log.v("VolumeProviderCompat", "Ajustar volumen: " + direction);

				// Si se ha asignado el CallbackContext, enviar el evento
				if (volumeCallbackContext != null) {
					// Puedes enviar un objeto JSON o simplemente la dirección, según tus necesidades
					JSONObject data = new JSONObject();

					try {
						data.put("message","volume");
						data.put("direction", direction);
					} catch (JSONException e) {
						e.printStackTrace();
					}
					PluginResult result = new PluginResult(PluginResult.Status.OK, data.toString());
					// Mantiene el callback activo para futuros eventos
					result.setKeepCallback(true);
					volumeCallbackContext.sendPluginResult(result);
				}
			}

			@Override
			public void onSetVolumeTo(int volume) {
				// Implementación si usas control absoluto
			}
		};

		//mediaSessionCompat.setPlaybackToRemote(volumeProvider);
		//mediaSessionCompat.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
		//this.mediaSessionCompat.setPlaybackToRemote(this.mConnection.getRemoteVolumeProvider());
	//	this.notification.setSessionToken(this.mediaSessionCompat.getSessionToken());
		Log.v("MediaControllerSession", "this.mediaSessionCompat " + this.mediaSessionCompat.getSessionToken().toString());
		this.notification.setMediaSessionCompat(mediaSessionCompat);

		this.mediaSessionCompat.setActive(true);
		setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);

		this.mediaSessionCompat.setCallback(this.mMediaSessionCallback);

		// Register media (headset) button event receiver
		try {
			this.mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
			Intent headsetIntent = new Intent("music-controls-media-button");
			headsetIntent.setPackage(context.getPackageName());
			int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;  // Only add on platform levels that support FLAG_MUTABLE
			this.mediaButtonPendingIntent = PendingIntent.getBroadcast(context, 0, headsetIntent, flag);
			this.registerMediaButtonEvent();
		} catch (Exception e) {
			this.mediaButtonAccess=false;
			e.printStackTrace();
		}

		Intent startServiceIntent = new Intent(activity,MusicControlsNotificationKiller.class);
		startServiceIntent.putExtra("notificationID",this.notificationID);
		activity.bindService(startServiceIntent, mConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
		final Context context=this.cordova.getActivity().getApplicationContext();
		final Activity activity=this.cordova.getActivity();


		if (action.equals("create")) {
			final MusicControlsInfos infos = new MusicControlsInfos(args);
			 final MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();


			this.cordova.getThreadPool().execute(new Runnable() {
				public void run() {


					// track title
					metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, infos.track);
					// artists
					metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, infos.artist);
					//album
					metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, infos.album);

					//duration
					metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, infos.duration);

					Bitmap art = getBitmapCover(infos.cover);
					if(art != null){
						metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
						metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art);

					}

					mediaSessionCompat.setMetadata(metadataBuilder.build());
					if (infos.isCasting) {
						mediaSessionCompat.setPlaybackToRemote(self.volumeProvider);
					} else {
						mediaSessionCompat.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
					}

					if(infos.isPlaying)
						setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
					else
						setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
					notification.updateNotification(infos);
					callbackContext.success("success");
				}
			});
		}
		else if (action.equals("updateIsPlaying")){
			final JSONObject params = args.getJSONObject(0);
			final boolean isPlaying = params.getBoolean("isPlaying");
			final long position = params.getLong("position");
			this.notification.updateIsPlaying(isPlaying);
			Log.i("Music controls",  "updateIsPlaying " + position);
			if(isPlaying)
				setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING, position);
			else
				setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED, position);

			callbackContext.success("success");
		}
		else if (action.equals("updateDismissable")){
			final JSONObject params = args.getJSONObject(0);
			final boolean dismissable = params.getBoolean("dismissable");
			this.notification.updateDismissable(dismissable);
			callbackContext.success("success");
		}
		else if (action.equals("destroy")){
			this.notification.destroy();
			this.mMessageReceiver.stopListening();
			callbackContext.success("success");
		}
		else if (action.equals("watch")) {
			this.registerMediaButtonEvent();
      			this.cordova.getThreadPool().execute(new Runnable() {
				public void run() {
          			mMediaSessionCallback.setCallback(callbackContext);
					mMessageReceiver.setCallback(callbackContext);
					volumeCallbackContext = callbackContext;
				}
			});
		}
		else if (action.equals("disableBatteryOptimization")){
            String packageName = activity.getPackageName();
            PowerManager powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                return false;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false;
            }
            Intent intent = new Intent();
            intent.setAction(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            activity.startActivity(intent);

            callbackContext.success("success");
        }
        else if (action.equals("disableWebViewOptimizations")){
            Thread thread = new Thread() {
                public void run() {
                    try {
                        Thread.sleep(1000);
                        activity.runOnUiThread(new Runnable() {
                            public void run() {
                                webView.getEngine().getView().dispatchWindowVisibilityChanged(View.VISIBLE);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("MMC", "ERROR: " + e.getMessage());
                    }
                }
            };
            thread.start();
            callbackContext.success("success");
        }
		return true;
	}

	@Override
	public void onDestroy() {
		final Activity activity = this.cordova.getActivity();
		final Context context = activity.getApplicationContext();
		final ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);

        for (RunningServiceInfo runningServiceInfo : services) {
            final String runningServiceClassName = runningServiceInfo.service.getClassName();
            if (runningServiceClassName.equals("com.homerours.musiccontrols.MusicControlsNotificationKiller")){
                Intent startServiceIntent = new Intent(context, MusicControlsNotificationKiller.class);
                startServiceIntent.putExtra("notificationID", this.notificationID);
                context.stopService(startServiceIntent);
                activity.unbindService(this.mConnection);
            }
        }

		this.notification.destroy();
		this.mMessageReceiver.stopListening();
		this.unregisterMediaButtonEvent();
		super.onDestroy();
	}

	@Override
	public void onReset() {
		onDestroy();
		super.onReset();
	}
	private void setMediaPlaybackState(int state) {
		this.setMediaPlaybackState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN);
		//this.setMediaPlaybackState(state, 20000L);
	}
	private void setMediaPlaybackState(int state, Long position) {

		PlaybackStateCompat.Builder playbackstateBuilder = new PlaybackStateCompat.Builder();
		if( state == PlaybackStateCompat.STATE_PLAYING ) {
			playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
					PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
					PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
					PlaybackStateCompat.ACTION_SEEK_TO);
			playbackstateBuilder.setState(state, position, 1.0f);
		} else {
			playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
					PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
					PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
					PlaybackStateCompat.ACTION_SEEK_TO);
			playbackstateBuilder.setState(state, position, 0);
		}
		if (this.mediaSessionCompat.isActive()) {
            this.mediaSessionCompat.setPlaybackState(playbackstateBuilder.build());
        }
	}
	// Get image from url
	private Bitmap getBitmapCover(String coverURL){
		try{
			if(coverURL.matches("^(https?|ftp)://.*$"))
				// Remote image
				return getBitmapFromURL(coverURL);
			else {
				// Local image
				return getBitmapFromLocal(coverURL);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
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
}
