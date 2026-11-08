#!/bin/sh
# Off-car test of native patches in memory (hmgapi.engine.MemRecipe) on synthetic
# libraries. A mod's own recipes on real stock libraries are the mod's test (MemRecipeTest
# with <stock> <patched> <recipe> triples; the mod SDK has it in test/hmgapi-engine-test.jar).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP" && javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/MemRecipeTest.java 2>/dev/null
exec java -cp "$CP" hmgapi.engine.MemRecipeTest
