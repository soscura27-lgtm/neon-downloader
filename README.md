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
- Instala aparte de YTDLnis original: su paquete es `com.diego.neondownloader`.

El resumen técnico de lo que cambió está en [CHANGES.md](CHANGES.md); los créditos y licencias en
[NOTICE.md](NOTICE.md).

## Compilar (Bitrise)

La compilación corre en Bitrise con el archivo [`bitrise.yml`](bitrise.yml):

| Workflow      | Qué hace                                                                 |
|---------------|--------------------------------------------------------------------------|
| `build-apk`   | APK firmado (`githubRelease`, arm64‑v8a) + página pública de instalación |
| `check-debug` | Compila `githubDebug` para comprobar que todo funciona                   |

El APK sale en la pestaña **Artifacts** del build; abre su "página de instalación" desde el celular
para instalarlo.

### Firma

Este repositorio es público, así que **no contiene ninguna clave de firma**. La clave personal vive
solo en la configuración de la app en Bitrise (variables `NEON_KEYSTORE_B64`,
`NEON_KEYSTORE_PASSWORD`, `NEON_KEY_ALIAS` y `NEON_KEY_PASSWORD`) y
`scripts/ci/prepare_signing.sh` la usa al compilar. Así cada versión se firma igual y se instala
encima de la anterior. Sin esas variables la compilación sigue funcionando, pero firma con una clave
de depuración distinta cada vez (hay que desinstalar antes de actualizar).

## Compilar en tu computadora

Requiere JDK 17+ y el SDK de Android (plataforma 36):

```bash
bash scripts/ci/prepare_signing.sh
./gradlew :app:assembleGithubRelease
# APK: app/build/outputs/apk/github/release/
```

## Licencia

GPL‑3.0 (ver [LICENSE](LICENSE)). Es un fork personal: cualquier distribución del APK debe ofrecer
también este código fuente.
