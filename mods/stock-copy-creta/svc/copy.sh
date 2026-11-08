#!/system/bin/sh
# One-off: the car's stock Bluetooth app (apk + odex, firmware files, the same on every Creta
# with this software) to creta-stock/ on the USB drive, so HMG-API hook points can be mapped
# from it off the car. Nothing from /data. Waits up to 10 minutes for the drive's folder.
D=${HMGAPI_DATA:-/data/local/tmp/stock-copy-creta}
mkdir -p "$D"
say() {
    echo "$(date) $1"
    log -t HMG-API "[stock-copy-creta] $1"
}
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}
if [ -f "$D/result" ]; then
    say "already done: $(cat "$D/result")"
    exit 0
fi
n=0
while :; do
    u=
    for f in $(hmgapid usb 2>/dev/null); do u=$f; break; done
    [ -n "$u" ] && break
    n=$((n + 1))
    if [ $n -ge 120 ]; then
        say "no creta-stock folder on a USB drive in 10 minutes (tried again at the next start)"
        exit 0
    fi
    sleep 5
done
ok=0
for f in /system/app/Bluetooth.apk /system/app/Bluetooth.odex; do
    if [ -f "$f" ] && cp "$f" "$u/" 2>/dev/null; then
        ok=$((ok + 1))
        md5 "$f" >> "$u/md5.txt" 2>/dev/null
    else
        say "not copied: $f"
    fi
done
ls /system/app > "$u/system-app.txt" 2>/dev/null
ls /system/framework > "$u/system-framework.txt" 2>/dev/null
sync
say "copied $ok file(s) to $u"
toast "Stock copy: $ok file(s) on the USB drive. You can remove the mod."
echo "copied $ok to $u" > "$D/result"
