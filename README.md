# Neon Downloader

App personal de Android para descargar video y audio, con diseño **Neon Space** (azul profundo,
cian y naranja neón, tipografía Orbitron y fondo espacial). Está basada en
[YTDLnis](https://github.com/deniscerri/ytdlnis) **v1.9.1.1** (código abierto, GPL‑3.0), que usa
[yt-dlp](https://github.com/yt-dlp/yt-dlp) como motor.

- Sin anuncios y **sin sección VIP / suscripciones**: no existe en el proyecto.
- Calidad hasta **2160p / 1440p / 1080p / 720p…** (o "mejor calidad disponible") en cualquier descarga.
- Sección propia **Calidad y velocidad** (Ajustes → primera opción): calidad de video, bitrate de
  audio, hilos por descarga (fragmentos concurrentes), descargas simultáneas, aria2c, límite de
  velocidad y tamaño de búfer.
- Valores por defecto: calidad "mejor", 4 hilos por descarga y 2 descargas simultáneas.
- **Explorar**: pestaña con YouTube dentro de la app (buscador, suscripciones, biblioteca y, si
  quieres, tu cuenta de Google). Todas las opciones de descarga están en **un solo botón en la esquina
  de la barra superior** (con el formato debajo: VÍDEO, MP3 o AUDIO): nada flota encima de los Shorts
  ni de los vídeos.
- **Apartados** (botón de cuadritos): Inicio, Shorts, Suscripciones, Biblioteca, Historial, Ver más
  tarde, Me gusta, YouTube Music y **películas y series gratis y legales** dentro de la app: Películas y TV
  de YouTube, Pluto TV, Tubi, Plex, ViX, FilminLatino y cine y series clásicas de Internet Archive.
  También abre tus apps de streaming instaladas (Netflix, Disney+, Prime Video, Max, Crunchyroll...).
  Las webs con DRM se pueden ver; las que no lo tienen (Pluto TV, Tubi, Archive) también se descargan.
- **Descarga de todo tipo de contenido**: vídeos, Shorts, directos, clips, playlists, Mix, canales,
  canciones y álbumes de YouTube Music, publicaciones (sus imágenes y GIF, o el vídeo que comparten),
  miniaturas y cualquier web que entienda yt-dlp. Manteniendo pulsada cualquier imagen, GIF o sticker
  se puede guardar o **enviar** a WhatsApp, Telegram, etc.
- **Comentarios con GIF y stickers**: los que la web móvil de YouTube no muestra se ven en un visor
  propio (con tu sesión: puedes dar me gusta y responder). Enviar un GIF a un comentario solo lo acepta
  la app oficial de YouTube, así que hay un acceso directo a ella. Apartado de **GIF y stickers**
  (Tenor y GIPHY) para guardarlos o enviarlos, y subida de fotos/GIF desde el teléfono en las páginas que
  lo permiten.
- **Bloqueador de anuncios** propio en Explorar (peticiones de publicidad, anuncios en pantalla y
  salto de los anuncios del reproductor). Se activa y desactiva desde el menú ⋮.
- Descarga de **playlist o Mix completos** con un toque.
- **Selector masivo** para un canal (Vídeos / Shorts / Directos), una playlist o un Mix: buscar,
  filtrar por duración, fecha o "no descargados", ordenar y elegir todo, los primeros N, un rango
  ("del 20 al 80") o vídeo por vídeo.
- **Formato** a elegir en la hoja del botón de la esquina y en el selector: Vídeo, **Audio MP3** o audio original.
- **Nivel de descarga**: porcentaje global en Explorar y porcentaje en cada descarga activa.
- **Escuchar** en Explorar: solo el audio del vídeo, la playlist o el Mix que estés viendo, sin vídeo y en
  segundo plano (sigue sonando con la pantalla apagada, con notificación y controles). Se puede añadir
  a una cola de audio (ahora, a continuación o al final).
- **Sigue sonando en segundo plano, como en Brave**: lo que suena en Explorar (música o vídeo de YouTube, y también otras
  webs) no se corta al salir de la app ni al apagar la pantalla. Aparece una notificación con reproducir/pausar
  y cerrar, con controles en la pantalla de bloqueo y en los audífonos. Si cambias de pestaña dentro de la app,
  el audio pasa solo al reproductor de "Escuchar" desde el mismo segundo. Se activa y desactiva en el menú ⋮
  ("Seguir sonando en segundo plano").
- **Lugar en la cola**: al descargar puedes pedir "la siguiente" o "la posición 3", y en la pantalla
  de la cola puedes mover lo seleccionado a la posición que quieras.
- Instala aparte de YTDLnis original: su paquete es `com.diego.neondownloader`.

El resumen técnico de lo que cambió está en [CHANGES.md](CHANGES.md); los créditos y licencias en
[NOTICE.md](NOTICE.md).

## Compilar (GitHub Actions)

La app se compila **solo en GitHub**, con el flujo [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):

- Cada push compila el APK firmado (`githubRelease`, arm64‑v8a).
- El APK se publica en la pestaña **Releases** del repositorio:
  - `main` → la versión marcada como *Latest*. Enlace fijo para el celular:
    <https://github.com/soscura27-lgtm/neon-downloader/releases/latest/download/NeonDownloader.apk>
  - cualquier otra rama → una *pre-release* de prueba `apk-<rama>`.
- También queda en **Actions → la ejecución → Artifacts** durante 30 días.
- Para recompilar sin cambios: **Actions → Compilar APK → Run workflow**.

### Firma

Este repositorio es público, así que la clave de firma personal **no está legible en ningún sitio del
código**: viaja cifrada (AES‑256, PBKDF2 con 600 000 iteraciones) en
[`scripts/ci/neon-signing.env.enc`](scripts/ci/neon-signing.env.enc) y solo se puede abrir con la
contraseña guardada en el secreto del repositorio **`NEON_FIRMA`** (**Settings → Secrets and variables →
Actions → New repository secret**). `scripts/ci/prepare_signing.sh` la descifra al compilar, así cada
versión se firma igual que las anteriores y se instala encima sin perder nada.

Huella SHA‑256 del certificado: `3A:FC:3F:64:02:40:07:F4:4B:2E:6E:83:18:BE:92:3F:35:23:F1:C2:92:35:1B:EA:81:32:52:DB:CF:21:FA:54`.

Sin ese secreto la compilación sigue funcionando, pero firma con una clave de prueba distinta cada vez
(hay que desinstalar antes de actualizar). También se aceptan las variables sueltas `NEON_KEYSTORE_B64`,
`NEON_KEYSTORE_PASSWORD`, `NEON_KEY_ALIAS`, `NEON_KEY_PASSWORD` y `NEON_KEYSTORE_SHA256`.

## Compilar en tu computadora

Requiere JDK 17+ y el SDK de Android (plataforma 36):

```bash
export NEON_FIRMA='la contraseña de la clave'   # opcional: sin ella firma con una clave de prueba
bash scripts/ci/prepare_signing.sh
./gradlew :app:assembleGithubRelease
# APK: app/build/outputs/apk/github/release/
```

## Licencia

GPL‑3.0 (ver [LICENSE](LICENSE)). Es un fork personal: cualquier distribución del APK debe ofrecer
también este código fuente.
