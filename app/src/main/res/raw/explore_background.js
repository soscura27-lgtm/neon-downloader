/*
 * Neon Downloader - reproducción en segundo plano de Explorar.
 *
 * Se ejecuta al empezar cada página, antes que sus propios scripts:
 *   1. La página nunca se entera de que la app pasó a segundo plano o de que la pantalla se apagó
 *      (document.hidden / visibilityState siempre "visible" y sin eventos visibilitychange). Así YouTube
 *      no pausa el vídeo al salir de la app, igual que con Premium.
 *   2. En YouTube marca actividad cada minuto para que no aparezca "¿Sigues viendo?".
 *   3. Avisa a la app de lo que suena (título, canal, segundo, si está en pausa) para la notificación y
 *      los controles, y obedece sus órdenes: reproducir y pausar.
 */
(function () {
  'use strict';

  var root = window;
  if (root.__neonBackground) { return; }
  try {
    Object.defineProperty(root, '__neonBackground', { value: true });
  } catch (e) {
    root.__neonBackground = true;
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Página siempre "visible"
  // ---------------------------------------------------------------------------------------------

  function alwaysVisible(name, value) {
    try {
      Object.defineProperty(Document.prototype, name, {
        configurable: true,
        enumerable: true,
        get: function () { return value; }
      });
    } catch (e) { /* el navegador no deja redefinirla */ }
  }
  alwaysVisible('hidden', false);
  alwaysVisible('webkitHidden', false);
  alwaysVisible('visibilityState', 'visible');
  alwaysVisible('webkitVisibilityState', 'visible');

  function swallow(event) { event.stopImmediatePropagation(); }
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
    root.addEventListener(type, swallow, true);
    document.addEventListener(type, swallow, true);
  });

  // ---------------------------------------------------------------------------------------------
  // 2. YouTube: "¿Sigues viendo?"
  // ---------------------------------------------------------------------------------------------

  if (/(^|\.)youtube\.com$/.test(location.hostname)) {
    setInterval(function () {
      try { root._lact = Date.now(); } catch (e) { /* ignorado */ }
    }, 60000);
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Estado de lo que suena y órdenes de la app
  // ---------------------------------------------------------------------------------------------

  // El vídeo principal: el reproductor de YouTube o, en otras webs, el que está sonando.
  function mainMedia() {
    var youtube = document.querySelector('#movie_player video, video.html5-main-video');
    if (youtube) { return youtube; }
    var all = document.querySelectorAll('video, audio');
    var fallback = null;
    for (var i = 0; i < all.length; i++) {
      var m = all[i];
      if (!m.paused && !m.ended && !m.muted) { return m; }
      if (!fallback && (m.currentSrc || m.src)) { fallback = m; }
    }
    return fallback;
  }

  function state() {
    var media = mainMedia();
    var title = document.title || '';
    var artist = '';
    try {
      var meta = navigator.mediaSession && navigator.mediaSession.metadata;
      if (meta) {
        if (meta.title) { title = meta.title; }
        if (meta.artist) { artist = meta.artist; }
      }
    } catch (e) { /* sin Media Session */ }
    var playing = !!(media && !media.paused && !media.ended && !media.muted);
    var position = media && isFinite(media.currentTime) ? Math.floor(media.currentTime) : 0;
    var duration = media && isFinite(media.duration) ? Math.floor(media.duration) : 0;
    return { p: playing, c: position, d: duration, t: title, a: artist, u: location.href };
  }

  // La app también puede preguntar directamente (WebView antiguos sin canal de mensajes).
  root.__neonMediaState = function () { return JSON.stringify(state()); };

  root.__neonMediaCommand = function (command) {
    var media = mainMedia();
    if (!media) { return false; }
    try {
      if (command === 'pause' || (command === 'toggle' && !media.paused)) {
        media.pause();
      } else if (command === 'play' || command === 'toggle') {
        var started = media.play();
        if (started && started.catch) { started.catch(function () { /* necesita un toque */ }); }
      }
    } catch (e) { return false; }
    return true;
  };

  var lastKey = '';
  function report(force) {
    var bridge = root.neonMedia;
    if (!bridge || typeof bridge.postMessage !== 'function') { return; }
    var current = state();
    var key = current.p + '|' + current.t + '|' + current.a + '|' + current.u;
    // mientras suena se manda cada pocos segundos (el segundo cambia); en pausa, solo si algo cambió
    if (key === lastKey && !(force && current.p)) { return; }
    lastKey = key;
    try { bridge.postMessage(JSON.stringify(current)); } catch (e) { /* la app ya no escucha */ }
  }

  ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'volumechange'].forEach(function (type) {
    document.addEventListener(type, function () {
      setTimeout(function () { report(false); }, 0);
    }, true);
  });
  setInterval(function () { report(true); }, 4000);
})();
