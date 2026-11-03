#!/bin/sh
# Host test of the switch rules (timings and choices); the hooks need the car.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" src/hmgapi/mods/projextras/Rules.java tools/test/hmgapi/mods/projextras/RulesTest.java
java -cp "$CP" hmgapi.mods.projextras.RulesTest
