#!/usr/bin/env python3
"""
Genera el historial de cambios que enseña la app en "Versión y novedades".

Lee los últimos commits del repositorio (sus mensajes ya están escritos en español para la persona que
usa la app) y los guarda como JSON en app/src/main/assets/neon_changelog.json, que se mete en el APK.
El archivo no se sube al repositorio: se genera en cada compilación (scripts/ci/build_apk.sh).

Uso: scripts/ci/changelog.py <archivo de salida> [máximo de cambios]
"""
import json
import subprocess
import sys

FIELD = "\x1f"
RECORD = "\x1e"
# líneas de atribución que no le dicen nada a quien usa la app
SKIP_PREFIXES = ("Co-Authored-By:", "Claude-Session:", "Signed-off-by:")


def main() -> int:
    out_path = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 80
    fmt = FIELD.join(["%H", "%ct", "%s", "%b"]) + RECORD
    try:
        raw = subprocess.run(
            ["git", "log", f"--max-count={limit}", "--no-merges", f"--format={fmt}"],
            check=True, capture_output=True, text=True, encoding="utf-8",
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"AVISO: no se pudo leer el historial de git ({error}); la app lo mostrará vacío.")
        raw = ""

    entries = []
    for record in raw.split(RECORD):
        record = record.strip("\n")
        if not record.strip():
            continue
        parts = (record.split(FIELD) + ["", "", "", ""])[:4]
        sha, timestamp, subject, body = (part.strip() for part in parts)
        if not sha:
            continue
        lines = [line.rstrip() for line in body.splitlines() if not line.strip().startswith(SKIP_PREFIXES)]
        entries.append({
            "sha": sha,
            "time": int(timestamp or 0),
            "title": subject.replace("[skip ci]", "").strip(),
            "body": "\n".join(lines).strip(),
        })

    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(entries, handle, ensure_ascii=False, indent=1)
    print(f"Historial de cambios: {len(entries)} entradas en {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
