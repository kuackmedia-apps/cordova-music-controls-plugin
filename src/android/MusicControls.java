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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.BroadcastReceiver;
import android.media.AudioManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

	// kuackmedia (fgs-grace, N8): orden de los pedidos del JS. execute() corre en el hilo del puente y en el orden
	// del JS; el create termina después en el thread pool (baja la carátula) y no puede pisar algo más nuevo.
	private final AtomicLong mSeq = new AtomicLong();
	private final Object playStateLock = new Object();
	private long lastPlayingSeq = 0;
	private boolean lastPublishedPlaying = false;
	private long lastCreateSeq = 0;
	// kuackmedia (fgs-grace, N4): generación de la página del WebView. onReset (main looper, el único que la escribe)
	// la incrementa; execute() la captura en el hilo del puente. Lo que una página anterior termina después del
	// reload (un create que bajaba la carátula) no toca la notificación, la sesión ni el FGS.
	private volatile int pageGen = 0;
	// Generación con la que sale el próximo publish (bajo playStateLock: la lee onNotificationUpdated, que corre
	// adentro del notification.update* de quien tiene el lock).
	private int publishGen = 0;
	// Último estado publicado en la sesión, para la pausa del reload sin perder la posición (bajo playStateLock).
	private long lastSessionPosition = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
	private long lastSessionAt = 0;
	private boolean lastSessionPlaying = false;

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

		// Notification Killer. kuackmedia (fgs-grace): mConnection es el dueño del FGS (máquina de estados).
		mConnection = new MusicControlsServiceConnection(activity, this.notificationID);

		this.cordovaActivity = activity;
		this.notification = new MusicControlsNotification(this.cordovaActivity, this.notificationID) {
			@Override
			protected void onNotificationUpdated(Notification notification, String source) {
				mConnection.publish(notification, this.infos.isPlaying, source, "create".equals(source), publishGen);
			}

			@Override
			protected void onNotificationDestroyed(AtomicInteger notifyCount, int seen, Runnable cancel) {
				// N6: REMOVE y después el cancel, los dos en el main looper.
				mConnection.destroyForeground("destroy", notifyCount, seen, cancel);
			}
		};
		this.mMediaSessionCallback.setMusicControls(this);

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
		this.notification.setMediaSessionCompat(mediaSessionCompat);

		this.mediaSessionCompat.setActive(true);
		setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);

		// kuackmedia (fgs-grace, N3): los comandos llegan al main looper. Sin Handler llegaban al hilo donde corrió
		// initialize (el del puente), que puede estar esperando el lock de la notificación de un create, y la ventana
		// de exención se abría tarde.
		this.mediaSessionCompat.setCallback(this.mMediaSessionCallback, new Handler(Looper.getMainLooper()));

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

		// kuackmedia (fgs-grace, N4): el bind pasó a mConnection.ensureBound(), que se repite si el servicio se pierde.
		mConnection.ensureBound();
	}

	@Override
	public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
		final Context context=this.cordova.getActivity().getApplicationContext();
		final Activity activity=this.cordova.getActivity();
		final long seq = this.mSeq.incrementAndGet();
		final int gen = this.pageGen;

		if (action.equals("create")) {
			final MusicControlsInfos infos = new MusicControlsInfos(args);
			 final MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();


			this.cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					try {
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

						synchronized (playStateLock) {
							// kuackmedia (fgs-grace, N8): un create que tardó con la carátula no pisa algo más nuevo.
							// N4: ni uno de la página anterior a un reload.
							if (gen == pageGen && seq >= lastCreateSeq) {
								lastCreateSeq = seq;
								if (seq < lastPlayingSeq) {
									// Después de este create el JS ya publicó otro estado: vale ése.
									infos.isPlaying = lastPublishedPlaying;
								} else {
									lastPlayingSeq = seq;
									lastPublishedPlaying = infos.isPlaying;
								}

								mediaSessionCompat.setMetadata(metadataBuilder.build());
								if (infos.isCasting) {
									mediaSessionCompat.setPlaybackToRemote(self.volumeProvider);
								} else {
									mediaSessionCompat.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
								}

								if(infos.isPlaying)
									setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING, infos.elapsed);
								else
									setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED, infos.elapsed);
								publishGen = gen;
								notification.updateNotification(infos);
							}
						}
						// kuackmedia (fgs-grace, N5): el success es el estado del FGS (JSON), desde el main looper.
						mConnection.answer(callbackContext);
					} catch (Exception e) {
						Log.e("MusicControls", "create falló", e);
						callbackContext.error(String.valueOf(e));
					}
				}
			});
		}
		else if (action.equals("updateIsPlaying")){
			final JSONObject params = args.getJSONObject(0);
			final boolean isPlaying = params.getBoolean("isPlaying");
			final long position = params.getLong("position");
			synchronized (this.playStateLock) {
				// kuackmedia (fgs-grace, N4): lo de la página anterior a un reload no se aplica (sí se responde).
				if (gen == this.pageGen) {
					// N8: el último estado publicado, para un create más viejo que termine después.
					this.lastPlayingSeq = seq;
					this.lastPublishedPlaying = isPlaying;
					this.publishGen = gen;
					this.notification.updateIsPlaying(isPlaying);
					if(isPlaying)
						setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING, position);
					else
						setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED, position);
				}
			}

			// kuackmedia (fgs-grace, N5): el success es el estado del FGS (JSON), desde el main looper y después de
			// aplicar este pedido. Un binario viejo devolvía "success" y el JS lo ignora.
			this.mConnection.answer(callbackContext);
		}
		else if (action.equals("getForegroundState")) {
			// kuackmedia (fgs-grace, N5): para el Admin y el diagnóstico; no toca el estado.
			this.mConnection.answer(callbackContext);
		}
		else if (action.equals("setForegroundPolicy")) {
			// kuackmedia (fgs-grace, N1): [{ graceMs?, legacy? }]. graceMs 0 = bajar el FGS al instante en una pausa
			// (sólo eso). legacy true = kill-switch por OTA: la promoción y la baja del plugin viejo (KUACKMEDIA.md).
			final JSONObject opts = args.optJSONObject(0);
			final boolean hasGrace = opts != null && opts.has("graceMs") && !opts.isNull("graceMs");
			final boolean hasLegacy = opts != null && opts.has("legacy") && !opts.isNull("legacy");
			final long graceMs = hasGrace ? opts.optLong("graceMs", -1L) : -1L;
			if ((!hasGrace && !hasLegacy) || (hasGrace && graceMs < 0)) {
				callbackContext.error("política inválida");
			} else {
				final int legacy = hasLegacy ? (opts.optBoolean("legacy", false) ? 1 : 0) : -1;
				this.mConnection.setPolicy(graceMs, legacy, callbackContext);
			}
		}
		else if (action.equals("updateDismissable")){
			final JSONObject params = args.getJSONObject(0);
			final boolean dismissable = params.getBoolean("dismissable");
			synchronized (this.playStateLock) {
				// kuackmedia (fgs-grace, N4): el publish lleva la generación de la página que lo pidió.
				if (gen == this.pageGen) {
					this.publishGen = gen;
					this.notification.updateDismissable(dismissable);
				}
			}
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
		// kuackmedia (fgs-grace, N4/N6): el destroy real (la Activity se destruye). Orden: gracia cancelada, FGS abajo
		// con REMOVE y recién después el unbind (mConnection.shutdown). El servicio nunca se inicia con startService,
		// así que ya no hace falta el stopService de antes.
		this.mConnection.shutdown();
		this.notification.destroy();
		this.mMessageReceiver.stopListening();
		this.unregisterMediaButtonEvent();
		super.onDestroy();
	}

	@Override
	public void onReset() {
		// kuackmedia (fgs-grace, N4 / R27): un reload del WebView ya no llama a onDestroy. Antes el unbind dejaba al
		// app sin FGS hasta que muriera el proceso (el único bindService está en initialize, que no se repite). Se
		// sueltan los callbacks de la página vieja y el reset cuenta como una pausa: bind, sesión y notificación
		// quedan y el FGS espera la gracia. La generación nueva descarta lo que la página vieja tenga en vuelo (un
		// create bajando la carátula). Si queda el FGS se publica la pausa (▶ y sesión PAUSED: el reload cortó el
		// audio); si no, la notificación se borra como antes.
		this.mMessageReceiver.stopListening();
		this.mMediaSessionCallback.setCallback(null);
		this.volumeCallbackContext = null;
		this.pageGen++;
		final int gen = this.pageGen;
		final long seq = this.mSeq.incrementAndGet();
		final MusicControlsNotification notification = this.notification;
		this.mConnection.onWebViewReset(gen, new Runnable() {
			public void run() {
				publishResetPause(gen, seq, true);
			}
		}, new Runnable() {
			public void run() {
				notification.destroy();
				publishResetPause(gen, seq, false);
			}
		});
		super.onReset();
	}

	// kuackmedia (fgs-grace, N4): la pausa del reload. cordova-plugin-media y el motor de kuack-player sueltan sus
	// reproductores en su onReset: la notificación pasa a ▶ (`notify`) y la sesión a PAUSED en la posición de ese
	// momento. En el thread pool: updateIsPlaying espera el lock de la notificación, que un create puede tener tomado
	// mientras baja la carátula, y el main looper no puede esperarlo. Lo descarta otro reload o lo que la página
	// nueva ya haya publicado.
	private void publishResetPause(final int gen, final long seq, final boolean notify) {
		this.cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				synchronized (playStateLock) {
					if (gen != pageGen || seq < lastPlayingSeq || seq < lastCreateSeq) {
						return;
					}
					lastPlayingSeq = seq;
					lastPublishedPlaying = false;
					if (notify) {
						publishGen = gen;
						notification.updateIsPlaying(false);
					}
					setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED, currentSessionPosition());
				}
			}
		});
	}

	// Bajo playStateLock: la posición que muestra la sesión ahora (avanza si estaba en PLAYING).
	private long currentSessionPosition() {
		if (this.lastSessionPosition < 0) {
			return PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
		}
		if (!this.lastSessionPlaying) {
			return this.lastSessionPosition;
		}
		return this.lastSessionPosition + Math.max(0L, SystemClock.elapsedRealtime() - this.lastSessionAt);
	}

	// kuackmedia (fgs-grace, N3): visibilidad de la Activity para la regla de exención. Al volver a verse, si el FGS
	// quedó DEFERRED se promueve (reemplaza a la re-promoción del JS al volver, O7).
	@Override
	public void onStart() {
		super.onStart();
		if (this.mConnection != null) {
			this.mConnection.onVisible();
		}
	}

	@Override
	public void onResume(boolean multitasking) {
		super.onResume(multitasking);
		if (this.mConnection != null) {
			this.mConnection.onVisible();
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		if (this.mConnection != null) {
			this.mConnection.onHidden();
		}
	}

	// kuackmedia (fgs-grace, N3): MediaSessionCallback y MusicControlsBroadcastReceiver lo llaman con cada comando
	// del usuario, antes de reenviarlo al JS.
	void onUserCommand(String trigger, boolean promoteIfOff, long eventUptime) {
		if (this.mConnection != null) {
			this.mConnection.onUserCommand(trigger, promoteIfOff, eventUptime);
		}
	}
	private void setMediaPlaybackState(int state) {
		this.setMediaPlaybackState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN);
		//this.setMediaPlaybackState(state, 20000L);
	}
	private void setMediaPlaybackState(int state, Long position) {

		// kuackmedia (fgs-grace, N4): para la pausa del reload (currentSessionPosition).
		this.lastSessionPosition = position != null ? position : PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
		this.lastSessionAt = SystemClock.elapsedRealtime();
		this.lastSessionPlaying = state == PlaybackStateCompat.STATE_PLAYING;
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
			// kuackmedia (fgs-grace, N7): sin timeouts, una descarga colgada bajo Doze demoraba el create.
			connection.setConnectTimeout(3000);
			connection.setReadTimeout(5000);
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
