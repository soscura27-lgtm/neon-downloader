/*
 * Neon Downloader - bloqueador de anuncios del navegador "Explorar" (YouTube).
 *
 * Se ejecuta al empezar cada documento de *.youtube.com, antes que los scripts de la propia pagina
 * (WebViewCompat.addDocumentStartJavaScript). Usa las mismas ideas que uBlock Origin / AdGuard:
 *   1. Quita de las respuestas de YouTube los datos de anuncios (adPlacements, adSlots, playerAds)
 *      y las tarjetas publicitarias de las listas (inicio, busqueda, Shorts).
 *   2. Oculta los elementos de publicidad que aun asi aparezcan.
 *   3. Si un anuncio llega a reproducirse (p. ej. insertado en el propio flujo de video), lo
 *      silencia, lo acelera y pulsa "Omitir" en cuanto existe.
 *
 * Es un bloqueo "de mejor esfuerzo": YouTube cambia estas piezas con frecuencia.
 */
(function () {
  'use strict';

  var root = window;
  if (root.__neonAdblock) { return; }
  try {
    Object.defineProperty(root, '__neonAdblock', { value: true });
  } catch (e) {
    root.__neonAdblock = true;
  }

  var nativeParse = JSON.parse;
  var nativeStringify = JSON.stringify;

  // ---------------------------------------------------------------------------------------------
  // 1. Datos de anuncios dentro de las respuestas JSON
  // ---------------------------------------------------------------------------------------------

  // Claves con las que YouTube describe los anuncios de un video.
  var AD_KEYS = { adPlacements: 1, adSlots: 1, playerAds: 1 };

  // Tarjetas publicitarias dentro de listas (inicio, resultados, "siguiente", Shorts).
  var AD_ITEMS = {
    adSlotRenderer: 1,
    promotedSparklesWebRenderer: 1,
    promotedSparklesTextSearchRenderer: 1,
    promotedVideoRenderer: 1,
    compactPromotedVideoRenderer: 1,
    displayAdRenderer: 1,
    inFeedAdLayoutRenderer: 1,
    searchPyvRenderer: 1,
    videoMastheadAdV3Renderer: 1,
    brandVideoSingletonRenderer: 1,
    brandVideoShelfRenderer: 1,
    primetimePromoRenderer: 1,
    actionCompanionAdRenderer: 1
  };

  // Comprobacion rapida: si el texto no contiene nada de esto, no hace falta analizarlo.
  var MARKER = new RegExp(
    '"?(adPlacements|adSlots|playerAds)"?|' +
    '(adSlotRenderer|promotedSparklesWebRenderer|promotedSparklesTextSearchRenderer|' +
    'promotedVideoRenderer|compactPromotedVideoRenderer|displayAdRenderer|inFeedAdLayoutRenderer|' +
    'searchPyvRenderer|videoMastheadAdV3Renderer|brandVideoSingletonRenderer|' +
    'brandVideoShelfRenderer|primetimePromoRenderer|actionCompanionAdRenderer)|"isAd"\\s*:\\s*true'
  );
  var KEY_MARKER = /"(adPlacements|adSlots|playerAds)"/g;
  var ITEM_MARKER = new RegExp(
    'adSlotRenderer|promotedSparklesWebRenderer|promotedSparklesTextSearchRenderer|' +
    'promotedVideoRenderer|compactPromotedVideoRenderer|displayAdRenderer|inFeedAdLayoutRenderer|' +
    'searchPyvRenderer|videoMastheadAdV3Renderer|brandVideoSingletonRenderer|' +
    'brandVideoShelfRenderer|primetimePromoRenderer|actionCompanionAdRenderer|"isAd"\\s*:\\s*true'
  );

  function hasAdItemKey(o) {
    if (!o || typeof o !== 'object') { return false; }
    for (var k in o) {
      if (AD_ITEMS[k] === 1) { return true; }
    }
    return false;
  }

  function isAdItem(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) { return false; }
    if (hasAdItemKey(o)) { return true; }
    var c = o.richItemRenderer && o.richItemRenderer.content;
    if (hasAdItemKey(c)) { return true; }
    c = o.richSectionRenderer && o.richSectionRenderer.content;
    if (hasAdItemKey(c)) { return true; }
    // Shorts: los anuncios llegan como un elemento mas de la secuencia
    c = o.command && o.command.reelWatchEndpoint && o.command.reelWatchEndpoint.adClientParams;
    if (c && c.isAd === true) { return true; }
    return false;
  }

  /** Recorre el objeto y quita los anuncios. Devuelve el mismo objeto. */
  function pruneDeep(rootObject) {
    var budget = 400000; // tope de nodos por si llega algo enorme
    (function walk(o, depth) {
      if (!o || typeof o !== 'object' || depth > 80 || budget-- < 0) { return; }
      if (Array.isArray(o)) {
        for (var i = o.length - 1; i >= 0; i--) {
          var item = o[i];
          if (isAdItem(item)) {
            o.splice(i, 1);
          } else {
            walk(item, depth + 1);
          }
        }
        return;
      }
      for (var k in o) {
        if (AD_KEYS[k] === 1) {
          try { delete o[k]; } catch (e) { /* objeto congelado */ }
          continue;
        }
        var v = o[k];
        if (v && typeof v === 'object') { walk(v, depth + 1); }
      }
    })(rootObject, 0);
    return rootObject;
  }

  /** Version para texto JSON: primero renombra las claves de anuncios y, si hay tarjetas, las quita. */
  function rewriteJsonText(text) {
    var out = text.replace(KEY_MARKER, '"no_ads"');
    if (ITEM_MARKER.test(out)) {
      try {
        out = nativeStringify(pruneDeep(nativeParse(out)));
      } catch (e) { /* se deja el texto tal cual */ }
    }
    return out;
  }

  function hasMarker(text) {
    return typeof text === 'string' && text.length > 20 && MARKER.test(text);
  }

  // Respuestas de la API interna de YouTube que pueden traer anuncios.
  var API_URL = /\/youtubei\/v1\/(player|next|browse|search|reel|get_watch|watch)|[?&]pbj=1/;

  function isApiUrl(url) {
    return typeof url === 'string' && API_URL.test(url);
  }

  // Variables globales con las que YouTube arranca cada pagina: se limpian al asignarlas.
  function trapGlobal(name) {
    try {
      var value = root[name];
      Object.defineProperty(root, name, {
        configurable: true,
        enumerable: true,
        get: function () { return value; },
        set: function (v) {
          try { value = pruneDeep(v); } catch (e) { value = v; }
        }
      });
      if (value) { pruneDeep(value); }
    } catch (e) { /* la pagina ya la definio de forma no configurable */ }
  }
  trapGlobal('ytInitialPlayerResponse');
  trapGlobal('ytInitialData');
  trapGlobal('ytInitialReelWatchSequenceResponse');

  // JSON.parse: cubre los datos que YouTube recibe como texto y convierte despues.
  try {
    JSON.parse = function (text, reviver) {
      var result = nativeParse.apply(this, arguments);
      try {
        if (hasMarker(text)) { pruneDeep(result); }
      } catch (e) { /* nunca romper la pagina */ }
      return result;
    };
  } catch (e) { /* ignorado */ }

  // fetch: las llamadas a youtubei/v1/* devuelven los datos ya sin anuncios.
  var nativeFetch = root.fetch;
  if (typeof nativeFetch === 'function') {
    root.fetch = function (input, init) {
      var promise = nativeFetch.apply(this, arguments);
      var url = '';
      try {
        url = typeof input === 'string' ? input : (input && input.url) || String(input);
      } catch (e) { /* sin url */ }
      if (!isApiUrl(url)) { return promise; }
      return promise.then(function (response) {
        try {
          var type = (response.headers && response.headers.get('content-type')) || '';
          if (type && type.indexOf('json') === -1) { return response; }
          return response.clone().text().then(function (text) {
            if (!hasMarker(text)) { return response; }
            var patched = new Response(rewriteJsonText(text), {
              status: response.status,
              statusText: response.statusText,
              headers: response.headers
            });
            try { Object.defineProperty(patched, 'url', { value: response.url }); } catch (e) { /* ignorado */ }
            return patched;
          }, function () { return response; });
        } catch (e) {
          return response;
        }
      });
    };
  }

  // XMLHttpRequest: lo mismo para las llamadas antiguas.
  var XHR = root.XMLHttpRequest;
  if (XHR && XHR.prototype) {
    var proto = XHR.prototype;
    var nativeOpen = proto.open;
    proto.open = function (method, url) {
      try {
        this.__neonUrl = String(url);
        this.__neonSrc = null;
        this.__neonOut = null;
        this.__neonObj = null;
      } catch (e) { /* sin url */ }
      return nativeOpen.apply(this, arguments);
    };
    ['responseText', 'response'].forEach(function (prop) {
      var descriptor = Object.getOwnPropertyDescriptor(proto, prop);
      if (!descriptor || !descriptor.get) { return; }
      Object.defineProperty(proto, prop, {
        configurable: true,
        enumerable: descriptor.enumerable,
        get: function () {
          var value = descriptor.get.call(this);
          try {
            if (this.readyState === 4 && isApiUrl(this.__neonUrl)) {
              if (typeof value === 'string') {
                if (this.__neonSrc === value) { return this.__neonOut; }
                if (!hasMarker(value)) { return value; }
                var rewritten = rewriteJsonText(value);
                this.__neonSrc = value;
                this.__neonOut = rewritten;
                return rewritten;
              }
              if (value && typeof value === 'object' && this.responseType === 'json') {
                // Chrome entrega un objeto nuevo en cada lectura: se limpia una vez y se reutiliza
                if (!this.__neonObj) {
                  pruneDeep(value);
                  this.__neonObj = value;
                }
                return this.__neonObj;
              }
            }
          } catch (e) { /* devolver el valor original */ }
          return value;
        }
      });
    });
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Ocultar lo que quede de publicidad
  // ---------------------------------------------------------------------------------------------

  var HIDE = [
    '#masthead-ad', '#player-ads',
    'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-display-ad-renderer',
    'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-sparkles-text-search-renderer',
    'ytd-promoted-video-renderer', 'ytd-compact-promoted-video-renderer',
    'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer', 'ytd-banner-promo-renderer',
    'ytd-brand-video-singleton-renderer', 'ytd-brand-video-shelf-renderer', 'ytd-primetime-promo-renderer',
    'ytd-statement-banner-renderer', 'ytd-mealbar-promo-renderer',
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
    'ytm-promoted-video-renderer', 'ytm-compact-promoted-video-renderer', 'ytm-companion-slot',
    'ytm-companion-ad-renderer', 'ytm-display-ad-renderer', 'ytm-ad-slot-renderer',
    'ytm-in-feed-ad-layout-renderer', 'ytm-brand-video-singleton-renderer',
    'ytm-brand-video-shelf-renderer', 'ytm-mealbar-promo-renderer', 'ytm-banner-promo-renderer',
    'ad-slot-renderer', 'in-feed-ad-layout-renderer',
    '.ytp-ad-overlay-container', '.ytp-ad-overlay-slot', '.ytp-ad-image-overlay',
    '.ytp-ad-text-overlay', '.ytp-ad-message-container'
  ];
  // Reglas con :has() en bloque aparte: si el navegador no las entiende no invalidan las demas.
  var HIDE_HAS = [
    'ytd-rich-item-renderer:has(> #content > ytd-ad-slot-renderer)',
    'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)',
    'ytd-rich-section-renderer:has(ytd-statement-banner-renderer)',
    'ytd-reel-video-renderer:has(ad-slot-renderer)',
    'ytm-rich-item-renderer:has(ad-slot-renderer)',
    'ytm-rich-item-renderer:has(ytm-ad-slot-renderer)',
    'ytm-item-section-renderer:has(ytm-promoted-sparkles-web-renderer)',
    'ytm-item-section-renderer:has(ad-slot-renderer)',
    'ytm-item-section-renderer:has(ytm-promoted-video-renderer)'
  ];

  function addStyle() {
    var parent = document.head || document.documentElement;
    if (!parent) { return false; }
    var style = document.createElement('style');
    style.id = 'neon-adblock-style';
    style.textContent =
      HIDE.join(',\n') + '{display:none!important}\n' +
      HIDE_HAS.map(function (s) { return s + '{display:none!important}'; }).join('\n');
    parent.appendChild(style);
    return true;
  }

  if (!addStyle()) {
    // el documento aun no tiene <html>: se espera a que aparezca
    var waiter = new MutationObserver(function () {
      if (addStyle()) { waiter.disconnect(); }
    });
    waiter.observe(document, { childList: true, subtree: true });
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Si un anuncio llega a reproducirse: silenciar, acelerar y omitir
  // ---------------------------------------------------------------------------------------------

  var SKIP_SELECTOR = [
    '.ytp-skip-ad-button', '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern',
    '.ytp-ad-skip-button-container button', '.ytp-ad-overlay-close-button',
    '.ytp-ad-survey-answer-button'
  ].join(',');

  var saved = null;

  function skipAds() {
    var player = document.querySelector('.html5-video-player');
    var video = document.querySelector('video.html5-main-video') || document.querySelector('video');
    if (!player || !video) { return; }

    var showing = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
    if (showing) {
      var button = document.querySelector(SKIP_SELECTOR);
      if (button) { button.click(); }
    }

    if (player.classList.contains('ad-interrupting')) {
      if (!saved) { saved = { muted: video.muted, rate: video.playbackRate }; }
      video.muted = true;
      if (video.playbackRate !== 16) { video.playbackRate = 16; }
    } else if (saved) {
      video.muted = saved.muted;
      video.playbackRate = saved.rate;
      saved = null;
    }
  }

  // Aviso de "los bloqueadores de anuncios no estan permitidos": se cierra y se reanuda el video.
  function closeEnforcement() {
    var message = document.querySelector('ytd-enforcement-message-view-model, ytm-enforcement-message-view-model');
    if (!message) { return; }
    var dialog = message.closest('tp-yt-paper-dialog, dialog') || message;
    dialog.remove();
    var backdrop = document.querySelector('tp-yt-iron-overlay-backdrop');
    if (backdrop) { backdrop.remove(); }
    var video = document.querySelector('video');
    if (video && video.paused) {
      var played = video.play();
      if (played && played.catch) { played.catch(function () { /* se necesita un toque */ }); }
    }
  }

  setInterval(function () {
    try {
      skipAds();
      closeEnforcement();
    } catch (e) { /* nunca romper la pagina */ }
  }, 300);
})();
