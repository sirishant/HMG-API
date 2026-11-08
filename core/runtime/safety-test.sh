#!/bin/sh
# Off-car test of the engine's safety rules and the change features the
# Wireless mod needs: build.prop guards, boot-critical files, live persist.*
# props, optional changes, delta candidates, the stock odex from a NEMESIS backup.
# Needs a JDK, python3 with bsdiff4, and out/lpatch-host (./build.sh lpatch).
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -x out/lpatch-host ] || { echo "run ./build.sh lpatch first"; exit 1; }
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP" 2>/dev/null
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 HMGAPI_ONEBIN=cvev_us \
        HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
mod() {   # mod <id> <patches.json changes>: replaces every mod
    rm -rf $R/data/hmgapi/mods; d=$R/data/hmgapi/mods/$1; mkdir -p $d/patches/ev6
    printf '{"id": "%s", "version": "1.0.0"}\n' "$1" > $d/mod.json
    printf '{"cars": {"CVEV.USA.": "ev6"}, "changes": [%s]}\n' "$2" > $d/patches.json
    printf 'Test.apk %s\nTest.odex %s\nlibx.so %s\n' $STOCK_APK $STOCK_ODEX $STOCK_LIB > $d/patches/ev6/stock
    cp $T/m/* $d/
}
mkdir -p $R/system/lib $R/system/app $R/system/bin $R/system/etc/permissions $R/data/hmgapi/settings $T/m
printf 'ro.build.product=CVEV.USA.0000.V015.012.260507\nro.build.version.release=4.4.2\n# comment\npersist.ium.support=none\n' > $R/system/build.prop
python3 - "$T" "$R" <<'PY'
import sys, random, zipfile
T, R = sys.argv[1], sys.argv[2]
random.seed(11)
with zipfile.ZipFile(R + "/system/app/Test.apk", "w") as z:
    z.writestr("AndroidManifest.xml", b"manifest")
open(R + "/system/app/Test.odex", "wb").write(bytes(random.getrandbits(8) for _ in range(8192)))
open(T + "/other.odex", "wb").write(bytes(random.getrandbits(8) for _ in range(8192)))
open(T + "/classes.dex", "wb").write(b"dex\n035\0" + bytes(random.getrandbits(8) for _ in range(4096)))
open(R + "/system/lib/libx.so", "wb").write(bytes(random.getrandbits(8) for _ in range(4096)))
open(T + "/libx.new", "wb").write(bytes(random.getrandbits(8) for _ in range(4096)))
open(R + "/system/lib/libc.so", "wb").write(b"libc")
PY
STOCK_APK=$(md5 $R/system/app/Test.apk); STOCK_ODEX=$(md5 $R/system/app/Test.odex); STOCK_LIB=$(md5 $R/system/lib/libx.so)
STOCK_PROP=$(md5 $R/system/build.prop)
python3 tools/mkdelta.py $R/system/app/Test.odex $T/classes.dex $T/m/Test.lpd > /dev/null
python3 tools/mkdelta.py $T/other.odex $T/classes.dex $T/m/Other.lpd > /dev/null
python3 tools/mkdelta.py $R/system/lib/libx.so $T/libx.new $T/m/libx.lpd > /dev/null
python3 tools/mkdelta.py $T/other.odex $T/libx.new $T/m/nofit.lpd > /dev/null
echo '<permissions/>' > $T/m/a.xml

# ---- a change's path: a plain /system path only
mod a '{"type": "add", "path": "/system/bin/../bin/app_process", "src": "a.xml"}'
eng plan; has "a path through .. refused (HMG-API's own and critical files are checked by exact path)" "isn't a plain /system file path" "$out"; check "... refusal exit" 3 $rc
mod a '{"type": "add", "path": "/system/app/x;reboot.apk", "src": "a.xml"}'
eng plan; has "shell characters in a path refused" "isn't a plain /system file path" "$out"
mod a '{"type": "add", "path": "/data/local/tmp/x", "src": "a.xml"}'
eng plan; has "a path outside /system refused" "isn't a plain /system file path" "$out"
mod a '{"type": "add", "path": "/system/etc/permissions/x.xml", "src": "a.xml"}'
eng plan; check "a plain /system path accepted" 0 $rc

# ---- build.prop
mod a '{"type": "props", "path": "/system/build.prop", "set": {"ro.build.product": "X"}, "stock": "any"}'
eng plan; has "ro.* key refused" "ro.* properties set how the firmware identifies" "$out"; check "refusal exit" 3 $rc
mod a '{"type": "props", "path": "/system/build.prop", "remove": ["ro.build.version.release"], "stock": "any"}'
eng plan; has "removing an ro.* key refused" "changes ro.build.version.release" "$out"
mod a '{"type": "lines", "path": "/system/build.prop", "remove": ["# comment"], "stock": "any"}'
eng plan; has "lines change on build.prop refused" "build.prop only takes \"props\"" "$out"
mod a '{"type": "add", "path": "/system/build.prop", "src": "a.xml"}'
eng plan; has "replacing build.prop refused" "build.prop only takes \"props\"" "$out"
mod a '{"type": "props", "path": "/system/build.prop", "set": {"persist.x": "a\nro.secure=0"}, "stock": "any"}'
eng plan; has "a value with a line break refused" "a value with a line break" "$out"
check "build.prop untouched by refusals" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
mod a '{"type": "props", "path": "/system/build.prop", "set": {"persist.ium.support": "carplay"}, "stock": "any"}'
eng apply; check "persist.* key in build.prop allowed" 0 $rc
check "every other line kept, in order" "ro.build.product=CVEV.USA.0000.V015.012.260507|ro.build.version.release=4.4.2|# comment|persist.ium.support=carplay" "$(tr '\n' '|' < $R/system/build.prop | sed 's/|$//')"
eng restore; check "build.prop back byte for byte" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
printf '{"overrides": {"allow_critical": ["/system/build.prop:ro.build.version.release"]}}\n' > $R/data/hmgapi/settings/hmgapi.json
mod a '{"type": "props", "path": "/system/build.prop", "set": {"ro.build.version.release": "4.4.4"}, "stock": "any"}'
eng plan; check "ro.* key allowed by override" 0 $rc
rm $R/data/hmgapi/settings/hmgapi.json

# ---- boot-critical and HMG-API's own files
mod a '{"type": "add", "path": "/system/lib/libc.so", "src": "a.xml", "stock": "any"}'
eng plan; has "boot-critical file refused" "the unit needs this file to boot" "$out"
printf '{"overrides": {"allow_critical": ["/system/lib/libc.so"]}}\n' > $R/data/hmgapi/settings/hmgapi.json
eng plan; has "boot-critical allowed by override, with a warning" "WARNING: /system/lib/libc.so is boot-critical" "$out"
mod a '{"type": "add", "path": "/system/bin/app_process", "src": "a.xml", "stock": "any"}'
eng plan; has "HMG-API's own files refused even with overrides" "belongs to HMG-API's own install" "$out"
rm $R/data/hmgapi/settings/hmgapi.json
mod a '{"type": "add", "path": "/system/framework/framework.jar", "src": "a.xml"}'
eng plan; has "boot class path jar refused" "framework.jar: the unit needs this file to boot" "$out"

# ---- live props
mod a '{"type": "prop", "key": "ro.secure", "value": "0"}'
eng plan; has "non-persist prop refused" "only persist.* properties can be set" "$out"
printf 'prop/persist.sys.iap2.wifi=f\n' > $R/settings.txt
mod a '{"type": "prop", "key": "persist.sys.iap2.wifi", "value": "t"}, {"type": "prop", "key": "persist.iap2.iden.wireless", "value": "t"}'
eng apply; has "persist prop set live" "prop/persist.sys.iap2.wifi=t" "$(cat $R/settings.txt)"
has "unset persist prop set" "prop/persist.iap2.iden.wireless=t" "$(cat $R/settings.txt)"
eng restore; check "props back: old value, and unset again" "prop/persist.sys.iap2.wifi=f" "$(cat $R/settings.txt)"

# ---- optional changes and delta candidates
mod a '{"type": "delta", "path": "/system/lib/libx.so", "delta": ["nofit.lpd", "libx.lpd"]}'
eng apply; check "second delta candidate used" "$(md5 $T/libx.new)" "$(md5 $R/system/lib/libx.so)"
eng restore
mod a '{"type": "delta", "path": "/system/lib/libx.so", "delta": "nofit.lpd", "optional": true}, {"type": "add", "path": "/system/etc/permissions/a.xml", "src": "a.xml"}'
eng apply; check "optional change that doesn't fit: the rest still applies" 0 $rc
has "skip reported" "skip     /system/lib/libx.so (optional" "$out"
check "skipped file left stock" "$STOCK_LIB" "$(md5 $R/system/lib/libx.so)"
check "other change applied" "<permissions/>" "$(cat $R/system/etc/permissions/a.xml)"
mod a '{"type": "delta", "path": "/system/lib/libx.so", "delta": "nofit.lpd"}'
eng plan; check "the same change not optional: refused" 3 $rc
mod a '{"type": "add", "path": "/system/etc/permissions/b.xml", "src": "a.xml"}'
eng restore

# ---- binds: a binary from the mod's own folder over a stock one
mod a '{"type": "bind", "src": "files/x", "target": "/sbin/adbd", "service": "adbd"}'
eng plan; has "bind: binary missing from the mod" "bind: no files/x in the mod" "$out"
mkdir -p $R/data/hmgapi/mods/a/files; echo bin > $R/data/hmgapi/mods/a/files/x
eng plan; has "bind planned from the mod's folder" "bind     /data/hmgapi/mods/a/files/x over /sbin/adbd (service adbd)" "$out"
eng apply; check "binds.txt for hmgapid" "/data/hmgapi/mods/a/files/x /sbin/adbd adbd" "$(cat $R/data/hmgapi/state/binds.txt)"
has "hmgapid told to do it" "binds" "$(cat $R/actions.log 2>/dev/null)"
mod a '{"type": "bind", "src": "files/x", "target": "/system/bin/linker", "service": "x"}'
mkdir -p $R/data/hmgapi/mods/a/files; echo bin > $R/data/hmgapi/mods/a/files/x
eng plan; has "bind over a boot-critical binary refused" "the unit needs /system/bin/linker to boot" "$out"
mod a '{"type": "add", "path": "/system/etc/permissions/b.xml", "src": "a.xml"}'
eng apply; has "binds dropped with the mod: unbind planned" "binds" "$(cat $R/actions.log)"
check "binds.txt empty again" "" "$(cat $R/data/hmgapi/state/binds.txt)"
eng restore

# ---- whole-file replacements: allowed, and the plan says other mods can't share the file
mod a '{"type": "delta", "path": "/system/lib/libx.so", "delta": "libx.lpd"}'
eng plan; has "ownership note" "note     a replaces whole files (delta), so no other mod can change those files" "$out"

# ---- app from a NEMESIS backup (the live apk and odex already changed by a NEMESIS package)
mkdir -p $R/data/nemesis/backup/projection.tbt $R/data/nemesis/backup/other
cp $R/system/app/Test.apk $R/system/app/Test.odex $R/data/nemesis/backup/projection.tbt/
head -c 8192 /dev/urandom > $R/data/nemesis/backup/other/Test.odex   # some other package's copy: no delta fits it
echo modified >> $R/system/app/Test.apk; echo modified >> $R/system/app/Test.odex   # NEMESIS's build on /system
mod a '{"type": "app", "path": "/system/app/Test.apk", "delta": ["Other.lpd", "Test.lpd"]}'
eng apply; check "app built from NEMESIS's stock copies" 0 $rc
has "NEMESIS baseline reported" "using the stock copy NEMESIS kept" "$out"
check "stock odex backed up (not the live one)" "$STOCK_ODEX" "$(md5 $R/data/hmgapi/stock/system/app/Test.odex)"
check "stock apk backed up" "$STOCK_APK" "$(md5 $R/data/hmgapi/stock/system/app/Test.apk)"
eng restore
check "restore: stock apk" "$STOCK_APK" "$(md5 $R/system/app/Test.apk)"
check "restore: stock odex" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"
exit $fail
