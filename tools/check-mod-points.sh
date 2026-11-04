#!/bin/sh
# Every hook point a mod's code names exists in the catalogue, and every one it hooks is declared
# in its mod.json (tools/check-mod-points.py). Off the car a typo would otherwise pass every test.
cd "$(dirname "$0")/.."
set --
for m in mods/*/ mods/examples/*/; do
    [ -f "$m/mod.json" ] && [ -d "$m/src" ] && set -- "$@" "$m"
done
python3 tools/check-mod-points.py "$@"
