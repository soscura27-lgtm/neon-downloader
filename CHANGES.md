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

## Proyecto
- `applicationId` → `com.diego.neondownloader` (accesos directos actualizados).
- Solo se compila `arm64-v8a`; archivos de salida `NeonDownloader-*.apk`.
- Firma estable por variables de entorno (sin claves en el repositorio), `bitrise.yml` y scripts de CI. Se eliminó `.github/` y los README
  traducidos del original (el README original queda en `UPSTREAM-README.md`).
