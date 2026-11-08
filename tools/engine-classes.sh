#!/bin/sh
# engine-classes.sh <dir>: hmgapi.engine compiled into <dir>, for the off-car tests. Copied from
# HMGAPI_TEST_ENGINE when tools/test-all.sh compiled it once for the run (javac is most of a
# test's time, and of the CPU when they run side by side); compiled here otherwise.
# Run from the top of the repo.
if [ -n "$HMGAPI_TEST_ENGINE" ] && [ -d "$HMGAPI_TEST_ENGINE/hmgapi/engine" ]; then
    cp -R "$HMGAPI_TEST_ENGINE/." "$1/"
else
    javac --release 8 -Xlint:-options -nowarn -d "$1" core/runtime/java/hmgapi/engine/*.java
fi
