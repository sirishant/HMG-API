#!/bin/sh
# Off-car test of mod signing (hmgapi.engine.Signing) on a desktop JVM.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP"
S() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
v() { set +e; out=$(S verify "$@" 2>&1); rc=$?; set -e; }

S keygen "$T/author.key" > "$T/kg"
U=$(S uuid "$T/author.key")
has "keygen prints the uuid" "uuid: $U" "$(cat "$T/kg")"
check "uuid from .pub is the same" "$U" "$(S uuid "$T/author.key.pub")"
case "$U" in ????????-????-8???-[89ab]???-????????????) echo "ok    uuid format" ;; *) echo "FAIL  uuid format $U"; fail=1 ;; esac
set +e; S keygen "$T/author.key" >/dev/null 2>&1; rc=$?; set -e
check "keygen won't overwrite a key" 1 $rc

cp -r mods/examples/hello "$T/hello"
has "sign" "signed hello 1.0.0" "$(S sign "$T/hello" "$T/author.key")"
v "$T/hello"; check "verify" 0 $rc; has "verify names it" "ok: hello 1.0.0 uuid $U" "$out"
v "$T/hello" "$U"; check "verify with the expected uuid" 0 $rc
v "$T/hello" "00000000-0000-8000-8000-000000000000"; check "different author refused" 1 $rc
has "says why" "signed by a different author" "$out"

cp -r "$T/hello" "$T/t1"; echo " " >> "$T/t1/settings.json"
v "$T/t1"; check "changed file refused" 1 $rc; has "names the file" "file changed after signing: settings.json" "$out"
cp -r "$T/hello" "$T/t2"; echo x > "$T/t2/extra.txt"
v "$T/t2"; check "extra file refused" 1 $rc; has "names it" "file not in the manifest: extra.txt" "$out"
cp -r "$T/hello" "$T/t3"; rm "$T/t3/settings.json"
v "$T/t3"; check "missing file refused" 1 $rc
cp -r "$T/hello" "$T/t4"; rm -r "$T/t4/META-HMGAPI"
v "$T/t4"; check "unsigned refused" 1 $rc; has "says unsigned" "not signed" "$out"

# a forger re-signs with their own key but keeps the original uuid in the manifest
S keygen "$T/forger.key" > /dev/null
cp -r "$T/hello" "$T/t5"; S sign "$T/t5" "$T/forger.key" > /dev/null
f=$T/t5/META-HMGAPI/manifest.json; sed "s/\"uuid\": \"[^\"]*\"/\"uuid\": \"$U\"/" "$f" > "$f.new" && mv "$f.new" "$f"   # no sed -i: BSD sed's differs
v "$T/t5"; check "forged uuid refused" 1 $rc
v "$T/t5" "$U"; check "forged uuid refused (expected uuid)" 1 $rc
cp -r "$T/hello" "$T/t6"; S sign "$T/t6" "$T/forger.key" > /dev/null
v "$T/t6" "$U"; check "update by another key refused" 1 $rc
cp -r "$T/hello" "$T/t7"; sed 's/"version": "1.0.0"/"version": "9.9.9"/' "$T/t7/mod.json" > "$T/t7/mod.json.new" && mv "$T/t7/mod.json.new" "$T/t7/mod.json"
v "$T/t7"; check "edited mod.json refused" 1 $rc
exit $fail
