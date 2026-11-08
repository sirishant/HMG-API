#!/bin/sh
# Off-car test of the hello mod with the mod SDK (the template for a mod's own test.sh):
# the engine takes the built mod, publishes it as on, and removes it again.
# The mod workflow runs it with HMGAPI_SDK (the unpacked SDK) and HMGAPI_MOD_BUILD
# (this mod as bin/build-mod.sh built it).
#   HMGAPI_SDK=<sdk> HMGAPI_MOD_BUILD=<built mod> mods/examples/hello/test.sh
set -e
B=${HMGAPI_MOD_BUILD:?set HMGAPI_MOD_BUILD to the built mod (the out dir of bin/build-mod.sh)}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"
mkdir -p $R/system $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\n' > $R/props.txt
install_mod "$B"
check "built: hooks.jar has the entry class" yes "$(unzip -p "$B/hooks.jar" classes.dex | grep -q 'Lexample/hello/Hello;' && echo yes)"
eng plan; check "plan" 0 $rc
eng apply; check "apply" 0 $rc
has "published as on" "1 of 1 mods on" "$out"
has "in the state the app reads" '"hello"' "$(cat $R/data/hmgapi/public/state.json)"
printf '{"greeting": "hi"}\n' > $R/data/hmgapi/settings/hello.json
eng apply; check "a setting change applies" 0 $rc
eng remove hello; check "remove" 0 $rc
eng apply; check "... and apply after it" 0 $rc
check "gone from the mods folder" "" "$(ls $R/data/hmgapi/mods)"
exit $fail
