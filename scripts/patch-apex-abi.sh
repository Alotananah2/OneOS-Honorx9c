#!/bin/sh
# X9c port: a jar with the guest's IApexService calls on the host's codes
# (patches/guest_apex_abi.py); only the dex files with a changed class are replaced.
# Usage: APKTOOL=<apktool jar> scripts/patch-apex-abi.sh <in jar> <host table> <out jar>
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
IN=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
TABLE=$2
OUT=$(cd "$(dirname "$3")" && pwd)/$(basename "$3")
APKTOOL=${APKTOOL:?APKTOOL=<apktool jar>}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
java -Xmx6g -jar "$APKTOOL" d -q -f -r -o "$WORK/jar" "$IN"
DIRS=$(python3 -I "$ROOT/switcher/patches/guest_apex_abi.py" "$WORK/jar" "$TABLE")
java -Xmx6g -jar "$APKTOOL" b -q -o "$WORK/rebuilt.jar" "$WORK/jar"
cp "$IN" "$OUT"
for d in $DIRS; do
    DEX="$(echo "$d" | sed 's/^smali_//; s/^smali$/classes/').dex"
    rm -rf "$WORK/dex" && mkdir -p "$WORK/dex"
    unzip -q -o "$WORK/rebuilt.jar" "$DEX" -d "$WORK/dex"
    (cd "$WORK/dex" && zip -q -0 "$OUT" "$DEX")
    echo "replaced $DEX"
done
python3 -I "$ROOT/scripts/dex-container-fix.py" "$IN" "$WORK/rebuilt.jar" "$OUT"
echo "$OUT: $(sha256sum "$OUT" | cut -d' ' -f1)"
