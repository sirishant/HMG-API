#!/bin/sh
# Host tests of the switch rules and the Bluetooth watchdog rules (timings and choices); the hooks need the car.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" src/hmgapi/mods/projextras/Rules.java src/hmgapi/mods/projextras/BtRules.java \
    tools/test/hmgapi/mods/projextras/RulesTest.java tools/test/hmgapi/mods/projextras/BtRulesTest.java
java -cp "$CP" hmgapi.mods.projextras.RulesTest
java -cp "$CP" hmgapi.mods.projextras.BtRulesTest
