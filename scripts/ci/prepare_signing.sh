#!/usr/bin/env bash
# Prepara la firma estable del APK SIN guardar ninguna clave en el repositorio.
#
# En CI lee estas variables de entorno (definidas en la configuración de Bitrise):
#   NEON_KEYSTORE_B64       keystore PKCS12 codificado en base64
#   NEON_KEYSTORE_SHA256    (opcional) huella SHA-256 del .p12 para detectar copias dañadas
#   NEON_KEYSTORE_PASSWORD  contraseña del keystore
#   NEON_KEY_ALIAS          alias de la clave (por defecto "neon")
#   NEON_KEY_PASSWORD       contraseña de la clave (por defecto la del keystore)
# y escribe keystore.properties (ignorado por Git), que build.gradle ya sabe usar.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -n "${NEON_KEYSTORE_B64:-}" ]]; then
  mkdir -p "$ROOT/signing"
  printf '%s' "$NEON_KEYSTORE_B64" | base64 -d > "$ROOT/signing/neon-personal.p12"
  if [[ -n "${NEON_KEYSTORE_SHA256:-}" ]]; then
    actual="$(sha256sum "$ROOT/signing/neon-personal.p12" | cut -d' ' -f1)"
    if [[ "$actual" != "$NEON_KEYSTORE_SHA256" ]]; then
      echo "ERROR: el keystore decodificado no coincide con NEON_KEYSTORE_SHA256 (variable mal copiada)." >&2
      exit 1
    fi
  fi
  {
    echo "signingConfig.storeFile=$ROOT/signing/neon-personal.p12"
    echo "signingConfig.storePassword=${NEON_KEYSTORE_PASSWORD:?Falta NEON_KEYSTORE_PASSWORD}"
    echo "signingConfig.keyAlias=${NEON_KEY_ALIAS:-neon}"
    echo "signingConfig.keyPassword=${NEON_KEY_PASSWORD:-$NEON_KEYSTORE_PASSWORD}"
  } > "$ROOT/keystore.properties"
  echo "Firma estable configurada."
elif [[ -f "$ROOT/keystore.properties" ]]; then
  echo "Usando keystore.properties local."
else
  echo "AVISO: no hay clave de firma; se usará la clave de depuración de esta máquina."
  echo "       La firma cambiará en cada compilación y no podrás actualizar encima de una instalación previa."
fi
