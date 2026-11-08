#!/system/bin/sh
# One-off test 2: does the Creta's blue-only ambient light take a brightness?
# Test 1 (1.0.1): red sent the way the car's own settings app does; the car took it and the
# light stayed blue at the same brightness. This one sends brightness, the other value of the
# same call (setSMLConfiguration item 5): full (10) for 15 s, off (0) for 10 s, then the
# car's own level back. The car reports its light at every start as 00 00 00 00 00 a2 02 08:
# blue 0xa2, brightness byte 2, which is level 1 in HMG-API's terms (the car's 1 is off,
# 2-11 the levels 1-10; read from the EV6's report, educated guess for the Creta's 8 bytes).
# HMG-API never hears the Creta's report, so its release can't put the light back by itself:
# this sends level 1 itself, then releases (which asks the car to report, into the drive log).
# Runs once (result2), only parked, 30 s after Android is up. Output: the HMG-API tag in the
# drive log.
D=${HMGAPI_DATA:-/data/local/tmp/lamp-test-creta}
mkdir -p "$D"
say() {
    echo "$(date) $1"
    log -t HMG-API "[lamp-test-creta] $1"
}
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}
if [ -f "$D/result2" ]; then
    say "already done: $(cat "$D/result2")"
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
# HMG-API asks the owner on screen the first time; until they answer the command is
# refused as waiting, and asking again puts the question back (every 30 s): up to 5 minutes
n=0
while :; do
    r=$(hmgapid vehicle-do ambient_light brightness 10 2>&1)
    case $r in
        ok*) break ;;
        *"waiting for the owner"*)
            [ $n = 0 ] && { say "full: $r"; toast "Lamp test 2: allow it on the screen (HMG-API's question)"; }
            n=$((n + 1))
            if [ $n -ge 10 ]; then
                say "no answer to HMG-API's question in 5 minutes: no test (tried again at the next start)"
                exit 0
            fi
            sleep 30
            ;;
        *)
            say "full: $r"
            toast "Lamp test 2: refused ($r)"
            echo "refused: $r" > "$D/result2"
            exit 0
            ;;
    esac
done
say "full: $r"
toast "Lamp test 2: ambient light at full brightness for 15 seconds"
sleep 15
r=$(hmgapid vehicle-do ambient_light brightness 0 2>&1)
say "off: $r"
toast "Lamp test 2: ambient light off for 10 seconds"
sleep 10
r=$(hmgapid vehicle-do ambient_light brightness 1 2>&1)
say "back to level 1: $r"
sleep 2
r=$(hmgapid vehicle-do ambient_light release 2>&1)
say "release: $r"
toast "Lamp test 2 done: did the light get brighter, then go off, then come back?"
echo "level 1 sent; release: $r" > "$D/result2"
