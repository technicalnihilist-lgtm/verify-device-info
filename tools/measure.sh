#!/usr/bin/env bash
# Records the numbers behind "smaller" and "faster" claims, so no improvement rests on an adjective.
#
#   tools/measure.sh                 # measure the debug APK that is already built
#   tools/measure.sh --build         # time a debug build first, then measure it
#   tools/measure.sh --apk <path>    # measure a specific APK (e.g. a release one)
#   tools/measure.sh --print         # print the row, do not touch docs/PERFORMANCE.md
#
# What it reports: APK size in bytes, the method count and class count of the merged dex files, and
# (with --build) the wall-clock time of the Gradle invocation. Method and class counts come from the
# DEX headers themselves, so nothing beyond unzip and python3 is needed — no SDK tool, no dexdump.
#
# The row is appended to docs/PERFORMANCE.md in a fixed column order, so two runs on two days are
# directly comparable. Exit status: 0 measured, 2 nothing to measure, 1 build or measurement failed.
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
OUT="$ROOT/docs/PERFORMANCE.md"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
BUILD=0
PRINT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --build) BUILD=1 ;;
    --print) PRINT=1 ;;
    --apk)
      shift
      [ $# -gt 0 ] || { echo "measure: --apk needs a path" >&2; exit 2; }
      APK=$1
      ;;
    -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "measure: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT" || exit 2

build_seconds=""
if [ "$BUILD" -eq 1 ]; then
  command -v gradle >/dev/null 2>&1 || { echo "measure: gradle is not on PATH" >&2; exit 1; }
  echo "==> timing gradle assembleDebug" >&2
  start=$(date +%s)
  if ! gradle assembleDebug --console=plain; then
    echo "measure: gradle assembleDebug failed, nothing recorded" >&2
    exit 1
  fi
  build_seconds=$(( $(date +%s) - start ))
fi

[ -f "$APK" ] || {
  echo "measure: no APK at $APK — build one first (gradle assembleDebug) or pass --apk" >&2
  exit 2
}

apk_bytes=$(wc -c < "$APK" | tr -d ' ')
dex_tmp=$(mktemp -d) || exit 1
trap 'rm -rf "$dex_tmp"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$dex_tmp" 2>/dev/null
[ -n "$(find "$dex_tmp" -name 'classes*.dex' -print -quit)" ] || {
  echo "measure: $APK contains no dex files" >&2
  exit 1
}

# DEX header: magic[8], then uint32 fields little-endian; class_defs_size at 0x60, method_ids_size
# at 0x58. Summed over every classesN.dex because minSdk 26 packages native multidex.
counts=$(python3 - "$dex_tmp" <<'PY'
import glob, os, struct, sys
total_methods = total_classes = total_dex = 0
files = sorted(glob.glob(os.path.join(sys.argv[1], "classes*.dex")))
for path in files:
    with open(path, "rb") as fh:
        header = fh.read(0x70)
    if header[:4] != b"dex\n" or len(header) < 0x70:
        continue
    total_dex += os.path.getsize(path)
    total_classes += struct.unpack_from("<I", header, 0x60)[0]
    total_methods += struct.unpack_from("<I", header, 0x58)[0]
print(f"{total_methods} {total_classes} {len(files)} {total_dex}")
PY
) || { echo "measure: could not read the dex headers" >&2; exit 1; }

read -r methods classes dex_files dex_bytes <<<"$counts"

date_utc=$(date -u +%Y-%m-%d)
row="| $date_utc | $(basename "$APK") | $apk_bytes | $methods | $classes | $dex_files | $dex_bytes | ${build_seconds:--} |"

if [ "$PRINT" -eq 1 ]; then
  echo "$row"
  exit 0
fi

if [ ! -f "$OUT" ]; then
  cat > "$OUT" <<'MD'
# Performance measurements

Numbers, not adjectives. Every row is produced by `tools/measure.sh`, so any claim of "smaller" or
"faster" in the backlog can be reproduced with one command:

    tools/measure.sh            # measure the debug APK that is already built
    tools/measure.sh --build    # time a debug build, then measure what it produced

| date (UTC) | apk | apk bytes | dex methods | dex classes | dex files | dex bytes | build seconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
MD
fi

printf '%s\n' "$row" >> "$OUT"
echo "measure: appended to ${OUT#$ROOT/}" >&2
printf '%s\n' "$row"
