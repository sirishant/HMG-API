#!/system/bin/sh
# One-off test: can the Creta's blue-only ambient light show another colour?
# The car reports its light at every start (red 0, green 0, blue 162); HMG-API's
# ambient_light command saves that first, sends one colour, and on release sends the
# saved light back exactly. Runs once (a "done" file), only parked, 30 s after
# Android is up. Output: /data/hmgapi/logs/lamp-test-creta/lamp-test.log, and the
# HMG-API tag in the drive log.
D=${HMGAPI_DATA:-/data/local/tmp/lamp-test-creta}
mkdir -p "$D"
say() {
    echo "$(date) $1"
    log -t HMG-API "[lamp-test-creta] $1"
}
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}
if [ -f "$D/done" ]; then
    say "already done: $(cat "$D/done")"
    exit 0
fi
sleep 30
n=0
while :; do
    p=$(echo $(hmgapid vehicle gear.park))
    [ "$p" = "gear.park 1" ] && break
    n=$((n + 1))
    if [ $n -ge 60 ]; then
        say "not parked for 5 minutes ($p): no test"
        exit 0
    fi
    sleep 5
done
toast "Lamp test: ambient light red for 15 seconds"
r=$(hmgapid vehicle-do ambient_light color ff0000 2>&1)
say "red: $r"
case $r in
    ok*) ;;
    *)
        toast "Lamp test: refused ($r)"
        echo "refused: $r" > "$D/done"
        exit 0
        ;;
esac
sleep 15
r=$(hmgapid vehicle-do ambient_light release 2>&1)
say "release: $r"
case $r in
    *"(restored "*) toast "Lamp test done: blue put back. Did it turn red?" ;;
    *) toast "Lamp test: light NOT put back ($r). Tell Claude" ;;
esac
echo "$r" > "$D/done"
