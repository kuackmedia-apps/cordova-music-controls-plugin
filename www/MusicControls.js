var musicControls = {
  updateCallback: function() {},

  create: function(data, successCallback, errorCallback) {
    data.artist = !isUndefined(data.artist) ? data.artist : "";
    data.track = !isUndefined(data.track) ? data.track : "";
    data.album = !isUndefined(data.album) ? data.album : "";
    data.cover = !isUndefined(data.cover) ? data.cover : "";
    data.ticker = !isUndefined(data.ticker) ? data.ticker : "";
    data.duration = !isUndefined(data.duration) ? data.duration : 0;
    data.elapsed = !isUndefined(data.elapsed) ? data.elapsed : 0;
    data.isPlaying = !isUndefined(data.isPlaying) ? data.isPlaying : true;
    data.hasPrev = !isUndefined(data.hasPrev) ? data.hasPrev : true;
    data.hasNext = !isUndefined(data.hasNext) ? data.hasNext : true;
    data.hasSkipForward = !isUndefined(data.hasSkipForward) ? data.hasSkipForward : false;
    data.hasSkipBackward = !isUndefined(data.hasSkipBackward) ? data.hasSkipBackward : false;
    data.hasScrubbing = !isUndefined(data.hasScrubbing) ? data.hasScrubbing : false;
    data.skipForwardInterval = !isUndefined(data.skipForwardInterval) ? data.skipForwardInterval : 0;
    data.skipBackwardInterval = !isUndefined(data.skipBackwardInterval) ? data.skipBackwardInterval : 0;
    data.hasClose = !isUndefined(data.hasClose) ? data.hasClose : false;
    data.dismissable = !isUndefined(data.dismissable) ? data.dismissable : false;
    data.playIcon = !isUndefined(data.playIcon) ? data.playIcon : "";
    data.pauseIcon = !isUndefined(data.pauseIcon) ? data.pauseIcon : "";
    data.prevIcon = !isUndefined(data.prevIcon) ? data.prevIcon : "";
    data.nextIcon = !isUndefined(data.nextIcon) ? data.nextIcon : "";
    data.closeIcon = !isUndefined(data.closeIcon) ? data.closeIcon : "";
    data.notificationIcon = !isUndefined(data.notificationIcon) ? data.notificationIcon : "";

    cordova.exec(successCallback, errorCallback, "MusicControls", "create", [
      data
    ]);
  },

  updateIsPlaying: function(args, successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "updateIsPlaying",
      [{
        isPlaying: args.isPlaying,
        position: args.position
      }]
    );
  },
  updateElapsed: function(args, successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "updateElapsed",
      [
        {
          elapsed: args.elapsed,
          isPlaying: args.isPlaying === undefined ? "" : !!args.isPlaying
        }
      ]
    );
  },
  updateDismissable: function(dismissable, successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "updateDismissable",
      [{ dismissable: dismissable }]
    );
  },

  destroy: function(successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "destroy",
      []
    );
  },

  // Register callback
  subscribe: function(onUpdate) {
    musicControls.updateCallback = onUpdate;
  },
  // Start listening for events
  listen: function() {
    cordova.exec(
      musicControls.receiveCallbackFromNative,
      function(res) {},
      "MusicControls",
      "watch",
      []
    );
  },
  receiveCallbackFromNative: function(messageFromNative) {
    musicControls.updateCallback(messageFromNative);
    cordova.exec(
      musicControls.receiveCallbackFromNative,
      function(res) {},
      "MusicControls",
      "watch",
      []
    );
  },

  disableBatteryOptimization: function(successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "disableBatteryOptimization",
      []
    );
  },
  disableWebViewOptimizations: function(successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "disableWebViewOptimizations",
      []
    );
  },

  // kuackmedia (fgs-grace): estado del servicio en primer plano de Android. El success
  // recibe un JSON string: {"fgs":"on|grace|off|deferred|denied|ignored|unbound",
  // "why":"…","err":"…","sdk":N,"graceMs":N,"legacy":bool}. Que la función exista indica
  // el binario nuevo, pero existe en todas las plataformas: el app gatea además por
  // Android y no TV. Fuera de Android no hay acción nativa: va directo al error.
  getForegroundState: function(successCallback, errorCallback) {
    if (!isAndroid()) {
      if (errorCallback) errorCallback("not_supported");
      return;
    }
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "getForegroundState",
      []
    );
  },
  // kuackmedia (fgs-grace): opts = { graceMs: N, legacy: bool }, las dos opcionales.
  // graceMs: cuánto espera el FGS después de una pausa antes de bajar (default 600000;
  // 0 = bajar al instante, y nada más). legacy: true vuelve a la promoción y la baja
  // del plugin viejo (kill-switch por OTA); false, a las nuevas.
  setForegroundPolicy: function(opts, successCallback, errorCallback) {
    if (!isAndroid()) {
      if (errorCallback) errorCallback("not_supported");
      return;
    }
    var policy = {};
    if (opts && !isUndefined(opts.graceMs)) {
      policy.graceMs = opts.graceMs;
    }
    if (opts && !isUndefined(opts.legacy)) {
      policy.legacy = !!opts.legacy;
    }
    cordova.exec(
      successCallback,
      errorCallback,
      "MusicControls",
      "setForegroundPolicy",
      [policy]
    );
  },
};

function isAndroid() {
  return !!(window.cordova && window.cordova.platformId === "android");
}

function isUndefined(val) {
  return val === undefined;
}

module.exports = musicControls;
