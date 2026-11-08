HMG-API mod SDK
===============

What a HMG-API mod outside the HMG-API repo builds, tests and packages with. Its
version (VERSION) is the HMG-API release it comes from; it works for a mod whose
"hmgapi" floor is at or below it. No manufacturer files, no Android SDK files: install
the Android SDK (build-tools, a platform android.jar) and set ANDROID_HOME.

  lib/hmgapi-runtime.jar        HMG-API's runtime (hmgapi.hook.*, ModEntry...): compile only
  lib/hmgapi-runtime-stubs.jar  the car classes runtime signatures name: compile only
  lib/hmgapi-client.jar         for a mod's own app or service (bundled in it)
  lib/hmgapi-engine.jar         the engine on this machine (HMGAPI_HOST=1), Signing, ScriptCheck
  bin/build-mod.sh <mod> <out>  hooks.jar from src/, the APK from app/
  bin/mkmodpkg.sh <mod> <key>   the signed package, out/packages/<id>-<version>.zip
  bin/mknemesismod.sh <zip>     the same mod as a NEMESIS package, out/dist/
  bin/check-scripts.sh <mod>    unit-only commands in the mod's scripts (unit-commands.txt)
  bin/check-points.py <mod>     the "hmgapi" floor and hooks.points against HMG-API's releases
  bin/lpatch-host, src/lpatch/  lpatch for host tests (src/lpatch/build.sh builds it elsewhere)
  bin/mkrecipe.py, mkdelta.py, mkmemrecipe.py, mkicon.sh, mod-draft.py   as in HMG-API's tools/
  test/lib.sh                   check/has/eng/tree for a mod's test.sh
  test/hmgapi-engine-test.jar   hmgapi.engine.MemRecipeTest: native recipes on real libraries
  points.txt, catalogue/        the hook points this release has, and their catalogue
  nemesis/                      the NEMESIS package templates

A key: java -cp lib/hmgapi-engine.jar hmgapi.engine.Signing keygen <key file>
How mods are built outside HMG-API: docs/mod-reference.md in the HMG-API repo,
"Building a mod outside this repo". AGPL-3.0 (LICENSE).
