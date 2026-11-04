#!/system/bin/sh
# Records the cluster frames and navigation messages to a USB drive that has a
# creta-drive-logs folder. The folder is the switch: a drive without it is never written to.
# Each car start with the drive in gets its own logN folder; nothing is ever overwritten.
# The log goes to logcat-001.txt, -002 ... a new file every SEG_BYTES (20 MB); nothing is dropped
# (the car's logcat caps its own -r/-n rotation at 1 MB x 10, which lost the start of long drives).
# No new log below 300 MB free on the drive, and a running one stops below 100 MB.
# DRIVES, POLL_S and SEG_BYTES can be set for off-car tests.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
FOLDER=creta-drive-logs
DRIVES=${DRIVES:-/storage/usb0 /storage/usb1 /storage/usb2}
POLL_S=${POLL_S:-5}
SEG_BYTES=${SEG_BYTES:-20971520}
TAGS="HMG-API:V DiPO:D iAP2:D Iap2:D IAP2:D hostapd:I dnsmasq:I SoftapController:V Tethering:D WifiStateMachine:D wpa_supplicant:I MicomD:D NAVDUMP:D ClusterService:D NavigationCluster:V GalReceiver:V AAProjection:D CarPlay_:I ModeKeyDispatcher:V AndroidRuntime:E ActivityManager:W EvCar:V EvModelSender:V EvDriveLog:V CarPlayEV:V *:S"

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}

# free space on the drive in MB (toolbox df: Filesystem Size Used Free Blksize, with K/M/G);
# empty when df's output isn't understood
free_mb() {
    _f=$(df "$1" 2>/dev/null | { read -r _h; read -r _a _b _c _d _e; echo "$_d"; })
    case "$_f" in
        *G) _f=${_f%G}; echo $(( ${_f%.*} * 1024 )) ;;
        *M) _f=${_f%M}; echo "${_f%.*}" ;;
        *K) echo 0 ;;
    esac
}

low() {
    _m=$(free_mb "$1")
    [ -n "$_m" ] && [ "$_m" -lt "$2" ]
}

# file size via ls -l (no stat on the unit); empty if missing or not understood
size_of() {
    _l=$(ls -l "$1" 2>/dev/null) || return 0
    set -- $_l
    case "$4" in
        ''|*[!0-9]*) ;;
        *) echo "$4" ;;
    esac
}

seg() {
    case $1 in
        ?) echo "$R/logcat-00$1.txt" ;;
        ??) echo "$R/logcat-0$1.txt" ;;
        *) echo "$R/logcat-$1.txt" ;;
    esac
}

# logcat to standard output: the car's logcat never rotates or drops that
start_logcat() {
    set -f
    logcat -v threadtime $TAGS > "$1" 2>/dev/null &
    L=$!
    set +f
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
    if low "$D" 300; then
        [ "$FULL" = "$D" ] || toast "Drive log: the drive is nearly full, not logging"
        FULL=$D
        sleep "$POLL_S"
        continue
    fi
    FULL=
    N=1
    while [ -d "$D/log$N" ]; do N=$((N + 1)); done
    R=$D/log$N
    if ! mkdir -p "$R"; then
        sleep "$POLL_S"
        continue
    fi
    echo "log $N date $(date) uptime $(cat /proc/uptime)" > "$R/info.txt"
    getprop ro.build.product >> "$R/info.txt"
    S=1
    start_logcat "$(seg $S)"
    sync
    toast "Drive log running"
    while [ -d "$R" ] && kill -0 "$L" 2>/dev/null; do
        sleep "$POLL_S"
        sync
        if low "$D" 100; then
            toast "Drive log: the drive is nearly full, logging stopped"
            FULL=$D
            break
        fi
        _s=$(size_of "$(seg $S)")
        if [ -n "$_s" ] && [ "$_s" -gt "$SEG_BYTES" ]; then
            O=$L
            S=$((S + 1))
            start_logcat "$(seg $S)"
            kill "$O" 2>/dev/null
        fi
    done
    kill "$L" 2>/dev/null
    sync
    sleep "$POLL_S"
done
