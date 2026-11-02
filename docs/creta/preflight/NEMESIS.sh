#!/system/bin/sh
# Creta pre-flight for HMG-API. READ ONLY: writes one file to this drive, changes nothing on
# the car. Records checksums of the files HMG-API relies on and what is installed. Collects
# no VIN, serials, phone data or /data contents.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
case "$0" in
    /*/NEMESIS.sh) KIT=${0%/*} ;;
    *) KIT=$(pwd) ;;
esac
R=$KIT/PREFLIGHT.txt
{
    echo "date $(date)"
    echo "product $(getprop ro.build.product)"
    echo "onebin $(getprop ro.mobis.onebindir) target $(getprop ro.mobis.targetdir)"
    echo "platform $(getprop ro.board.platform) android $(getprop ro.build.version.release)"
    echo "stepofspec $(getprop ro.product.opt.stepofspec)"
    echo "wifi state $(getprop persist.wifi.state) ium $(getprop persist.ium.support)"
    echo "--- files"
    for f in /system/bin/app_process /system/lib/libdvm.so /system/lib/libandroid_runtime.so \
        /system/etc/install-recovery.sh /system/etc/qb_runtime.sh /system/xbin/nemesis \
        /system/framework/core.odex /system/framework/framework.odex /system/framework/automotive.odex \
        /system/app/HKMC_GPM.apk /system/app/HKMC_GPM.odex /system/app/HKMC_DiPO.apk /system/app/HKMC_DiPO.odex \
        /system/app/HKMC_Navi_ExSL.apk /system/app/HKMC_Navi_ExSL.odex /system/app/Iap2ServerApp.apk \
        /system/app/Iap2ServerApp.odex /system/app/HKMC_BtSettings.apk /system/lib/libautoreceiver_infobank.so \
        /system/lib/libCarPlayCore.so /system/lib/libCarPlayAudio.so /system/lib/libCarPlayUtils.so; do
        if [ -f "$f" ]; then
            md5 "$f"
        else
            echo "absent $f"
        fi
    done
    echo "--- packages"
    /system/xbin/nemesis pkg-list
    echo "--- leftovers"
    ls /data/hmgapi /data/leafia 2>&1
} > "$R" 2>&1
sync
am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "Pre-flight done, safe to remove the drive" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
exit 0
