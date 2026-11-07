#!/bin/sh
# Make a signed mod package: out/packages/<id>-<version>.zip from mods/<id>
# (its build.sh first, if it has one), signed with the author's key.
#   tools/mkmodpkg.sh mods/<id> <author key>
# New key: java -cp out/engine-host hmgapi.engine.Signing keygen <key file>
set -e
cd "$(dirname "$0")/.."
M=${1:?usage: mkmodpkg.sh <mod dir> <key>}; KEY=${2:?usage: mkmodpkg.sh <mod dir> <key>}
ID=$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' "$M/mod.json" | head -1)
VER=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$M/mod.json" | head -1)
CP=out/engine-host
mkdir -p "$CP" && javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/$ID"
APPSRC=no; [ -f "$M/app/AndroidManifest.xml" ] && APPSRC=yes   # app/ is the mod's APK source: the package gets the built APK
for f in "$M"/*; do
    case "${f##*/}" in build.sh|test.sh|src|service|tools|native) ;; app) [ $APPSRC = yes ] || cp -r "$f" "$W/$ID/" ;; *) cp -r "$f" "$W/$ID/" ;; esac
done
if [ -d "$M/src" ] || [ -d "$M/service" ] || [ $APPSRC = yes ]; then ./build.sh mods >/dev/null || exit 1; fi
[ -d "$M/src" ] && cp "out/mods/$ID/hooks.jar" "$W/$ID/"
[ -d "$M/service" ] && cp "out/mods/$ID/service.jar" "$W/$ID/"
if [ $APPSRC = yes ]; then
    APK=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' "$M/mod.json")
    mkdir -p "$W/$ID/$(dirname "$APK")" && cp "out/mods/$ID/$APK" "$W/$ID/$APK" || { echo "no built $APK"; exit 1; }
fi
[ -x "$M/build.sh" ] && sh "$M/build.sh" "$W/$ID" >/dev/null
java -cp "$CP" hmgapi.engine.Signing sign "$W/$ID" "$KEY"
mkdir -p out/packages
Z=$PWD/out/packages/$ID-$VER.zip
rm -f "$Z"
# fixed order and times: the same mod and key give the same zip
(cd "$W/$ID" && find . -type f | sort | TZ=UTC xargs touch -d 2026-01-01T00:00:00 && find . -type f | sort | zip -q -X -D "$Z" -@)
java -cp "$CP" hmgapi.engine.Signing verify "$W/$ID"
echo "$Z"
