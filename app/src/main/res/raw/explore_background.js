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

  // Lo que suena ahora: primero cualquier vídeo o audio que esté sonando (en los Shorts hay varios vídeos
  // precargados), después el reproductor principal de YouTube y, si no, el primero que tenga contenido.
  function mainMedia() {
    var all = document.querySelectorAll('video, audio');
    var i, m;
    for (i = 0; i < all.length; i++) {
      m = all[i];
      if (!m.paused && !m.ended && !m.muted && m.readyState > 0) { return m; }
    }
    var youtube = document.querySelector('#movie_player video, video.html5-main-video');
    if (youtube) { return youtube; }
    for (i = 0; i < all.length; i++) {
      m = all[i];
      if (m.currentSrc || m.src) { return m; }
    }
    return null;
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

  // --- Anterior / siguiente (Mix, playlists, vídeos sueltos y Shorts) ---

  var NEXT_BUTTONS = [
    '.ytp-next-button', '#movie_player [aria-label*="next video" i]', '#movie_player [aria-label*="siguiente" i]',
    'button[aria-label*="next video" i]', 'button[aria-label*="siguiente vídeo" i]', 'button[aria-label*="siguiente video" i]'
  ];
  var PREVIOUS_BUTTONS = [
    '.ytp-prev-button', '#movie_player [aria-label*="previous video" i]', '#movie_player [aria-label*="anterior" i]',
    'button[aria-label*="previous video" i]', 'button[aria-label*="vídeo anterior" i]', 'button[aria-label*="video anterior" i]'
  ];

  function clickFirst(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var button = null;
      try { button = document.querySelector(selectors[i]); } catch (e) { /* selector no admitido */ }
      if (button && !button.disabled && button.getAttribute('aria-disabled') !== 'true') {
        button.click();
        return true;
      }
    }
    return false;
  }

  function currentVideoId() {
    var match = location.href.match(/[?&]v=([\w-]{11})/) || location.pathname.match(/\/shorts\/([\w-]{11})/);
    return match ? match[1] : '';
  }

  // En un Mix o una playlist: el vídeo de la lista que va antes o después del que suena.
  function stepInList(step) {
    var list = (location.search.match(/[?&]list=([\w-]+)/) || [])[1];
    var current = currentVideoId();
    if (!list || !current) { return false; }
    var ids = [], links = {};
    var anchors = document.querySelectorAll('a[href*="list=' + list + '"]');
    for (var i = 0; i < anchors.length; i++) {
      var m = (anchors[i].getAttribute('href') || '').match(/[?&]v=([\w-]{11})/);
      if (m && !links[m[1]]) { ids.push(m[1]); links[m[1]] = anchors[i]; }
    }
    var target = links[ids[ids.indexOf(current) + step]];
    if (ids.indexOf(current) < 0 || !target) { return false; }
    target.click();
    return true;
  }

  // Vídeo suelto: el primero de los recomendados (lo que YouTube pondría a continuación).
  function firstSuggestion() {
    var current = currentVideoId();
    var anchors = document.querySelectorAll('a[href*="/watch?v="]');
    for (var i = 0; i < anchors.length; i++) {
      var m = (anchors[i].getAttribute('href') || '').match(/[?&]v=([\w-]{11})/);
      if (m && m[1] !== current) { anchors[i].click(); return true; }
    }
    return false;
  }

  // Shorts: se desliza la lista vertical una pantalla arriba o abajo.
  function stepInShorts(step) {
    var el = mainMedia();
    while (el && el !== document.body && el !== document.documentElement) {
      var style = getComputedStyle(el);
      if (/(auto|scroll)/.test(style.overflowY) && el.scrollHeight > el.clientHeight + 10) {
        el.scrollBy({ top: step * el.clientHeight, behavior: 'smooth' });
        return true;
      }
      el = el.parentElement;
    }
    root.scrollBy({ top: step * root.innerHeight, behavior: 'smooth' });
    return true;
  }

  function skip(step) {
    var media = mainMedia();
    // "Anterior" con la canción ya empezada vuelve al principio, como en cualquier reproductor
    if (step < 0 && media && media.currentTime > 5) {
      media.currentTime = 0;
      return true;
    }
    if (/^\/shorts\//.test(location.pathname)) { return stepInShorts(step); }

    var before = location.href;
    var player = document.getElementById('movie_player');
    var method = step > 0 ? 'nextVideo' : 'previousVideo';
    var asked = false;
    try {
      if (player && typeof player[method] === 'function') { player[method](); asked = true; }
    } catch (e) { /* el reproductor no lo admite aquí */ }

    // si el reproductor no cambió de vídeo, se prueba con sus botones, la lista o la página anterior
    setTimeout(function () {
      if (location.href !== before) { return; }
      if (clickFirst(step > 0 ? NEXT_BUTTONS : PREVIOUS_BUTTONS)) { return; }
      if (stepInList(step)) { return; }
      if (step > 0) { firstSuggestion(); } else { history.back(); }
    }, asked ? 1500 : 0);
    return true;
  }

  root.__neonMediaCommand = function (command) {
    if (command === 'next') { return skip(1); }
    if (command === 'previous') { return skip(-1); }
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
