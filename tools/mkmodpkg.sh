#!/bin/sh
# Make a signed mod package: out/packages/<id>-<version>.zip from mods/<id>
# (its build.sh first, if it has one), signed with the author's key.
#   tools/mkmodpkg.sh mods/<id> <author key>
# New key: java -cp out/engine-host hmgapi.engine.Signing keygen <key file>
# In the mod SDK (bin/mkmodpkg.sh), the mod dir is relative to where it's run, the
# package goes to $OUT/packages (default ./out), and the mod is built with
# bin/build-mod.sh, or taken as built from HMGAPI_MOD_BUILD (build-mod.sh's out dir).
# New key there: java -cp <sdk>/lib/hmgapi-engine.jar hmgapi.engine.Signing keygen <key file>
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
M=${1:?usage: mkmodpkg.sh <mod dir> <key>}; KEY=${2:?usage: mkmodpkg.sh <mod dir> <key>}
M=${M%/}
if [ -f "$HERE/../lib/hmgapi-engine.jar" ]; then   # the mod SDK
    SDK=yes CP=$HERE/../lib/hmgapi-engine.jar PKGS=${OUT:-$PWD/out}/packages
    case $KEY in /*) ;; *) KEY=$PWD/$KEY ;; esac
else
    SDK=no CP=out/engine-host
    cd "$HERE/.."
    PKGS=$PWD/out/packages
    mkdir -p "$CP" && javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
fi
ID=$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' "$M/mod.json" | head -1)
VER=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$M/mod.json" | head -1)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/$ID"
APPSRC=no; [ -f "$M/app/AndroidManifest.xml" ] && APPSRC=yes   # app/ is the mod's APK source: the package gets the built APK
for f in "$M"/*; do
    case "${f##*/}" in build.sh|test.sh|src|service|tools|native) ;; app) [ $APPSRC = yes ] || cp -r "$f" "$W/$ID/" ;; *) cp -r "$f" "$W/$ID/" ;; esac
done
if [ -d "$M/src" ] || [ -d "$M/service" ] || [ $APPSRC = yes ]; then
    if [ $SDK = no ]; then ./build.sh mods >/dev/null || exit 1; B=out/mods/$ID
    elif [ -n "$HMGAPI_MOD_BUILD" ]; then B=$HMGAPI_MOD_BUILD
    else B=$W/build; sh "$HERE/build-mod.sh" "$M" "$B" "$W/work" >/dev/null || exit 1
    fi
fi
[ -d "$M/src" ] && cp "$B/hooks.jar" "$W/$ID/"
[ -d "$M/service" ] && cp "$B/service.jar" "$W/$ID/"
if [ $APPSRC = yes ]; then
    APK=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' "$M/mod.json")
    mkdir -p "$W/$ID/$(dirname "$APK")" && cp "$B/$APK" "$W/$ID/$APK" || { echo "no built $APK"; exit 1; }
fi
[ -x "$M/build.sh" ] && sh "$M/build.sh" "$W/$ID" >/dev/null
java -cp "$CP" hmgapi.engine.Signing sign "$W/$ID" "$KEY"
mkdir -p "$PKGS"
Z=$PKGS/$ID-$VER.zip
rm -f "$Z"
# fixed order and times: the same mod and key give the same zip
(cd "$W/$ID" && find . -type f | sort | TZ=UTC xargs touch -d 2026-01-01T00:00:00 && find . -type f | sort | zip -q -X -D "$Z" -@)
java -cp "$CP" hmgapi.engine.Signing verify "$W/$ID"
echo "$Z"
