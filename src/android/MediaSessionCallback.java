package com.homerours.musiccontrols;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.KeyEvent;

import org.apache.cordova.CallbackContext;


public class MediaSessionCallback extends MediaSessionCompat.Callback {

  private CallbackContext cb;
  private MusicControls musicControls;

  public void setCallback(CallbackContext cb){
    this.cb = cb;
  }

  public void setMusicControls(MusicControls musicControls) {
    this.musicControls = musicControls;
  }

  // kuackmedia (fgs-grace, N3): el comando trae la exención de Android para promover el FGS (10 s). Se abre la
  // ventana antes de reenviarlo al JS y aunque cb sea null (es de un solo uso y puede no estar armado). Los callbacks
  // llegan al main looper (MusicControls.initialize). `eventUptime`: la hora del KeyEvent (0 = ahora).
  private void userCommand(String trigger, boolean promoteIfOff, long eventUptime) {
    if (this.musicControls != null) {
      this.musicControls.onUserCommand(trigger, promoteIfOff, eventUptime);
    }
  }

  @Override
  public void onPlay() {
    super.onPlay();
    this.userCommand("session:play", true, 0);
    if(this.cb != null) {
      this.cb.success("{\"message\": \"music-controls-play\", \"source\": \"music-controls-media-button-play 1\"}");
      this.cb = null;
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if(this.cb != null) {
      this.cb.success("{\"message\": \"music-controls-pause\", \"source\": \"music-controls-media-button-pause 1\"}");
      this.cb = null;
    }
  }

  @Override
  public void onSkipToNext() {
    super.onSkipToNext();
    this.userCommand("session:next", true, 0);
    if(this.cb != null) {
      this.cb.success("{\"message\": \"music-controls-next\", \"source\": \"music-controls-media-button-next 1\"}");
      this.cb = null;
    }
  }

  @Override
  public void onSkipToPrevious() {
    super.onSkipToPrevious();
    this.userCommand("session:prev", true, 0);
    if(this.cb != null) {
      this.cb.success("{\"message\": \"music-controls-previous\", \"source\": \"music-controls-media-button-previous 1\"}");
      this.cb = null;
    }
  }

  @Override
  public void onPlayFromMediaId(String mediaId, Bundle extras) {
    super.onPlayFromMediaId(mediaId, extras);
  }

  @Override
  public void onSeekTo(long pos) {
    super.onSeekTo(pos);
    // Un seek en pausa no es reproducir: abre la ventana pero sólo promueve si el JS ya quería reproducir.
    this.userCommand("session:seek", false, 0);
    if(this.cb != null) {
      this.cb.success("{\"message\": \"music-controls-seek\", \"pos\": \"" + pos + "\"}");
      this.cb = null;
    }
  }

  @Override
  public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
    final Bundle extras = mediaButtonIntent.getExtras();
    final KeyEvent event = extras != null ? (KeyEvent) extras.get(Intent.EXTRA_KEY_EVENT) : null;

    if (event == null) {
      return super.onMediaButtonEvent(mediaButtonIntent);
    }

    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      final int keyCode = event.getKeyCode();
      if (isPlayCommandKey(keyCode)) {
        // La ventana corre desde la hora de la tecla: AOSP la cuenta desde el despacho, no desde este callback.
        this.userCommand("button:" + KeyEvent.keyCodeToString(keyCode), true, event.getEventTime());
      }
      switch (keyCode) {
        case KeyEvent.KEYCODE_MEDIA_PAUSE:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-pause\", \"source\": \"music-controls-media-button-pause\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_PLAY:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-play\", \"source\": \"music-controls-media-button-play\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-previous\", \"source\": \"music-controls-media-button-previous\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_NEXT:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-next\", \"source\": \"music-controls-media-button-next\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-toggle-play-pause\", \"source\": \"music-controls-media-button-play-pause\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_STOP:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-stop\", \"source\": \"music-controls-media-button-stop\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-forward\", \"source\": \"music-controls-media-button-forward\"}");
            this.cb = null;
          }
          break;
        case KeyEvent.KEYCODE_MEDIA_REWIND:

          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-rewind\", \"source\": \"music-controls-media-button-rewind\"}");
            this.cb = null;
          }
          break;
        default:
          if(this.cb != null) {
            this.cb.success("{\"message\": \"music-controls-unknown-" + keyCode + "\", \"source\": \"music-controls-media-button-unknown-" + keyCode + "\"}");
            this.cb = null;
          }
          return super.onMediaButtonEvent(mediaButtonIntent);
      }
    }

    return true;
  }

  // kuackmedia (fgs-grace, N3): teclas que pueden arrancar la reproducción (HEADSETHOOK incluida, que va al default).
  static boolean isPlayCommandKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
        || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
        || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
  }
}

