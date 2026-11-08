#!/bin/sh
# Build one mod folder: hooks.jar from src/ (against HMG-API's runtime, compile only),
# the app APK from app/ (with hmgapi-client.jar inside), and the mod's *.json.
# build.sh mods runs it for every mod in this repo; in the mod SDK it's bin/build-mod.sh.
#   build-mod.sh <mod dir> <out dir> [work dir]
# Puts mod.json etc., hooks.jar and the APK (at the mod's first "apps" entry) in <out dir>;
# the compiled classes stay in <work dir>/classes (default: a temp dir) for mods that
# require this one (HMGAPI_MOD_CP).
# Env: HMGAPI_CP      the runtime to compile against (default: the SDK's lib/hmgapi-runtime.jar
#                     and lib/hmgapi-runtime-stubs.jar, beside this script)
#      HMGAPI_CLIENT  hmgapi-client.jar (default: the SDK's lib/hmgapi-client.jar)
#      HMGAPI_MOD_CP  other mods' classes, ':'-separated (mods this one requires)
#      HMGAPI_APP_KEY the .p12 the APK is signed with (password "example"; made here when missing)
#      ANDROID_HOME   the Android SDK (build-tools, a platform android.jar), as for build.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
M=${1:?usage: build-mod.sh <mod dir> <out dir> [work dir]}; O=${2:?usage: build-mod.sh <mod dir> <out dir> [work dir]}
M=${M%/}
. "$HERE/env.sh"
[ -n "$ANDROID_JAR" ] && [ -x "$D8" ] || { echo "build-mod: no Android SDK (set ANDROID_HOME: build-tools and a platform android.jar)"; exit 1; }
LIB=$HERE/../lib
CP=${HMGAPI_CP:-$LIB/hmgapi-runtime.jar:$LIB/hmgapi-runtime-stubs.jar}
CLIENT=${HMGAPI_CLIENT:-$LIB/hmgapi-client.jar}
W=$3
if [ -z "$W" ]; then W=$(mktemp -d); trap 'rm -rf "$W"' EXIT; fi
id=$(basename "$M")
mkdir -p "$O"
cp "$M"/*.json "$O/"
deps=${HMGAPI_MOD_CP:+$HMGAPI_MOD_CP:}
if [ -d "$M/src" ]; then
    rm -rf "$W/classes" && mkdir -p "$W/classes"
    # stubs/: signatures of the car's libraries an app part links against (compile only, never in hooks.jar)
    if [ -d "$M/stubs" ]; then
        rm -rf "$W/stubs" && mkdir -p "$W/stubs"
        javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$W/stubs" $(find "$M/stubs" -name '*.java')
        deps="$deps$W/stubs:"
    fi
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$CP:$deps" -d "$W/classes" $(find "$M/src" -name '*.java')
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" $(for d in $(echo "$CP:$deps" | tr ':' ' '); do printf -- '--classpath %s ' "$d"; done) \
        --output "$O/hooks.jar" $(find "$W/classes" -name '*.class')
fi
# service/: a Java service's sources, built to service.jar with hmgapi-client.jar inside (a "jar" service
# runs with only its own jar as CLASSPATH; this fork's mods/climate-creta)
if [ -d "$M/service" ]; then
    [ -f "$CLIENT" ] || { echo "build-mod: no $CLIENT"; exit 1; }
    rm -rf "$W/service" && mkdir -p "$W/service"
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$CLIENT" -d "$W/service" $(find "$M/service" -name '*.java')
    (cd "$W/service" && jar xf "$CLIENT" hmgapi)
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$O/service.jar" $(find "$W/service" -name '*.class')
    echo "mod $id: service.jar"
fi
# app/: the mod's own APK (AndroidManifest.xml, res/, java/), with hmgapi-client.jar inside; built to the
# mod's first "apps" entry. Signed with an example key (real mods sign their APK with their author's key).
if [ -f "$M/app/AndroidManifest.xml" ]; then
    [ -f "$CLIENT" ] || { echo "build-mod: no $CLIENT"; exit 1; }
    apk=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' "$M/mod.json")
    A=$W/app
    rm -rf "$A" && mkdir -p "$A/classes" "$O/$(dirname "$apk")"
    "$BT/aapt2" compile --dir "$M/app/res" -o "$A/res.zip"
    "$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$M/app/AndroidManifest.xml" --min-sdk-version 19 --target-sdk-version 19 \
        --java "$A/gen" -o "$A/base.apk" "$A/res.zip"
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$CLIENT" -d "$A/classes" \
        $(find "$M/app/java" "$A/gen" -name '*.java')
    (cd "$A/classes" && jar xf "$CLIENT" hmgapi)
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$A" $(find "$A/classes" -name '*.class')
    cp "$A/base.apk" "$A/unsigned.apk"
    (cd "$A" && zip -q -X unsigned.apk classes.dex)
    "$BT/zipalign" -f 4 "$A/unsigned.apk" "$A/aligned.apk"
    K=${HMGAPI_APP_KEY:-$W/example-app.p12}
    [ -f "$K" ] || keytool -genkeypair -keystore "$K" -storetype pkcs12 -storepass example -keypass example \
        -alias example -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HMG-API example mods" >/dev/null 2>&1
    "$BT/apksigner" sign --ks "$K" --ks-pass pass:example --min-sdk-version 9 \
        --v2-signing-enabled false --v3-signing-enabled false --out "$O/$apk" "$A/aligned.apk" 2>/dev/null
    echo "mod $id: app $apk"
fi
echo "mod $id"
