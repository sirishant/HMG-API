#!/bin/sh
# Host test of the climate policy against a made-up car (no Android, no HMG-API).
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" service/hmgapi/mods/climate/Policy.java tools/test/hmgapi/mods/climate/PolicyTest.java
java -cp "$CP" hmgapi.mods.climate.PolicyTest
