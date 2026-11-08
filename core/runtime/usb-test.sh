#!/bin/sh
# Off-car test of updates from a USB stick (engine usb-scan, install, update-hmgapi)
# with a fake stick: what each zip would do, and that only genuine releases run.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root; U=$T/usb0
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
sig() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
sig keygen $T/author.key > /dev/null; sig keygen $T/release.key > /dev/null; sig keygen $T/fake.key > /dev/null
UA=$(sig uuid $T/author.key); UR=$(sig uuid $T/release.key)
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_USB_DIRS=$U:$T/usb1 HMGAPI_TEST_RELEASE_UUID=$UR \
        java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
st() {   # st <file name>: the status the scan gives that zip
    printf '%s' "$out" | python3 -c "import json,sys; d=json.load(sys.stdin); print([p['status'] for p in d['packages'] if p['file'].endswith('/'+sys.argv[1])][0])" "$1"
}
mkdir -p $R/data/hmgapi/mods $R/data/hmgapi/settings $R/data/hmgapi/state
printf 'ro.board.platform=tcc803x\n' > $R/props.txt
mod() {   # mod <id> <version> <key> <zip>
    rm -rf $T/m; mkdir -p $T/m/$1
    printf '{"id": "%s", "name": "Mod %s", "version": "%s", "hmgapi": ">=1.0.0"}\n' "$1" "$1" "$2" > $T/m/$1/mod.json
    sig sign $T/m/$1 $3 > /dev/null
    (cd $T/m && zip -q -r -X $4 $1)
}
eng usb-scan
has "no stick: says why, and about ADB over USB" "the port is in computer mode" "$out"

mkdir -p $U/HMGAPI/updates
mod alpha 1.0.0 $T/author.key $U/HMGAPI/updates/alpha-1.0.0.zip
mod beta 2.0.0 $T/author.key $U/HMGAPI/updates/beta-2.0.0.zip
mod gamma 1.0.0 $T/fake.key $U/HMGAPI/updates/gamma-1.0.0.zip
echo junk > $U/HMGAPI/updates/junk.zip
# a release: a tiny installer that leaves a mark
mkdir -p $T/r/hmgapi-9.9.9; printf '9.9.9\n' > $T/r/hmgapi-9.9.9/VERSION
printf '#!/bin/sh\necho "fake installer ran with $1"\n: > "$ROOT/installed-9.9.9"\n' > $T/r/hmgapi-9.9.9/install.sh
sig sign-hmgapi $T/r/hmgapi-9.9.9 $T/release.key > /dev/null; (cd $T/r && zip -q -r -X $U/HMGAPI/updates/hmgapi-9.9.9.zip hmgapi-9.9.9)
mkdir -p $T/f/hmgapi-9.9.9; cp $T/r/hmgapi-9.9.9/VERSION $T/r/hmgapi-9.9.9/install.sh $T/f/hmgapi-9.9.9/
sig sign-hmgapi $T/f/hmgapi-9.9.9 $T/fake.key > /dev/null; (cd $T/f && zip -q -r -X $U/HMGAPI/updates/hmgapi-fake.zip hmgapi-9.9.9)
# beta 1.0.0 already installed, by the same author
mkdir -p $T/b/beta; printf '{"id": "beta", "version": "1.0.0", "hmgapi": ">=1.0.0"}\n' > $T/b/beta/mod.json; sig sign $T/b/beta $T/author.key >/dev/null
cp -r $T/b/beta $R/data/hmgapi/mods/

eng usb-scan; check "scan runs" 0 $rc
check "new mod" new "$(st alpha-1.0.0.zip)"
has "with its author UUID" "\"uuid\": \"$UA\"" "$out"
check "update by the same author" update "$(st beta-2.0.0.zip)"
check "new mod by another author" new "$(st gamma-1.0.0.zip)"
has "... not on the trusted list: warned" "isn't on HMG-API's trusted author list" "$out"
check "junk refused" refused "$(st junk.zip)"
check "genuine HMG-API release offered" update "$(st hmgapi-9.9.9.zip)"
check "release signed with another key refused" refused "$(st hmgapi-fake.zip)"
has "and says why" "not a genuine HMG-API release" "$out"
check "the scan installed nothing" "beta" "$(ls $R/data/hmgapi/mods)"

mkdir -p $T/usb1/HMGAPI/updates; cp $U/HMGAPI/updates/alpha-1.0.0.zip $T/usb1/HMGAPI/updates/
eng usb-scan; check "a stick seen at two mount points: listed once" 1 "$(printf '%s' "$out" | grep -o 'alpha-1.0.0.zip' | wc -l | tr -d ' ')"

eng install $U/HMGAPI/updates/alpha-1.0.0.zip; check "install from the stick" 0 $rc
eng install $U/HMGAPI/updates/beta-2.0.0.zip; check "update from the stick" 0 $rc
has "update in place" '"version": "2.0.0"' "$(cat $R/data/hmgapi/mods/beta/mod.json)"

eng update-hmgapi $U/HMGAPI/updates/hmgapi-fake.zip; check "fake release not run" 3 $rc
check "its installer didn't run" no "$([ -e $R/installed-9.9.9 ] && echo yes || echo no)"
eng update-hmgapi $U/HMGAPI/updates/hmgapi-9.9.9.zip; check "genuine release runs its installer" 0 $rc
has "installer output passed on" "fake installer ran" "$out"
check "installer ran" yes "$([ -e $R/installed-9.9.9 ] && echo yes)"
printf 'version 9.9.9\n' > $R/data/hmgapi/installed.txt
eng usb-scan; check "after the update: same version" same "$(st hmgapi-9.9.9.zip)"

# a mod for another head unit: refused, and the app may offer "Install anyway"
rm -rf $T/m; mkdir -p $T/m/other
printf '{"id": "other", "name": "Other", "version": "1.0.0", "hmgapi": ">=1.0.0", "system": {"platform": ["tcc897x"]}}\n' > $T/m/other/mod.json
sig sign $T/m/other $T/author.key > /dev/null; (cd $T/m && zip -q -r -X $U/HMGAPI/updates/other-1.0.0.zip other)
eng usb-scan; check "another head unit's mod refused" refused "$(st other-1.0.0.zip)"
check "... with what installing it anyway would do" new "$(printf '%s' "$out" | python3 -c "import json,sys; d=json.load(sys.stdin); print([p.get('any_car') for p in d['packages'] if p['file'].endswith('/other-1.0.0.zip')][0])")"
check "a refusal that isn't about the car: no Install anyway" None "$(printf '%s' "$out" | python3 -c "import json,sys; d=json.load(sys.stdin); print([p.get('any_car') for p in d['packages'] if p['file'].endswith('/gamma-1.0.0.zip') or p['file'].endswith('/hmgapi-fake.zip')][-1])")"
exit $fail
