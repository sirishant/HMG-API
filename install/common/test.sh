#!/bin/sh
# Off-car test of install/common/lib.sh (the boot hook) against a fake root.
# UNIT_SH: the shell to run it with (an ARM busybox ash is closest to the unit's mksh).
set -e
cd "$(dirname "$0")"
SH=${UNIT_SH:-sh}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
L() { env ROOT="$T" $SH -c ". $PWD/lib.sh; $*"; }
mkdir -p "$T/system/etc"
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"; chmod 755 "$T/system/etc/install-recovery.sh"
printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > "$T/system/etc/qb_runtime.sh"
IR=$(md5sum < "$T/system/etc/install-recovery.sh"); QB=$(md5sum < "$T/system/etc/qb_runtime.sh")

L hook_add /system/etc/install-recovery.sh top
check "block after the first line" "# HMGAPI_HOOK begin (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)" "$(sed -n 2p "$T/system/etc/install-recovery.sh")"
check "still ends with exit 0" "exit 0" "$(tail -1 "$T/system/etc/install-recovery.sh")"
check "NEMESIS lines kept" "1" "$(grep -c 'nemesis watchdog' "$T/system/etc/install-recovery.sh")"
check "stays executable" "yes" "$([ -x "$T/system/etc/install-recovery.sh" ] && echo yes)"
L hook_add /system/etc/install-recovery.sh top
check "adding twice keeps one block" "1" "$(grep -c 'HMGAPI_HOOK begin' "$T/system/etc/install-recovery.sh")"
L hook_add /system/etc/qb_runtime.sh end
check "qb block at the end" "# HMGAPI_HOOK end" "$(tail -1 "$T/system/etc/qb_runtime.sh")"
check "qb not made executable" "no" "$([ -x "$T/system/etc/qb_runtime.sh" ] && echo yes || echo no)"
sh -n "$T/system/etc/install-recovery.sh" && echo "ok    hooked script parses" || { echo "FAIL  hooked script syntax"; fail=1; }

L hook_remove /system/etc/install-recovery.sh
L hook_remove /system/etc/qb_runtime.sh
check "install-recovery.sh back byte for byte" "$IR" "$(md5sum < "$T/system/etc/install-recovery.sh")"
check "qb_runtime.sh back byte for byte" "$QB" "$(md5sum < "$T/system/etc/qb_runtime.sh")"

rm "$T/system/etc/install-recovery.sh"
L hook_add /system/etc/install-recovery.sh top
check "created when missing" "yes" "$([ -x "$T/system/etc/install-recovery.sh" ] && grep -q HMGAPI_CREATED "$T/system/etc/install-recovery.sh" && echo yes)"
L hook_remove /system/etc/install-recovery.sh
check "created file removed again" "no" "$([ -e "$T/system/etc/install-recovery.sh" ] && echo yes || echo no)"

# hook-check.sh at boot: a NEMESIS install or update rewrites install-recovery.sh as a whole
D=$T/data/hmgapi
mkdir -p "$D"; cp lib.sh "$D/"; echo "version 1.0.0" > "$D/installed.txt"
HC() { env ROOT="$T" $SH "$PWD/hook-check.sh" 2>&1; }
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"; chmod 755 "$T/system/etc/install-recovery.sh"
L hook_add /system/etc/install-recovery.sh top
L hook_add /system/etc/qb_runtime.sh end
IRH=$(md5sum < "$T/system/etc/install-recovery.sh"); QBH=$(md5sum < "$T/system/etc/qb_runtime.sh")
o=$(HC)
check "hook-check: both hooks there: nothing changed, nothing said" "|$IRH|$QBH" "$o|$(md5sum < "$T/system/etc/install-recovery.sh")|$(md5sum < "$T/system/etc/qb_runtime.sh")"
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"   # what a NEMESIS update writes
o=$(HC)
check "hook-check: install-recovery.sh rewritten: the block put back after the first line" "# HMGAPI_HOOK begin (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)" "$(sed -n 2p "$T/system/etc/install-recovery.sh")"
check "... as HMG-API's install puts it (same bytes)" "$IRH" "$(md5sum < "$T/system/etc/install-recovery.sh")"
check "... qb_runtime.sh left alone" "$QBH" "$(md5sum < "$T/system/etc/qb_runtime.sh")"
check "... said in install.log" "1" "$(grep -c 'boot hook put back in /system/etc/install-recovery.sh' "$D/install.log")"
check "... no lock left" "no" "$([ -e "$D/state/hook-check.pid" ] && echo yes || echo no)"
L hook_remove /system/etc/qb_runtime.sh
HC > /dev/null
check "hook-check: qb_runtime.sh lost it: put back at the end" "$QBH" "$(md5sum < "$T/system/etc/qb_runtime.sh")"
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"
rm "$D/installed.txt"
HC > /dev/null
check "hook-check: HMG-API not installed: nothing put back" "0" "$(grep -c HMGAPI_HOOK "$T/system/etc/install-recovery.sh")"
echo "version 1.0.0" > "$D/installed.txt"
if [ -f /proc/self/cmdline ]; then   # hook-check tells one is running from /proc/<pid>/cmdline, as on the unit
    cp "$(command -v sleep)" "$T/hook-check"; "$T/hook-check" 30 & sp=$!   # stands in for one already running
    echo $sp > "$D/state/hook-check.pid"
    HC > /dev/null
    check "hook-check: another one running: this one does nothing" "0" "$(grep -c HMGAPI_HOOK "$T/system/etc/install-recovery.sh")"
    kill $sp 2>/dev/null; wait $sp 2>/dev/null || true
else
    echo "skip  hook-check: another one running (no /proc here)"
fi
echo 999999 > "$D/state/hook-check.pid"
HC > /dev/null
check "hook-check: a stale lock (after a restart) doesn't stop it" "1" "$(grep -c 'HMGAPI_HOOK begin' "$T/system/etc/install-recovery.sh")"
exit $fail
