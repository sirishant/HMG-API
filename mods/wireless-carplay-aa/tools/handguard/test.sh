#!/bin/sh
# Host test of the Wireless mod's manual connect guard (dipo/HandGuard): which automatic
# CarPlay starts it holds back, for how long, and the masked address in its log.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" ../../src/hmgapi/mods/wireless/dipo/HandGuard.java test/hmgapi/mods/wireless/dipo/HandGuardTest.java
java -cp "$CP" hmgapi.mods.wireless.dipo.HandGuardTest
