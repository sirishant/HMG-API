#!/bin/sh
# Off-car test of HMG-API on a unit where a NEMESIS package already changed the same
# files (an older wireless CarPlay/AA package), and of that package uninstalled
# afterwards, the "wrong" order: NEMESIS puts its stock copies back and deletes its
# backup folder. The engine builds on the files as found when it's safe (merges, or a
# stock the mods accept), lets go of files no mod changes any more, and names the
# package when a file a mod adds is already there.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP" 2>/dev/null
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
D=$R/data/hmgapi
NB=$R/data/nemesis/backup
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 HMGAPI_ONEBIN=cvev_us \
        java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
FX=$R/system/onebin/cvev_us/featurexml.cfg
BX=$R/system/etc/permissions/b.xml
LIB=$R/system/lib/libleafiaaac.so
mkdir -p "$R/system/onebin/cvev_us" "$R/system/etc/permissions" "$R/system/lib" "$D/settings" "$D/mods/w" "$NB/wireless.projection" "$NB/carplay.wifi.audio"

# stock
printf 'a\n-/system/etc/permissions/com.hkmc.software.wifihotspot.xml\nb\n' > "$FX"
echo '<permissions stock="1"/>' > "$BX"
cp "$FX" "$T/fx.stock"; cp "$BX" "$T/bx.stock"
FX_STOCK=$(md5 "$FX"); BX_STOCK=$(md5 "$BX")
# a NEMESIS package installs: edits featurexml.cfg and b.xml, adds a library; it keeps its own stock copies
cp "$FX" "$NB/wireless.projection/featurexml.cvev_us.cfg"
cp "$BX" "$NB/wireless.projection/perm.b.xml"
echo absent > "$NB/carplay.wifi.audio/libleafiaaac.so.absent"
echo pkg-line >> "$FX"
echo '<permissions package="1"/>' > "$BX"
echo lib > "$LIB"
FX_PKG=$(md5 "$FX")

# a mod changing all three, as the wireless mod does
echo '<permissions mod="1"/>' > "$D/mods/w/b.xml"
echo modlib > "$D/mods/w/lib.so"
mod() {   # mod <extra change or empty>
    printf '{"id": "w", "version": "1.0.0"}\n' > "$D/mods/w/mod.json"
    printf '{"changes": [{"type": "lines", "path": "/system/onebin/{onebin}/featurexml.cfg", "stock": "any", "comment": ["-/system/etc/permissions/com.hkmc.software.wifihotspot.xml"]},
      {"type": "add", "path": "/system/etc/permissions/b.xml", "src": "b.xml", "stock": "%s"}%s]}\n' "$BX_STOCK" "$1" > "$D/mods/w/patches.json"
}
mod ', {"type": "add", "path": "/system/lib/libleafiaaac.so", "src": "lib.so"}'

eng apply
check "a file the mod adds is already there: nothing changes" 3 $rc
has "... the package that put it there named" "/system/lib/libleafiaaac.so: already on the head unit" "$out"
has "... and what to do" "the NEMESIS package carplay.wifi.audio changed it: uninstall carplay.wifi.audio in NEMESIS Manager first, or turn [w] off so the other mods apply" "$out"
check "... featurexml untouched" "$FX_PKG" "$(md5 "$FX")"

mod ''   # (the owner turned that part off)
eng apply
check "over the package: applies" 0 $rc
has "b.xml from the stock copy NEMESIS kept (perm. name)" "using the stock copy NEMESIS kept (wireless.projection)" "$out"
check "featurexml: the package's lines kept, the mod's edit on top" "a
#-/system/etc/permissions/com.hkmc.software.wifihotspot.xml
b
pkg-line" "$(cat "$FX")"
check "HMG-API's backup of featurexml: as it found it (the package's)" "$FX_PKG" "$(md5 "$D/stock/system/onebin/cvev_us/featurexml.cfg")"
check "HMG-API's backup of b.xml: the stock NEMESIS kept" "$BX_STOCK" "$(md5 "$D/stock/system/etc/permissions/b.xml")"

# the package is uninstalled now (the wrong order): NEMESIS puts its copies back and forgets them
cp "$T/fx.stock" "$FX"; cp "$T/bx.stock" "$BX"; rm -f "$LIB"; rm -rf "$NB/wireless.projection" "$NB/carplay.wifi.audio"
eng plan
check "after the package is gone: plan runs" 0 $rc
has "featurexml: built on again (a merge)" "rebase   /system/onebin/cvev_us/featurexml.cfg: changed outside HMG-API (now $FX_STOCK, the mods' changes merge onto any version of it)" "$out"
has "b.xml: built on again (a stock the mod accepts)" "rebase   /system/etc/permissions/b.xml: changed outside HMG-API (now $BX_STOCK, the stock HMG-API started from)" "$out"
eng apply
check "... applies" 0 $rc
check "featurexml: stock plus the mod's edit, the package's line gone" "a
#-/system/etc/permissions/com.hkmc.software.wifihotspot.xml
b" "$(cat "$FX")"
check "HMG-API's backup of featurexml: real stock now" "$FX_STOCK" "$(md5 "$D/stock/system/onebin/cvev_us/featurexml.cfg")"
check "b.xml: the mod's" "$(md5 "$D/mods/w/b.xml")" "$(md5 "$BX")"
mod ', {"type": "add", "path": "/system/lib/libleafiaaac.so", "src": "lib.so"}'
eng apply
check "the library goes in once the package is gone" "$(md5 "$D/mods/w/lib.so")" "$(md5 "$LIB")"
eng restore
check "restore: featurexml stock" "$FX_STOCK" "$(md5 "$FX")"
check "restore: b.xml stock" "$BX_STOCK" "$(md5 "$BX")"
check "restore: the library gone" absent "$(md5 "$LIB")"

# a file put back to stock by someone else while no mod changes it any more: let go, its old backup gone
mod ''
eng apply
cp "$T/bx.stock" "$BX"
: > "$D/mods/w/.disabled"
eng apply
check "mod off, b.xml stock again outside HMG-API: applies" 0 $rc
has "... let go" "no mod changes it now, so HMG-API lets it go" "$out"
check "... no longer HMG-API's" "" "$(grep -o '/system/etc/permissions/b.xml' "$D/state/files.json" || true)"
check "... its old backup gone" absent "$(md5 "$D/stock/system/etc/permissions/b.xml")"
check "... the file left as it is" "$BX_STOCK" "$(md5 "$BX")"
exit $fail
