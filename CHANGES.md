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
- Icono de cohete y planeta (vector adaptable, monocromo y PNG heredados).

## Proyecto
- `applicationId` → `com.diego.neondownloader` (accesos directos actualizados).
- Solo se compila `arm64-v8a`; archivos de salida `NeonDownloader-*.apk`.
- Firma estable por variables de entorno (sin claves en el repositorio), `bitrise.yml` y scripts de CI. Se eliminó `.github/` y los README
  traducidos del original (el README original queda en `UPSTREAM-README.md`).
