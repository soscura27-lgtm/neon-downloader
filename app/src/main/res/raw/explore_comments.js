/*
 * Neon Downloader - visor de comentarios (GIF, imágenes y stickers).
 *
 * Se ejecuta al empezar la página de escritorio de www.youtube.com/watch?v=... dentro del visor de
 * comentarios de Explorar. Deja visibles solo los comentarios, ajustados al ancho del teléfono, y mantiene
 * callado el reproductor (que queda oculto): los comentarios con GIF o imagen sí se ven en la web de
 * escritorio, mientras que la web móvil de YouTube todavía no los muestra.
 */
(function () {
  'use strict';

  if (window.__neonComments) { return; }
  try {
    Object.defineProperty(window, '__neonComments', { value: true });
  } catch (e) {
    window.__neonComments = true;
  }

  var HIDE = [
    '#masthead-container', 'ytd-masthead', '#guide', 'tp-yt-app-drawer#guide', 'ytd-mini-guide-renderer',
    '#player', '#player-container-outer', '#player-container-inner', '#player-full-bleed-container',
    '#full-bleed-container', '#cinematics', '#cinematics-container', 'ytd-player',
    'ytd-watch-metadata', '#above-the-fold', 'ytd-video-primary-info-renderer',
    'ytd-video-secondary-info-renderer', '#info-contents', '#meta-contents', '#ticket-shelf', '#merch-shelf',
    'ytd-merch-shelf-renderer', 'ytd-donation-shelf-renderer', '#donation-shelf', '#offer-module',
    '#secondary', '#secondary-inner', '#related', 'ytd-watch-next-secondary-results-renderer',
    '#chat-container', 'ytd-live-chat-frame', 'ytd-playlist-panel-renderer',
    'ytd-engagement-panel-section-list-renderer:not([target-id*="comment"])',
    'ytd-reel-shelf-renderer', 'yt-mealbar-promo-renderer', 'ytd-mealbar-promo-renderer',
    'tp-yt-paper-toast#toast', 'ytd-consent-bump-v2-lightbox'
  ];

  var CSS = HIDE.join(',\n') + '{display:none!important}\n' +
    'html,body{min-width:0!important;overflow-x:hidden!important}\n' +
    'ytd-app{--ytd-masthead-height:0px!important;min-width:0!important}\n' +
    'ytd-app #content,ytd-page-manager,#page-manager{margin-top:0!important;padding-top:0!important;min-width:0!important}\n' +
    'ytd-watch-flexy,ytd-watch-flexy[flexy]{min-width:0!important;' +
      '--ytd-watch-flexy-min-player-width:0px!important;--ytd-watch-flexy-max-player-width:100vw!important}\n' +
    'ytd-watch-flexy #columns,#columns.ytd-watch-flexy{display:block!important;margin:0!important;' +
      'padding:0!important;min-width:0!important;max-width:none!important}\n' +
    'ytd-watch-flexy #primary,#primary.ytd-watch-flexy{margin:0!important;padding:6px 12px 32px!important;' +
      'min-width:0!important;max-width:none!important;width:auto!important}\n' +
    '#primary-inner,#below,ytd-comments,ytd-comments#comments,#comments{min-width:0!important;' +
      'max-width:none!important;width:auto!important;margin-top:0!important}\n' +
    'ytd-comments-header-renderer{margin-top:4px!important}\n' +
    /* las imágenes y GIF de los comentarios nunca más anchos que la pantalla */
    'ytd-comments img,ytd-comments video{max-width:100%!important;height:auto}\n';

  function addStyle() {
    var parent = document.head || document.documentElement;
    if (!parent) { return false; }
    if (document.getElementById('neon-comments-style')) { return true; }
    var style = document.createElement('style');
    style.id = 'neon-comments-style';
    style.textContent = CSS;
    parent.appendChild(style);
    return true;
  }

  if (!addStyle()) {
    var waiter = new MutationObserver(function () {
      if (addStyle()) { waiter.disconnect(); }
    });
    waiter.observe(document, { childList: true, subtree: true });
  }

  // El reproductor queda oculto: que no suene ni gaste datos. Solo se toca el vídeo principal; los GIF de
  // los comentarios (que pueden ser vídeos cortos en bucle) siguen animándose.
  function quiet() {
    var player = document.getElementById('movie_player');
    if (player) {
      try {
        if (player.mute) { player.mute(); }
        if (player.getPlayerState) {
          var state = player.getPlayerState();
          if ((state === 1 || state === 3) && player.pauseVideo) { player.pauseVideo(); }
        }
      } catch (e) { /* el reproductor aún no está listo */ }
    }
    var main = document.querySelector('#movie_player video, video.html5-main-video');
    if (main) {
      try {
        main.muted = true;
        if (!main.paused) { main.pause(); }
      } catch (e) { /* ignorado */ }
    }
  }

  setInterval(function () {
    try {
      addStyle();
      quiet();
    } catch (e) { /* nunca romper la página */ }
  }, 400);
})();
