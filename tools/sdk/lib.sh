# Helpers for a mod's test.sh, in the mod SDK as test/lib.sh (the same check/has/eng the
# mods' tests in HMG-API used). A test sources it after setting R, the fake head unit's root:
#   . "$HMGAPI_SDK/test/lib.sh"
# then calls eng <engine args> (sets $out and $rc), check/has, and ends with exit $fail.
# HMGAPI_SDK is the unpacked SDK (the mod workflow sets it). Set HMGAPI_PRODUCT,
# HMGAPI_ONEBIN etc. in the environment for the engine; HMGAPI_TEST_ANY_CAR defaults to 1.
HMGAPI_SDK=${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}
HMGAPI_SDK=$(cd "$HMGAPI_SDK" && pwd)
ENGINE=$HMGAPI_SDK/lib/hmgapi-engine.jar
LPATCH=$HMGAPI_SDK/bin/lpatch-host
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
# the engine on this machine (host mode), on the head unit at $R
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=${HMGAPI_TEST_ANY_CAR-1} HMGAPI_LPATCH=$LPATCH \
        java -cp "$ENGINE" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
# md5 and path of every file under $R/system (compare before and after a restore)
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }
# put the mod (its folder, without test.sh) on the head unit: install_mod <mod dir>
install_mod() { mkdir -p $R/data/hmgapi/mods && rm -rf "$R/data/hmgapi/mods/$(basename "$1")" && cp -r "$1" $R/data/hmgapi/mods/ && rm -f "$R/data/hmgapi/mods/$(basename "$1")/test.sh"; }
