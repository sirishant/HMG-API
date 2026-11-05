#!/system/bin/sh
# HMG-API's NEMESIS service ([service.0] in NEMESIS.data, mode=background): NEMESIS's
# svcmgr starts it at boot, so HMG-API starts with NEMESIS even when a NEMESIS install
# or update rewrote install-recovery.sh without HMG-API's boot hook. `hmgapid boot`
# returns at once and runs HMG-API's boot steps only if hmgapid isn't running yet, so
# this and the boot hook can both run: the first one starts HMG-API, the other does nothing.
[ -x /system/bin/hmgapid ] || exit 0
exec /system/bin/hmgapid boot
