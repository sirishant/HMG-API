#!/bin/sh
# Build HMG-API into out/.
#   ./build.sh            everything
#   ./build.sh <part>     hmgapid | zygote | loader | runtime | client | app | mods | lpatch | payload
# payload (out/payload): everything install/common/install.sh puts on the unit,
# with a MANIFEST of md5s; every installer (adb, NEMESIS, USB stick) carries it.
# Needs clang + lld, a JDK and the Android SDK (build-tools, a platform android.jar).
# The app is signed with HMG-API's key (HMGAPI_KEY, a .p12 with a .pass beside it);
# without it, with a throwaway key (fine for tests, not for a release).
set -e
cd "$(dirname "$0")"
REPO=$PWD
. tools/env.sh
OUT=${OUT:-$REPO/out}
mkdir -p "$OUT"

hmgapid() { OUT="$OUT" sh core/hmgapid/build.sh; }

zygote() {
    clang $ARM_STATIC -Wall -o "$OUT/app_process" core/zygote/zygote.c
    md5sum "$OUT/app_process"
}

loader() {
    # against stand-ins for the head unit's libc/liblog/libdl (core/loader/syslib-stubs.sh: the same
    # bytes as linking the unit's own; KitKat linker: DT_HASH, no GNU hash)
    sh core/loader/syslib-stubs.sh "$OUT/syslib" >/dev/null
    clang --target=armv7a-linux-androideabi -mthumb -march=armv7-a -mfloat-abi=softfp -Os -fPIC \
      -ffreestanding -fno-builtin -nostdlib -shared -fno-stack-protector -fuse-ld=lld \
      -Wl,--hash-style=sysv -Wl,-z,defs -Wl,--build-id=none -Wl,-soname,libhmgapi_hook.so \
      -o "$OUT/libhmgapi_hook.so" core/loader/loader.c "$OUT/syslib/libc.so" "$OUT/syslib/liblog.so" "$OUT/syslib/libdl.so"
    md5sum "$OUT/libhmgapi_hook.so"
}

runtime() {
    rm -rf "$OUT/classes" "$OUT/runtime-stubs" && mkdir -p "$OUT/classes" "$OUT/runtime-stubs"
    # stubs/: signatures of the car's own classes the runtime extends (compile only, never in the jar)
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$OUT/runtime-stubs" $(find core/runtime/stubs -name '*.java')
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$OUT/runtime-stubs" -d "$OUT/classes" $(find core/runtime/java -name '*.java')
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --classpath "$OUT/runtime-stubs" --output "$OUT/hmgapi-hook.jar" $(find "$OUT/classes" -name '*.class')
    md5sum "$OUT/hmgapi-hook.jar"
}

mods() {
    [ -d "$OUT/classes" ] || runtime
    rm -rf "$OUT/mods" "$OUT/mod-classes" && mkdir -p "$OUT/mods"
    for m in mods/examples/*/ mods/*/; do
        [ "$m" = mods/examples/ ] && continue
        id=$(basename "$m")
        mkdir -p "$OUT/mods/$id"
        cp "$m"*.json "$OUT/mods/$id/"
        if [ -d "$m/src" ]; then
            mkdir -p "$OUT/mod-classes/$id"
            # mods built before this one are on the classpath: a mod compiles against the API of mods it requires
            deps=$(find "$OUT/mod-classes" -mindepth 1 -maxdepth 1 -type d ! -name "$id" | tr '\n' ':')
            # stubs/: signatures of the car's libraries an app part links against (compile only, never in hooks.jar)
            if [ -d "$m/stubs" ]; then
                rm -rf "$OUT/mod-stubs/$id" && mkdir -p "$OUT/mod-stubs/$id"
                javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$OUT/mod-stubs/$id" $(find "$m/stubs" -name '*.java')
                deps="$deps$OUT/mod-stubs/$id:"
            fi
            javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$OUT/classes:$deps" -d "$OUT/mod-classes/$id" $(find "$m/src" -name '*.java')
            "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --classpath "$OUT/classes" $(for d in $(echo "$deps" | tr ':' ' '); do printf -- '--classpath %s ' "$d"; done) \
                --output "$OUT/mods/$id/hooks.jar" $(find "$OUT/mod-classes/$id" -name '*.class')
        fi
        # app/: the mod's own APK (AndroidManifest.xml, res/, java/), with hmgapi-client.jar inside; built to the
        # mod's first "apps" entry. Signed with an example key made here (real mods sign with their author's key).
        if [ -f "$m/app/AndroidManifest.xml" ]; then
            [ -f "$OUT/hmgapi-client.jar" ] || client
            apk=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' "$m/mod.json")
            W="$OUT/mod-app/$id"
            rm -rf "$W" && mkdir -p "$W/classes" "$OUT/mods/$id/$(dirname "$apk")"
            "$BT/aapt2" compile --dir "$m/app/res" -o "$W/res.zip"
            "$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$m/app/AndroidManifest.xml" --min-sdk-version 19 --target-sdk-version 19 \
                --java "$W/gen" -o "$W/base.apk" "$W/res.zip"
            javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR:$OUT/hmgapi-client.jar" -d "$W/classes" \
                $(find "$m/app/java" "$W/gen" -name '*.java')
            (cd "$W/classes" && jar xf "$OUT/hmgapi-client.jar" hmgapi)
            "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$W" $(find "$W/classes" -name '*.class')
            cp "$W/base.apk" "$W/unsigned.apk"
            (cd "$W" && zip -q -X unsigned.apk classes.dex)
            "$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
            K="$OUT/example-app.p12"
            [ -f "$K" ] || keytool -genkeypair -keystore "$K" -storetype pkcs12 -storepass example -keypass example \
                -alias example -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HMG-API example mods" >/dev/null 2>&1
            "$BT/apksigner" sign --ks "$K" --ks-pass pass:example --min-sdk-version 9 \
                --v2-signing-enabled false --v3-signing-enabled false --out "$OUT/mods/$id/$apk" "$W/aligned.apk" 2>/dev/null
            echo "mod $id: app $apk"
        fi
        echo "mod $id"
    done
}

app() {
    W="$OUT/app-build"
    rm -rf "$W" && mkdir -p "$W/classes"
    "$BT/aapt2" compile --dir core/app/res -o "$W/res.zip"
    "$BT/aapt2" link -I "$ANDROID_JAR" --manifest core/app/AndroidManifest.xml --min-sdk-version 19 --target-sdk-version 19 \
        -o "$W/base.apk" "$W/res.zip"
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$W/classes" $(find core/app/java -name '*.java')
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$W" $(find "$W/classes" -name '*.class')
    cp "$W/base.apk" "$W/unsigned.apk"
    (cd "$W" && zip -q -X unsigned.apk classes.dex)
    "$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
    # v1 (JAR) signing only: plenty for API 19 (the head unit's install allowlist,
    # not the digest, is what refuses unknown signers while booted; see core/app/install.sh)
    if [ ! -f "$APP_KEY" ]; then   # no HMG-API key here (CI, a contributor): a throwaway one
        APP_KEY="$OUT/throwaway-app.p12"
        [ -f "$APP_KEY" ] || keytool -genkeypair -keystore "$APP_KEY" -storetype pkcs12 -storepass throwaway -keypass throwaway \
            -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HMG-API test build" >/dev/null 2>&1
        echo throwaway > "${APP_KEY%.p12}.pass"
        echo "app: signed with a throwaway key (set HMGAPI_KEY for a release)" >&2
    fi
    "$BT/apksigner" sign --ks "$APP_KEY" --ks-pass "file:${APP_KEY%.p12}.pass" --min-sdk-version 9 \
        --v2-signing-enabled false --v3-signing-enabled false --out "$OUT/HmgApi.apk" "$W/aligned.apk" 2>/dev/null
    md5sum "$OUT/HmgApi.apk"
}

# hmgapi-client.jar: for mods' own apps and services to compile against and bundle (plain classes; their build dexes it)
client() {
    W="$OUT/client-build"
    rm -rf "$W" && mkdir -p "$W/classes"
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$W/classes" $(find core/client/java -name '*.java')
    (cd "$W/classes" && jar cf "$OUT/hmgapi-client.jar" hmgapi)
    md5sum "$OUT/hmgapi-client.jar"
}

lpatch() { OUT="$OUT" sh tools/lpatch/build.sh; }

payload() {
    P="$OUT/payload"
    rm -rf "$P" && mkdir -p "$P"
    for f in hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk; do
        [ -f "$OUT/$f" ] || { echo "payload: no out/$f (build it first)"; exit 1; }
        cp "$OUT/$f" "$P/"
    done
    cp install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/stock.txt install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh "$P/"
    # HMG-API's NEMESIS scripts and incompatible packages, for engine nemesis-sync on the unit
    mkdir -p "$P/nemesis/mod" "$P/nemesis/bin"
    cp -r install/nemesis/scripts "$P/nemesis/scripts"
    cp -r install/nemesis/mod/scripts "$P/nemesis/mod/scripts"
    cp install/nemesis/bin/open.sh install/nemesis/bin/start.sh "$P/nemesis/bin/"
    cp install/nemesis/conflicts.txt "$P/nemesis/conflicts.txt"
    # the hook maps, with every point id in the catalogue (a map missing one isn't used)
    cp -r maps "$P/maps"
    rm -f "$P/maps/coverage.json"   # tools/test-hooks.py's baseline, for the repo only
    # the catalogue itself: the on-car mapper (engine map-car) finds points by their shapes
    mkdir -p "$P/maps/catalogue" && cp core/hookpoints/*.json "$P/maps/catalogue/"
    python3 -c 'import glob,json; [print(p["id"]) for f in sorted(glob.glob("core/hookpoints/*.json")) for p in json.load(open(f))["points"]]' > "$P/maps/points.txt"
    # MAPS.md5, not MAPS: next to the maps folder, names differing only in case are the same
    # name on FAT/exFAT sticks, Windows and macOS (one of the two was lost when unzipped there)
    (cd "$P" && find maps -type f | sort | xargs md5sum > MAPS.md5)
    sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java > "$P/VERSION"
    echo "$(git rev-parse --short HEAD 2>/dev/null || echo local)$(git diff --quiet HEAD 2>/dev/null || echo +) $(date -u +%Y-%m-%d)" > "$P/BUILD"
    (cd "$P" && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 $(find nemesis -type f | sort) > MANIFEST)
    echo "payload $(cat "$P/VERSION") ($(cat "$P/BUILD")): $P"
}

if [ $# -eq 0 ]; then
    set -- hmgapid zygote loader runtime client mods app lpatch payload
fi
for part in "$@"; do
    case $part in
        hmgapid|zygote|loader|runtime|client|mods|app|lpatch|payload) $part ;;
        *) echo "unknown part: $part"; exit 2 ;;
    esac
done
