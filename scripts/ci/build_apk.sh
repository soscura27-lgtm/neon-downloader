#!/usr/bin/env bash
# Compila una variante (por defecto githubRelease), localiza los APK generados y los copia a
# $APK_OUT_DIR (por defecto ./out). Lo usa .github/workflows/build-apk.yml (GitHub Actions es el
# único sitio donde se compila la app) y también sirve en una computadora.
# Uso: scripts/ci/build_apk.sh [variante]
set -euo pipefail

VARIANT="${1:-githubRelease}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

bash scripts/ci/prepare_signing.sh

# historial de cambios para "Versión y novedades" (se mete en el APK; no se sube al repositorio)
python3 scripts/ci/changelog.py "$ROOT/app/src/main/assets/neon_changelog.json" 80 ||
  echo "AVISO: no se generó el historial de cambios; la app lo mostrará vacío."

# githubRelease -> assembleGithubRelease
chmod +x ./gradlew 2>/dev/null || true
./gradlew ":app:assemble${VARIANT^}" --stacktrace

OUT="${APK_OUT_DIR:-$ROOT/out}"
mkdir -p "$OUT"
rm -f "$OUT"/*.apk

mapfile -t apks < <(find "$ROOT/app/build/outputs/apk" -name '*.apk' -type f 2>/dev/null | sort)
if ((${#apks[@]} == 0)); then
  echo "ERROR: Gradle terminó pero no hay ningún APK. Contenido de app/build/outputs:" >&2
  find "$ROOT/app/build/outputs" -type f 2>/dev/null | head -60 >&2
  exit 1
fi

APKSIGNER="$(ls -1 "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"

for apk in "${apks[@]}"; do
  cp "$apk" "$OUT/"
  echo "APK: $(basename "$apk")  $(du -h "$apk" | cut -f1)"
  if [[ -n "$APKSIGNER" ]]; then
    "$APKSIGNER" verify --print-certs "$apk" 2>&1 | grep -E "Signer #1 certificate (DN|SHA-256)" || true
  fi
done
echo "APK(s) copiados a $OUT"
