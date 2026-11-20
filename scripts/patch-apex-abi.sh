#!/bin/sh
# X9c port: a set's services_patched.jar with the guest's IApexService calls on the host's
# codes (patches/guest_apex_abi.py), the same jar surgery as retrofit-abi-codes.sh.
# Usage: scripts/patch-apex-abi.sh <set dir with services_patched.jar and guards.txt> <host table> <new dir>
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OLD=$(cd "$1" && pwd)
TABLE=$2
NEW=$3
APKTOOL=${APKTOOL:?APKTOOL=<apktool jar>}
MARK="# X9c port: IApexService calls on the host's codes ($(basename "$TABLE"), patches/guest_apex_abi.py)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
java -jar "$APKTOOL" d -q -f -r -o "$WORK/services" "$OLD/services_patched.jar"
DIRS=$(python3 -I "$ROOT/switcher/patches/guest_apex_abi.py" "$WORK/services" "$TABLE")
java -jar "$APKTOOL" b -q -o "$WORK/rebuilt.jar" "$WORK/services"
mkdir -p "$NEW"
cp "$OLD/services_patched.jar" "$NEW/services_patched.jar"
for d in $DIRS; do
    DEX="$(echo "$d" | sed 's/^smali_//; s/^smali$/classes/').dex"
    rm -rf "$WORK/dex" && mkdir -p "$WORK/dex"
    unzip -q -o "$WORK/rebuilt.jar" "$DEX" -d "$WORK/dex"
    (cd "$WORK/dex" && zip -q -0 "$NEW/services_patched.jar" "$DEX")
    echo "replaced $DEX"
done
python3 -I "$ROOT/scripts/dex-container-fix.py" "$OLD/services_patched.jar" "$WORK/rebuilt.jar" "$NEW/services_patched.jar"
SUM=$(sha256sum "$NEW/services_patched.jar" | cut -d' ' -f1)
OLDSUM=$(sha256sum "$OLD/services_patched.jar" | cut -d' ' -f1)
awk -v mark="$MARK" -v old="$OLDSUM" -v new="$SUM" 'NR == 1 { print; print mark; next }
    $1 == old && $2 == "services_patched.jar" { print new "  services_patched.jar"; next } { print }' \
    "$OLD/guards.txt" > "$NEW/guards.txt"
grep -q "^$SUM  services_patched.jar$" "$NEW/guards.txt" || { echo "guards.txt: services_patched.jar line not updated" >&2; exit 1; }
echo "$NEW: services_patched.jar $SUM (was $OLDSUM)"
