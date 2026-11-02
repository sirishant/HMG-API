#!/bin/sh
# Host test of the Creta display through the Wireless mod's real AaTurns, with stand-ins
# for Android and HMG-API's Cluster/Car that record every cluster call.
set -e
cd "$(dirname "$0")"
A=../wireless-carplay-aa
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
S=$(find "$A/tools/aaturns/stubs" -name '*.java' ! -path '*/android/content/Context.java')
javac --release 8 -Xlint:-options -nowarn -d "$CP" $S $(find tools/stubs tools/test src/hmgapi/mods/creta -name '*.java' ! -name CretaMod.java) \
    "$A/src/hmgapi/mods/wireless/AaTurns.java" "$A/src/hmgapi/mods/wireless/api/TurnDisplay.java"
java -cp "$CP" hmgapi.mods.wireless.CretaRhdTest
