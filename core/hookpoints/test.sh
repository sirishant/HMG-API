#!/bin/sh
# The hook point catalogue and every car map (tools/check-maps.py; --rebuild where a stock tree is known),
# docs/hook-points.md made from them (tools/mkhookdoc.py),
# then, for the cars tools/hook-trees.local lists: every point on every car (tools/test-hooks.py) and the
# on-car mapper against map-car.py (tools/probe-test.py).
cd "$(dirname "$0")/../.."
python3 tools/check-maps.py --rebuild || exit 1
python3 tools/mkhookdoc.py --check || exit 1
[ -f tools/hook-trees.local ] || { echo "skip  coverage and the on-car mapper (no tools/hook-trees.local)"; exit 0; }
python3 tools/test-hooks.py || exit 1
echo "ok    hook coverage on every car"
python3 tools/probe-test.py || exit 1
echo "ok    on-car mapper finds what map-car.py finds"
