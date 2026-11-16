#!/usr/bin/env bash
# Publica el APK recién compilado en una Release de GitHub de la rama. La Release de cada rama se
# reemplaza en cada compilación, así siempre hay una sola descarga, la más nueva:
#   main  -> etiqueta apk-main, marcada como "Latest"
#            (enlace fijo: https://github.com/<repo>/releases/latest/download/NeonDownloader.apk)
#   otras -> etiqueta apk-<rama>, como pre-release de prueba
#
# Lo ejecuta .github/workflows/build-apk.yml después de scripts/ci/build_apk.sh. Necesita GH_TOKEN
# (el token de la ejecución, con permiso contents: write) y las variables estándar de GitHub Actions.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${APK_OUT_DIR:-$ROOT/out}"
: "${GITHUB_REPOSITORY:?Solo se ejecuta dentro de GitHub Actions}"
: "${GITHUB_REF_NAME:?}"
: "${GITHUB_SHA:?}"

mapfile -t apks < <(find "$OUT" -maxdepth 1 -name '*.apk' -type f 2>/dev/null | sort)
if ((${#apks[@]} == 0)); then
  echo "ERROR: no hay ningún APK en $OUT para publicar." >&2
  exit 1
fi
# Solo se compila arm64-v8a: hay un único APK.
apk="${apks[0]}"

branch="$GITHUB_REF_NAME"
tag="apk-${branch//\//-}"
asset_dir="${RUNNER_TEMP:-$(mktemp -d)}"
asset="$asset_dir/NeonDownloader.apk"
cp "$apk" "$asset"

version="$(basename "$apk" .apk)"
version="${version#NeonDownloader-}"
short_sha="${GITHUB_SHA:0:7}"
subject="$(git -C "$ROOT" log -1 --format=%s "$GITHUB_SHA" 2>/dev/null || echo "")"
built_at="$(date -u '+%Y-%m-%d %H:%M') UTC"
server="${GITHUB_SERVER_URL:-https://github.com}"
run_url="$server/$GITHUB_REPOSITORY/actions/runs/${GITHUB_RUN_ID:-}"
download_url="$server/$GITHUB_REPOSITORY/releases/download/$tag/NeonDownloader.apk"

if [[ "${SIGNED:-false}" == "true" ]]; then
  signature="Firma estable: se instala encima de la versión anterior sin perder nada."
else
  signature="⚠️ **Firma de prueba**: falta el secreto NEON_FIRMA en el repositorio (Settings → Secrets and variables → Actions). Este APK NO se instala encima de la app que ya tienes (habría que desinstalarla antes y se perderían los ajustes). Añade el secreto y vuelve a compilar."
fi

if [[ "$branch" == "main" ]]; then
  title="Neon Downloader $version"
  flags=(--latest)
else
  title="Neon Downloader $version · prueba ($branch)"
  flags=(--prerelease)
fi

notes="$(cat <<EOF
**Descarga directa:** [NeonDownloader.apk]($download_url)

- **Versión:** $version
- **Cambio:** \`$short_sha\` $subject
- **Compilado:** $built_at · [registro de la compilación]($run_url)
- $signature

Para instalar: abre el enlace desde el celular, descarga el APK y ábrelo (Android pedirá permitir
instalar desde el navegador la primera vez).
EOF
)"

# Se borra la Release anterior de esta rama (y su etiqueta) y se crea de nuevo apuntando a este commit.
gh release delete "$tag" --repo "$GITHUB_REPOSITORY" --yes --cleanup-tag >/dev/null 2>&1 || true
create_release() {
  gh release create "$tag" "$asset" \
    --repo "$GITHUB_REPOSITORY" \
    --target "$1" \
    --title "$title" \
    --notes "$notes" \
    "${flags[@]}"
}
# GitHub no deja al token de la ejecución crear una etiqueta en un commit cuyo flujo de trabajo ya no
# coincide con el de ninguna rama (pasa si se subió un cambio del flujo mientras esto compilaba).
# En ese caso la etiqueta se crea en la punta de la rama; las notas siguen indicando el commit compilado.
if ! create_release "$GITHUB_SHA"; then
  echo "AVISO: no se pudo etiquetar $short_sha; se publica en la punta de la rama $branch."
  gh release delete "$tag" --repo "$GITHUB_REPOSITORY" --yes --cleanup-tag >/dev/null 2>&1 || true
  create_release "$branch"
fi

echo "APK publicado: $download_url"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### APK listo"
    echo
    echo "**$title**"
    echo
    echo "- Descarga directa: $download_url"
    echo "- $signature"
  } >> "$GITHUB_STEP_SUMMARY"
fi
