#!/bin/sh
# Every script that runs on a head unit (each mod's, and HMG-API's installer) only
# calls commands the unit has: core/runtime/unit-commands.txt (a US EV6's /system/bin
# and /system/xbin: mksh and Android 4.4's toolbox, so no sed, tr, awk, head, tail...).
# The engine runs the same check at install, against the unit itself.
#   tools/check-scripts.sh [mod dir or script...]   (default: every mod and the installer)
cd "$(dirname "$0")/.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
[ $# -gt 0 ] || set -- mods/*/ mods/examples/*/ install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh install/nemesis/bin/start.sh install/nemesis/mod/scripts/*.sh
java -cp "$CP" hmgapi.engine.ScriptCheck core/runtime/unit-commands.txt "$@"
