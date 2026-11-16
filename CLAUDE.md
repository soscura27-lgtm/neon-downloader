# Neon Downloader — memoria del proyecto

Reglas permanentes pedidas por el dueño del proyecto. Valen para cualquier sesión y cualquier tarea.

## Compilación: solo en GitHub
- **La app se compila únicamente en GitHub Actions** (`.github/workflows/build-apk.yml`). No usar
  Bitrise ni ningún otro servicio de CI, y no proponer compilar en otro sitio.
- Cada push compila el APK (`githubRelease`, arm64-v8a) y lo publica en Releases del repositorio:
  `apk-main` (marcada como Latest) para `main` y `apk-<rama>` (pre-release) para las demás ramas.
  Enlace fijo de la última versión de `main`:
  `https://github.com/soscura27-lgtm/neon-downloader/releases/latest/download/NeonDownloader.apk`.
- Para comprobar que un cambio compila: push a la rama de trabajo y revisar la ejecución en la
  pestaña Actions (herramientas de GitHub). Para recompilar sin cambios: "Run workflow" del mismo flujo.
- La firma estable usa los secretos del repositorio `NEON_KEYSTORE_B64`, `NEON_KEYSTORE_PASSWORD`,
  `NEON_KEY_ALIAS`, `NEON_KEY_PASSWORD` y, opcional, `NEON_KEYSTORE_SHA256`
  (`scripts/ci/prepare_signing.sh`). El repositorio es público: nunca subir claves ni contraseñas.

## Proyecto
- Fork personal de YTDLnis v1.9.1.1 (GPL-3.0) con tema Neon Space; paquete `com.diego.neondownloader`.
- Textos de la app, comentarios del código y mensajes de commit en español.
- Cada cambio propio se resume en `CHANGES.md` (aviso de modificación de la GPL) y, si se nota al
  usarla, en `README.md`.
- Explorar (YouTube, películas y series dentro de la app):
  `app/src/main/java/com/deniscerri/ytdl/ui/explore/`.
