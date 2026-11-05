#!/bin/sh
# Off-car test of the USB stick installer (troubleShooter, as the DSP OTA runs it)
# and of the NEMESIS package's scripts, on a fake head unit: install, update,
# uninstall from the stick; NEMESIS install, update (HMG-API stays), uninstall.
# /system must come back byte-identical. Builds out/dist (tools/mkinstallers.sh) if it isn't there.
set -e
cd "$(dirname "$0")/../.."
SH=${UNIT_SH:-sh}
V=$(cat out/payload/VERSION 2>/dev/null)
NB=$PWD/out/dist/hmgapi-$V-nemesis.zip UZ=$PWD/out/dist/hmgapi-$V-usb.zip
# made again when the build is newer than them (old downloads would test old packaging)
[ -f "$NB" ] && [ -f "$UZ" ] && [ ! out/payload/BUILD -nt "$NB" ] || tools/mkinstallers.sh >/dev/null
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
NZ=$T/hmgapi.zip   # the package itself, from inside the NEMESIS download
R=$T/root; S=$T/stick
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
# the downloads, as NEMESIS's USB handler reads a stick (native/usb.c): NEMESIS.data at the root with
# a type= and relative paths (no leading /, no .., no shell characters) to package zips,
# whose own NEMESIS.data names them
ini() { unzip -p "$1" "$2" | grep "^$3=" | head -1 | cut -d= -f2-; }
relok() { case "$1" in ''|/*|*..*|*[\;\|\&\`\$\'\"\(\)\<\>]*) echo no ;; *) echo yes ;; esac; }
n=${UZ##*/}
check "$n: NEMESIS.data says install" install "$(ini "$UZ" NEMESIS.data type)"
rel=$(ini "$UZ" NEMESIS.data path)
check "$n: path= is one NEMESIS takes ($rel)" yes "$(relok "$rel")"
check "$n: path= is in the zip" "$rel" "$(unzip -Z1 "$UZ" | grep -x "$rel" || true)"
unzip -p "$UZ" "$rel" > $T/inner.zip
check "$n: the package it installs is HMG-API's" hmgapi "$(ini $T/inner.zip NEMESIS.data id)"
# the NEMESIS download: a Package Store list (type=list, NEMESIS 1.16; StoreList.parseList):
# [package.0], [package.1], ... with no gaps, each a path= to a package on the stick, HMG-API first
n=${NB##*/}
unzip -p "$NB" NEMESIS.data > $T/list.data
check "$n: NEMESIS.data is a Package Store list" "list" "$(grep '^type=' $T/list.data | cut -d= -f2)"
check "$n: [package.N] sections numbered from 0 with no gaps" yes "$(grep '^\[package\.' $T/list.data | python3 -c 'import sys; n=[int(l.strip()[9:-1]) for l in sys.stdin]; print("yes" if n and n == list(range(len(n))) else "no")')"
rows=$(grep -c '^\[package\.' $T/list.data)
check "$n: one row per package on the stick" "$rows" "$(unzip -Z1 "$NB" | grep -c '^packages/.*\.zip$')"
i=0
while [ $i -lt $rows ]; do
    sec=$(sed -n "/^\[package\.$i\]/,/^\[/p" $T/list.data)
    rel=$(echo "$sec" | grep '^path=' | cut -d= -f2-)
    check "$n: row $i: path= is one NEMESIS takes ($rel)" yes "$(relok "$rel")"
    unzip -p "$NB" "$rel" > $T/inner.zip 2>/dev/null || :
    id=$(ini $T/inner.zip NEMESIS.data id)
    check "$n: row $i: the zip is the package its name says ($rel)" "packages/$id.zip" "$rel"
    check "$n: row $i: named, with a short-desc and a YYYY-MM-DD date" yes "$(echo "$sec" | grep -q '^name=.' && echo "$sec" | grep -q '^short-desc=.' && echo "$sec" | grep -qE '^published=[0-9]{4}-[0-9]{2}-[0-9]{2}$' && echo yes)"
    [ $i -eq 0 ] && check "$n: row 0 is HMG-API" hmgapi "$id"
    i=$((i + 1))
done
unzip -p "$NB" packages/hmgapi.zip > "$NZ"
# the package's own NEMESIS.data, descriptor 1.1 as NEMESIS reads it (04-Packages.md, native/meta.c) and as
# the Package Store takes it (07-Submit-a-Package.md): version= is the descriptor's, package-version= ours
val() { ini "$NZ" NEMESIS.data "$1" | sed 's/^"\(.*\)"$/\1/'; }
check "package: descriptor version 1.1" 1.1 "$(val version)"
check "package: type= (1.1), not types=" "application " "$(val type) $(val types)"
check "package: package-version is dotted numbers" yes "$(val package-version | grep -qxE '[0-9]+(\.[0-9]+)*' && echo yes)"
check "package: a beta's package-version comes before its release" yes "$(case $V in *-beta.*) python3 -c 'import sys; a=[int(x) for x in sys.argv[1].split(".")]; b=[int(x) for x in sys.argv[2].split("-")[0].split(".")]; print("yes" if a < b else "no")' "$(val package-version)" "$V" ;; *) echo yes ;; esac)"
check "package: short-desc at most 64 characters" yes "$([ -n "$(val short-desc)" ] && [ ${#V} -gt 0 ] && python3 -c 'import sys; print("yes" if len(sys.argv[1]) <= 64 else "no")' "$(val short-desc)")"
check "package: detailed-desc at most 256 characters" yes "$(python3 -c 'import sys; print("yes" if len(sys.argv[1]) <= 256 else "no")' "$(val detailed-desc)")"
check "package: os, models and depends set" yes "$([ -n "$(val os)" ] && [ -n "$(val models)" ] && unzip -p "$NZ" NEMESIS.data | grep -q '^depends=' && echo yes)"
check "package: os any, so every Gen5W car is a full match in NEMESIS" any "$(val os)"
check "package: refuses HMG Leafia Plus' package" yes "$(val depends | tr ',' '\n' | grep -qx '!wireless.carplay.aa' && echo yes)"
check "package: NEMESIS Manager's Launch runs bin/open.sh" "kind=sh path=bin/open.sh" "$(echo $(unzip -p "$NZ" NEMESIS.data | grep -A3 '^\[app.0\]' | grep '^kind=\|^path='))"
check "... naming no app, so NEMESIS never pm uninstalls HMG-API's app" "" "$(unzip -p "$NZ" NEMESIS.data | grep '^package=\|^component=')"
check "... and the script is in the package" yes "$(unzip -Z1 "$NZ" | grep -qx bin/open.sh && echo yes)"
check "package: a boot service, so NEMESIS's svcmgr starts HMG-API too (hmgapid boot)" "name=boot kind=sh path=bin/start.sh uid=root mode=background yes" "$(echo $(unzip -p "$NZ" NEMESIS.data | grep -A5 '^\[service.0\]' | grep -v '^\[')) $(unzip -Z1 "$NZ" | grep -qx bin/start.sh && echo yes)"
check "the package on its own (for the Package Store) is the one in the NEMESIS download" "$(md5sum < "$NZ")" "$(md5sum < "$PWD/out/dist/hmgapi-$V-nemesis-package.zip")"
# every download unzips the same on a FAT/exFAT stick, Windows and macOS: no two names that differ
# only in case (payload/MAPS next to payload/maps lost one of them there), nothing FAT can't name
names() {   # names <zip>: what's wrong with its names, nothing if all is well
    unzip -Z1 "$1" | python3 -c '
import sys
seen = {}
for n in (l.rstrip("\n") for l in sys.stdin):
    parts = n.rstrip("/").split("/")
    for i in range(1, len(parts) + 1):
        p = "/".join(parts[:i])
        if seen.setdefault(p.lower(), p) != p:
            print("case clash: %s / %s" % (seen[p.lower()], p))
        if any(c in parts[i - 1] for c in "<>:\"\\|?*") or parts[i - 1].endswith((".", " ")):
            print("not a FAT name: " + p)
' | sort -u
}
for z in "$NB" "$UZ" "$PWD/out/dist/hmgapi-$V-adb.zip" "$NZ"; do
    check "${z##*/}: every name fits a FAT stick, any case" "" "$(names "$z")"
done
check "the NEMESIS download says how to use it" yes "$(unzip -p "$NB" README.txt | grep -q 'top level of a USB stick' && echo yes)"

mkdir -p $T/bin
printf '#!/bin/sh\n[ -f "$1" ] || exit 1\nset -- $(md5sum "$1"); echo "$1 $2"\n' > $T/bin/md5
printf '#!/bin/sh\nexit 0\n' > $T/bin/chown
chmod +x $T/bin/*
run() { set +e; out=$(cd ${CWD:-$T} && env PATH="$T/bin:$PATH" ROOT=$R CAPCHECK=0 HMGAPI_USB_DIRS="$T/none $S" $SH "$@" 2>&1); rc=$?; set -e; }
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }
installed() { [ -f $R/system/bin/hmgapid ] && [ -f $R/system/bin/app_process.hmgapi-stock ] && echo yes || echo no; }

mkdir -p $R/system/bin $R/system/lib $R/system/framework $R/system/etc $R/data/dalvik-cache $R/data/local/tmp
STOCK=$HOME/hmg-private/stock/ev6-sysimg/bin/app_process
if [ -f "$STOCK" ]; then cp $STOCK $R/system/bin/app_process; else head -c 9000 /dev/urandom > $R/system/bin/app_process; fi
chmod 755 $R/system/bin/app_process
printf 'echo qb stock\n' > $R/system/etc/qb_runtime.sh
BEFORE=$(tree)
# the stick, as tools/mkinstallers.sh lays it out, with this tree's scripts
mkdir -p $S/HMGAPI/install
unzip -q "$NZ" 'payload/*' -d $T/pk
P=$S/HMGAPI/install/payload; mv $T/pk/payload $P
cp install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/stock.txt install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh $P/
[ -f "$STOCK" ] || echo "$(md5sum < $R/system/bin/app_process | cut -c1-32) test stock" > $P/stock.txt
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh MAPS.md5 > MANIFEST)
TS=$PWD/install/usb/troubleShooter

mv $S/HMGAPI $S/X
run $TS
has "no HMGAPI folder: says so" "no HMGAPI folder on the USB stick. Nothing was changed" "$out"
check "... and fails" 1 $rc
mv $S/X $S/HMGAPI

run $TS
has "stick install: done" "HMG-API installed, restarting" "$out"
check "stick install: exit 0" 0 $rc
check "stick install: HMG-API is in" yes "$(installed)"
has "install log on the stick" "installed. Restart the head unit" "$(cat $S/HMGAPI/install.log)"
check "staging cleaned up" no "$([ -e $R/data/local/tmp/hmgapi-usb ] && echo yes || echo no)"
run $TS
has "stick again: update over itself" "HMG-API's wrapper is in place" "$(cat $S/HMGAPI/install.log)"

: > $S/HMGAPI/UNINSTALL
run $TS
has "UNINSTALL on the stick: uninstalled" "HMG-API uninstalled, restarting" "$out"
check "/system byte-identical to stock" "$BEFORE" "$(tree)"
has "uninstall log on the stick" "HMG-API uninstalled" "$(cat $S/HMGAPI/install.log)"
rm -r $R/data/hmgapi
run $TS
has "UNINSTALL with nothing installed: says so" "HMG-API isn't installed. Nothing was changed" "$out"
rm $S/HMGAPI/UNINSTALL

# another car's app_process: refused unless the owner puts HMGAPI/FORCE on the stick
cp $R/system/bin/app_process $R/../app_process.mine
echo "another car" >> $R/system/bin/app_process
OTHER=$(tree)
run $TS
has "unknown app_process: refused, says how to go on" "an empty file HMGAPI/FORCE on the USB stick" "$(cat $S/HMGAPI/install.log)"
check "... nothing changed" "$OTHER" "$(tree)"
: > $S/HMGAPI/FORCE
run $TS
has "HMGAPI/FORCE: installed at the owner's risk" "isn't a known stock build; HMGAPI_FORCE=1, going on at your own risk" "$(cat $S/HMGAPI/install.log)"
: > $S/HMGAPI/UNINSTALL
run $TS
check "... and uninstalled back to that car's own bytes" "$OTHER" "$(tree)"
rm $S/HMGAPI/UNINSTALL $S/HMGAPI/FORCE
rm -rf $R/data/hmgapi
mv $R/../app_process.mine $R/system/bin/app_process

# NEMESIS: the package unpacked where NEMESIS puts it; postinstall runs there
A=$R/data/nemesis/apps/hmgapi
mkdir -p $A; unzip -q "$NZ" -d $A; rm -r $A/payload; cp -r $P $A/payload
has "package metadata" "postinstall=scripts/postinstall.sh" "$(cat $A/NEMESIS.data)"
CWD=$A run scripts/postinstall.sh
has "NEMESIS install: done" "installed. Restart the head unit" "$out"
check "NEMESIS install: HMG-API is in" yes "$(installed)"
mkdir -p $R/data/local/tmp/nemesis/stage/hmgapi
cp $A/NEMESIS.data $R/data/local/tmp/nemesis/stage/hmgapi/   # the new package, staged by nemesis install --update
CWD=$A run scripts/preuninstall.sh
has "NEMESIS update: preuninstall keeps HMG-API" "HMG-API stays installed" "$out"
check "... still installed" yes "$(installed)"
CWD=$A run scripts/postinstall.sh
has "... and the new postinstall updates it" "HMG-API's wrapper is in place" "$out"
rm $R/data/local/tmp/nemesis/stage/hmgapi/NEMESIS.data   # left over from an interrupted install: not a package
CWD=$A run scripts/preuninstall.sh
has "NEMESIS uninstall: uninstalled (a leftover stage doesn't stop it)" "HMG-API uninstalled" "$out"
check "/system byte-identical to stock again" "$BEFORE" "$(tree)"
exit $fail
