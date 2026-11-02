#!/system/bin/sh
# Records the cluster frames and navigation messages to a USB drive that has a
# creta-drive-logs folder. The folder is the switch: a drive without it is never written to.
# Each car start with the drive in gets its own logN folder; nothing is ever overwritten.
# Files rotate at 100 MB, 30 at most (3 GB). DRIVES and POLL_S can be set for off-car tests.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
FOLDER=creta-drive-logs
DRIVES=${DRIVES:-/storage/usb0 /storage/usb1 /storage/usb2}
POLL_S=${POLL_S:-5}

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}

folder() {
    for _d in $DRIVES; do
        if [ -d "$_d/$FOLDER" ]; then
            echo "$_d/$FOLDER"
            return 0
        fi
    done
    return 1
}

while :; do
    D=$(folder)
    if [ -z "$D" ]; then
        sleep "$POLL_S"
        continue
    fi
    N=1
    while [ -d "$D/log$N" ]; do N=$((N + 1)); done
    R=$D/log$N
    if ! mkdir -p "$R"; then
        sleep "$POLL_S"
        continue
    fi
    echo "log $N date $(date) uptime $(cat /proc/uptime)" > "$R/info.txt"
    getprop ro.build.product >> "$R/info.txt"
    logcat -v threadtime -f "$R/logcat.txt" -r 102400 -n 30 MicomD:D NAVDUMP:D ClusterService:D NavigationCluster:V GalReceiver:V AAProjection:D AndroidRuntime:E ActivityManager:W EvCar:V EvModelSender:V EvDriveLog:V '*:S' &
    L=$!
    sync
    toast "Drive log running"
    while [ -d "$R" ] && kill -0 "$L" 2>/dev/null; do
        sleep "$POLL_S"
        sync
    done
    kill "$L" 2>/dev/null
    sync
    sleep "$POLL_S"
done
