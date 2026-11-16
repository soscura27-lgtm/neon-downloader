#!/usr/bin/env bash
# Prepara la firma estable del APK SIN guardar ninguna clave legible en el repositorio.
#
# La clave personal viaja cifrada en scripts/ci/neon-signing.env.enc (AES-256, PBKDF2 con 600 000
# iteraciones). Para descifrarla basta UN secreto del repositorio en GitHub (Settings → Secrets and
# variables → Actions):
#   NEON_FIRMA              contraseña del archivo cifrado
#
# También se aceptan las variables sueltas (tienen prioridad sobre el archivo cifrado):
#   NEON_KEYSTORE_B64       keystore PKCS12 codificado en base64
#   NEON_KEYSTORE_SHA256    (opcional) huella SHA-256 del .p12 para detectar copias dañadas
#   NEON_KEYSTORE_PASSWORD  contraseña del keystore
#   NEON_KEY_ALIAS          alias de la clave (por defecto "neon")
#   NEON_KEY_PASSWORD       contraseña de la clave (por defecto la del keystore)
#
# Escribe keystore.properties (ignorado por Git), que build.gradle ya sabe usar.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENCRYPTED="$ROOT/scripts/ci/neon-signing.env.enc"

# 1) Clave cifrada del repositorio + contraseña NEON_FIRMA
if [[ -z "${NEON_KEYSTORE_B64:-}" && -n "${NEON_FIRMA:-}" ]]; then
  if [[ ! -f "$ENCRYPTED" ]]; then
    echo "ERROR: hay NEON_FIRMA pero falta $ENCRYPTED." >&2
    exit 1
  fi
  plain="$(mktemp)"
  trap 'rm -f "$plain"' EXIT
  if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 \
      -in "$ENCRYPTED" -out "$plain" -pass env:NEON_FIRMA 2>/dev/null; then
    echo "ERROR: NEON_FIRMA no es la contraseña de la clave cifrada (revisa el secreto en GitHub)." >&2
    exit 1
  fi
  # Solo se leen las variables conocidas; el contenido nunca se ejecuta.
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      NEON_KEYSTORE_B64|NEON_KEYSTORE_SHA256|NEON_KEYSTORE_PASSWORD|NEON_KEY_ALIAS|NEON_KEY_PASSWORD)
        export "$key=$value"
        ;;
    esac
  done < "$plain"
  rm -f "$plain"
  if [[ -z "${NEON_KEYSTORE_B64:-}" ]]; then
    echo "ERROR: la clave descifrada no trae NEON_KEYSTORE_B64 (¿NEON_FIRMA equivocada?)." >&2
    exit 1
  fi
  echo "Clave de firma descifrada."
fi

# 2) Variables sueltas (o las que acaba de descifrar el paso anterior)
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
