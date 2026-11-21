/*
 * Neon Downloader - vídeos de YouTube en Explorar. Se ejecuta al empezar cada página de YouTube.
 *
 *   1. Sin arranque en silencio: YouTube empieza el vídeo en silencio (con el botón "ACTIVAR SONIDO") cuando la página
 *      se abrió sin un toque dentro de ella, por ejemplo desde el buscador de la app. Entonces se le activa el sonido,
 *      como hace la app de YouTube, salvo que lo haya silenciado el propio usuario (YouTube guarda esa elección).
 *   2. Pantalla completa en horizontal: con la clase "neon-fullscreen" la página deja de desplazarse y esconde la
 *      cabecera de YouTube; la app coloca el navegador para que el reproductor llene la pantalla
 *      (window.__neonPlayerRect dice dónde está el reproductor).
 */
(function () {
  'use strict';

  var root = window;
  if (root.__neonWatch) { return; }
  try {
    Object.defineProperty(root, '__neonWatch', { value: true });
  } catch (e) {
    root.__neonWatch = true;
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Sin arranque en silencio
  // ---------------------------------------------------------------------------------------------

  var START_WINDOW_MS = 8000;   // solo se corrige el arranque de cada vídeo
  var USER_ACTION_MS = 1500;    // un silencio tan pegado a un toque lo puso el usuario
  var MAX_TRIES = 6;

  var lastInput = 0;
  ['pointerdown', 'touchstart', 'mousedown', 'keydown'].forEach(function (type) {
    root.addEventListener(type, function () { lastInput = Date.now(); }, true);
  });

  var startedAt = {};
  var tries = {};
  var userMuted = {};

  function videoIdNow() {
    var match = location.href.match(/[?&]v=([\w-]{11})/) ||
      location.pathname.match(/^\/(?:shorts|live)\/([\w-]{11})/);
    return match ? match[1] : '';
  }

  // YouTube guarda el volumen elegido: {"data": "{\"volume\":100,\"muted\":false}", ...}
  function userChoseMute() {
    try {
      var raw = root.localStorage.getItem('yt-player-volume');
      if (!raw) { return false; }
      var stored = JSON.parse(raw);
      var data = typeof stored.data === 'string' ? JSON.parse(stored.data) : stored.data;
      return !!(data && (data.muted === true || data.volume === 0));
    } catch (e) {
      return false;
    }
  }

  // El vídeo que suena (en Shorts hay varios precargados) o, si ninguno, el del reproductor principal
  function activeVideo() {
    var all = document.querySelectorAll('video');
    for (var i = 0; i < all.length; i++) {
      var v = all[i];
      if (!v.paused && !v.ended && v.readyState > 1) { return v; }
    }
    return document.querySelector('#movie_player video') || document.querySelector('video.html5-main-video');
  }

  function unmuteIfAutoplayMuted() {
    var id = videoIdNow();
    if (!id || userMuted[id] || userChoseMute()) { return; }
    var media = activeVideo();
    if (!media || !media.muted) { return; }
    var now = Date.now();
    if (!startedAt[id]) { startedAt[id] = now; }
    if (now - startedAt[id] > START_WINDOW_MS) { return; }
    tries[id] = (tries[id] || 0) + 1;
    if (tries[id] > MAX_TRIES) { return; }

    // el botón "ACTIVAR SONIDO" de YouTube hace lo mismo que tocarlo
    var button = document.querySelector('.ytp-unmute');
    if (button && button.offsetParent !== null) {
      try { button.click(); } catch (e) { /* sigue con el reproductor */ }
    }
    var player = document.getElementById('movie_player');
    try {
      if (player && typeof player.isMuted === 'function' && player.isMuted() && typeof player.unMute === 'function') {
        player.unMute();
      }
      if (player && typeof player.getVolume === 'function' && player.getVolume() === 0 &&
          typeof player.setVolume === 'function') {
        player.setVolume(100);
      }
    } catch (e) { /* reproductor sin esa función */ }
    try { if (media.muted) { media.muted = false; } } catch (e) { /* ignorado */ }
  }

  document.addEventListener('volumechange', function (event) {
    var media = event.target;
    if (!media || media.tagName !== 'VIDEO') { return; }
    if (media.muted && Date.now() - lastInput < USER_ACTION_MS) {
      userMuted[videoIdNow()] = true;   // lo silenció el usuario: se respeta
      return;
    }
    if (media.muted) { setTimeout(unmuteIfAutoplayMuted, 60); }
  }, true);

  ['loadedmetadata', 'playing'].forEach(function (type) {
    document.addEventListener(type, function (event) {
      if (event.target && event.target.tagName === 'VIDEO') { setTimeout(unmuteIfAutoplayMuted, 60); }
    }, true);
  });

  // ---------------------------------------------------------------------------------------------
  // 2. Pantalla completa en horizontal
  // ---------------------------------------------------------------------------------------------

  var STYLE_ID = 'neon-watch-style';
  var STYLE = [
    'html.neon-fullscreen, html.neon-fullscreen body {',
    '  overflow: hidden !important; overscroll-behavior: none !important; background: #000 !important; }',
    'html.neon-fullscreen ytm-mobile-topbar-renderer, html.neon-fullscreen header.mobile-topbar-header,',
    'html.neon-fullscreen #header-bar { display: none !important; }'
  ].join('\n');

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) { return; }
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = STYLE;
    (document.head || document.documentElement).appendChild(style);
  }

  root.__neonFullscreen = function (on) {
    ensureStyle();
    var html = document.documentElement;
    if (on) {
      try { root.scrollTo(0, 0); } catch (e) { /* ignorado */ }
      html.classList.add('neon-fullscreen');
    } else {
      html.classList.remove('neon-fullscreen');
    }
    return !!on;
  };

  // Dónde está el reproductor (en píxeles de la página) y cuántos píxeles de pantalla mide cada uno
  root.__neonPlayerRect = function () {
    var el = document.getElementById('movie_player') || document.querySelector('.html5-video-player') ||
      document.getElementById('player-container-id');
    if (!el) { return null; }
    var r = el.getBoundingClientRect();
    if (r.width < 10 || r.height < 10) { return null; }
    return { t: r.top, w: r.width, h: r.height, dpr: root.devicePixelRatio || 1, iw: root.innerWidth };
  };
})();
