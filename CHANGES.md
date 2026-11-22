# Cambios respecto a YTDLnis v1.9.1.1

Aviso de modificación (GPL‑3.0 §5a). Base: <https://github.com/deniscerri/ytdlnis> etiqueta
`v1.9.1.1` (commit `2bec7edf34801c4a633223023a229c2ad591776d`).

## Diseño
- Nuevo tema `Theme.Neon` (`values/neon_themes.xml`, `values/neon_colors.xml`): esquema Material 3
  oscuro con primario cian, acento naranja y superficies azul marino; siempre en modo oscuro.
- Fondo espacial (`drawable-nodpi/neon_space_bg.jpg`, `drawable/neon_window_bg.xml`).
- Tipografía Orbitron (SIL OFL 1.1, `font/neo_display.ttf`) en títulos y botones.
- Tarjetas con borde cian tenue y barra de navegación inferior azul profundo.
- Icono nuevo (vector adaptable + PNG heredados) y nombre "Neon Downloader".
- `ThemeUtil.kt`: acento `neon` por defecto y modo noche forzado; `App.kt` evita el parpadeo del
  primer arranque. Se añadió "Neón" a la lista de acentos; los demás acentos siguen disponibles.

## Funciones
- Sección **Calidad y velocidad** (`ui/more/settings/quality/QualitySettingsFragment.kt`,
  `xml/quality_preferences.xml`, entrada en `root_preferences.xml` y `settings_nav.xml`). Reusa las
  claves y la lógica de las pantallas Descargas/Procesamiento.
- Valores por defecto: 4 fragmentos concurrentes y 2 descargas simultáneas.
- Actualización automática de la app desactivada por defecto (el autor original publica otro paquete).

## Explorar y descargas
- Pestaña **Explorar** (`ui/explore/ExploreFragment.kt`, `YoutubeUrls.kt`, `res/layout/fragment_explore.xml`,
  `res/menu/explore_menu.xml`): YouTube en un WebView con buscador, suscripciones, biblioteca, inicio
  de sesión de Google opcional (sus cookies se pasan a yt-dlp) y botón de descarga.
- **Bloqueador de anuncios** (`ui/explore/AdBlocker.kt`, `res/raw/explore_adblock.js`): bloqueo de
  peticiones en `shouldInterceptRequest`, script al inicio del documento (`addDocumentStartJavaScript`)
  que oculta anuncios, limpia los datos de anuncios de las respuestas y salta los del reproductor.
- **Selector masivo** (`ui/explore/BulkSelectDialog.kt`, `BulkSelectViewModel.kt`, `BulkAdapter.kt`):
  lectura progresiva con yt-dlp (`ResultRepository.streamList`), búsqueda con exclusiones, filtros,
  orden y selección por rangos. `ResultItem` guarda en memoria (sin tocar la base de datos)
  `viewCount` y `uploadTimestamp`; `YTDLPUtil.getFromYTDL` acepta `approximateDates`.
- **Formato** (`ui/explore/DownloadKind.kt`): Vídeo / Audio MP3 / Audio original.
  `DownloadViewModel.turnResultItemsToProcessingDownloads` admite `forcedType` y `forcedAudioContainer`.
- **Nivel de descarga** (`database/viewmodel/DownloadProgressViewModel.kt`, `ActiveDownloadAdapter`,
  `ActiveDownloadsFragment`, `res/layout/active_download_card.xml`): porcentaje global del lote y
  porcentaje por tarjeta, a partir de `WorkerEventBus` y de los estados de la base de datos.
- **Lugar en la cola** (`util/QueuePlacement.kt`, `util/DownloadQueueUtil.kt`, `DownloadDao.kt`,
  `ui/downloads/QueuePlacementDialog.kt`, `QueuedDownloadsFragment.kt`): posición 1 = la siguiente en
  empezar. `DownloadRepository.startDownloadWorker` ya no fija una lista de prioridad con los 20
  primeros de cada tanda (solo para las programadas), de modo que manda el orden de la cola.
- **Escuchar solo audio en segundo plano** (`services/MusicService.kt`, `ui/explore/ExplorePlayer.kt`,
  botón "Escuchar" y barra del reproductor en `fragment_explore.xml`): servicio `MediaSessionService`
  (Media3 `media3-session`, tipo de servicio en primer plano `mediaPlayback`) con ExoPlayer. El audio se
  reproduce en streaming: la dirección directa de cada vídeo la pide yt-dlp justo antes de leerla
  (`YTDLPUtil.getAudioStreamUrl`, mediante `ResolvingDataSource`), se guarda 25 minutos y se pide por
  adelantado la de la pista siguiente. Si YouTube rechaza una dirección se reintenta una vez con otra
  nueva y, si la pista falla, se pasa a la siguiente. Permisos nuevos: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
  y `WAKE_LOCK`.
- **Botón de la esquina** (`fragment_explore.xml`, `ExploreActionsSheet.kt`, `sheet_explore_actions.xml`): los
  botones flotantes (Formato / Elegir vídeos / Escuchar / Descargar) tapaban los controles de YouTube en Shorts y
  vídeos; ahora hay un único botón en la barra superior con el formato debajo, que abre una hoja con todas las
  opciones de lo que se está viendo. La página se lee en el momento (`WebView.url`), así funciona al deslizar Shorts.
- **Todo tipo de contenido** (`YoutubeUrls.kt`): clips, publicaciones (imágenes/GIF y vídeo compartido),
  canciones y álbumes de YouTube Music, Shorts y directos; cualquier otra web pasa a yt-dlp. Pulsación larga
  sobre imágenes, GIF y stickers para guardarlos o compartirlos (`MediaSaver.kt`: OkHttp + MediaStore +
  FileProvider, tipo detectado por los primeros bytes; miniaturas de YouTube en máxima resolución).
- **Apartados** (`ExploreSections.kt`, `sheet_explore_sections.xml`): secciones de YouTube, películas y series
  gratis y legales (YouTube Películas y TV, Pluto TV, Tubi, Plex, ViX, FilminLatino, Internet Archive), apps de
  streaming instaladas (`<queries>` en el manifiesto) y GIF/stickers (Tenor, GIPHY). Explorar pasa a navegar
  por cualquier web (los enlaces de salida de YouTube siguen abriéndose fuera); se concede el permiso de
  contenido protegido (DRM, Widevine) para poder ver esos servicios.
- **Comentarios con GIF y stickers** (`CommentsViewer.kt`, `res/raw/explore_comments.js`): YouTube añadió GIF e
  imágenes a los comentarios (oct.-nov. 2026) pero su web móvil no los muestra; el visor abre el vídeo en la web
  de escritorio dejando solo los comentarios, con la misma sesión, el reproductor oculto y callado. Publicar GIF
  solo lo acepta la app oficial: acceso directo a ella.
- **Subida de archivos** (`onShowFileChooser`) en Explorar y en el visor de comentarios, e identidad de Chrome
  compartida (`ExploreBrowser.kt`; en modo escritorio los client hints también dicen escritorio).
- **Reproducción en segundo plano de Explorar** (`ExploreBackgroundPlayback.kt`, `services/ExploreMediaService.kt`,
  `res/raw/explore_background.js`): antes, al salir de la app Explorar llamaba a `WebView.onPause()`, YouTube pausaba
  al ver la página oculta y Android congelaba la app. Ahora la página siempre se cree visible (sin
  `visibilitychange`, con aviso de actividad para el "¿Sigues viendo?"), cuenta lo que suena por un canal de mensajes
  (`addWebMessageListener`; en WebView antiguos se le pregunta cada pocos segundos) y, al ocultarse la app con algo
  sonando, un servicio en primer plano `mediaPlayback` la mantiene viva: notificación de reproductor con MediaSession
  (pantalla de bloqueo, audífonos), bloqueos de CPU y Wi-Fi y el proceso de la página con prioridad alta. La pausa del
  navegador sigue a la app entera (no a la pestaña); al cambiar de pestaña, lo que sonaba de YouTube pasa a
  `MusicService` en el mismo segundo. Opción en el menú ⋮ (activada por defecto).
  Como en Brave: `ExploreWebView` no le cuenta al motor que la ventana se ocultó (Chromium pausa en Android los
  vídeos de una página oculta), la notificación aparece en cuanto algo suena (el servicio ya está en marcha al
  salir) y reproducir desde la notificación despierta la página aunque la app esté fuera.
- **Anterior / siguiente** en la notificación de segundo plano, la pantalla de bloqueo y los audífonos
  (`ExploreMediaService`, `explore_background.js`): API del reproductor de YouTube (`nextVideo`/`previousVideo`),
  sus botones, la lista del Mix o playlist y, en Shorts, desplazar la lista vertical.
- **Versión y novedades** (`ui/more/NeonUpdates.kt`, `sheet_neon_updates.xml`): `app/build.gradle` mete en BuildConfig
  el número de compilación de GitHub Actions, el commit, la rama y la fecha; la versión pasa a ser
  `1.9.1.1.<compilación>` y el versionCode sube con cada compilación. `scripts/ci/changelog.py` genera desde git el
  historial (`assets/neon_changelog.json`, no se sube al repositorio). Aviso "¡Actualizada!" al abrir una compilación
  distinta (`MainActivity`), fila en Más, opción en el menú de Explorar y comprobación de la última compilación
  publicada (las notas de cada Release llevan `neon-build:N`).
- **Giro y pantalla completa en Explorar** (`ExploreFragment.kt`, `res/raw/explore_watch.js`, `MainActivity`,
  manifiesto): al girar, Android recreaba la actividad y el WebView se destruía (la página se recargaba y YouTube
  volvía a empezar el vídeo en silencio). Ahora `MainActivity` declara `orientation|screenSize|screenLayout|
  smallestScreenSize` en `configChanges`: con Explorar a la vista el giro se resuelve en el sitio (el WebView no se
  toca) y en las demás pantallas se llama a `recreate()` como antes; si se giró en Explorar, la actividad se rehace
  al ir a otra pestaña (para sus diseños de horizontal). En horizontal, en un vídeo de YouTube, se esconden la barra
  de Explorar, la de navegación (`MainActivity.setFullscreen`, que también impide que `showBottomNavigation` la
  vuelva a enseñar) y las del sistema; el navegador se hace tan ancho como para que el reproductor (que en YouTube
  ocupa todo el ancho) tenga la altura de la pantalla, se centra y se sube lo que mida la cabecera (se mide en la
  página con `__neonPlayerRect`). El botón de pantalla completa de YouTube gira a horizontal
  (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`); "atrás" sale a vertical hasta que el teléfono esté derecho
  (`OrientationEventListener`), como en la app de YouTube. La pantalla completa de otras webs también oculta las
  barras. Si YouTube arranca un vídeo en silencio por haberse abierto sin un toque, se activa el sonido (botón
  `.ytp-unmute` y API del reproductor), salvo que el usuario lo haya silenciado (preferencia `yt-player-volume` o
  silencio justo después de un toque). Franja de descargas de una sola línea.
- **Letra de lo que suena** (`ui/lyrics/`: `LyricsDialog.kt`, `LyricsFinder.kt`, `YoutubeMusicLyrics.kt`,
  `NowPlaying.kt`; `dialog_lyrics.xml`, `item_lyrics_line.xml`, `lyrics_menu.xml`): pantalla completa con solo la
  letra, que sigue lo que suena en la página de Explorar (`ExploreBackgroundPlayback`) o en `MusicService` (por un
  `MediaController`) y cambia sola de canción. Fuentes: la API interna de YouTube Music (`next` para el nombre real,
  el tipo de vídeo y la pestaña "Letra"; `browse` con el cliente de Android para la letra con tiempos y con el web
  para el texto) y LRCLIB (`LrcLibUtil`, ya existente). Los tiempos solo se usan si valen para ese vídeo (la canción
  misma, o la misma duración ±3 s); si no, la letra se enseña como texto. Copiar, seleccionar texto, compartir,
  tamaño, ajuste de tiempo, tocar una línea para ir ahí, búsqueda manual en LRCLIB (la elección se recuerda por
  canción) y pantalla encendida mientras suena. La página cuenta ahora el milisegundo exacto, si está cargando y la
  velocidad, avisa al saltar (`seeked`, `waiting`, `playing`, `ratechange`) y acepta `seek:<ms>`; la notificación
  solo se vuelve a pintar si cambia algo visible o cada 4 s. Entradas: barra de "Escuchar", hoja del botón de la
  esquina, menú ⋮ de Explorar y Más. Se tradujeron al español los textos de letras de YTDLnis.
- Icono de cohete y planeta (vector adaptable, monocromo y PNG heredados).

## Proyecto
- `applicationId` → `com.diego.neondownloader` (accesos directos actualizados).
- Solo se compila `arm64-v8a`; archivos de salida `NeonDownloader-*.apk`.
- Compilación solo en GitHub Actions (`.github/workflows/build-apk.yml`, `scripts/ci/`): APK firmado en
  cada push y publicado en Releases (`apk-main` = Latest, `apk-<rama>` = prueba). Firma estable con la
  clave personal cifrada (`scripts/ci/neon-signing.env.enc`) y un único secreto (`NEON_FIRMA`). Diagnóstico aparte
  (no compila): `.github/workflows/probar-letras.yml` + `scripts/ci/probar_letras.py` comprueban que YouTube Music y
  LRCLIB siguen respondiendo como espera la app. Se reemplazaron los flujos de GitHub del original y
  se quitaron los README traducidos (el README original queda en `UPSTREAM-README.md`).
