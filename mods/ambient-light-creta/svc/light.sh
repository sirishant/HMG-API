#!/system/bin/sh
# The Creta's ambient light (blue only in the car's own software, no menu): the owner's colour
# and brightness from this mod's settings, through HMG-API's ambient_light command. The
# default (the car's own blue, the car's brightness) sends nothing. A choice is sent when it
# changes and again at each car start (ignition on), in case the light forgets it while off.
# Back to the car's own: the light is released, and HMG-API puts the car's reported light
# back; the same when the mod is turned off or removed.
# POLL_S can be set for off-car tests.
POLL_S=${POLL_S:-3}
say() {
    echo "$(date) $1"
    log -t HMG-API "[ambient-light-creta] $1"
}
# a value from setting-get's {"colour": "red", "brightness": "car"}
setting() {
    case $S in
        *"\"$1\""*) v=${S#*"\"$1\""}; v=${v#*:}; v=${v#*\"}; echo "${v%%\"*}" ;;
        *) echo car ;;
    esac
}
hex() {
    case $1 in
        red) echo ff321d ;;
        orange) echo ff6930 ;;
        amber) echo ffa646 ;;
        yellow) echo fff362 ;;
        white) echo ffffff ;;
        lime) echo cbfd63 ;;
        green) echo 68dd62 ;;
        mint) echo 3eee98 ;;
        cyan) echo 14faf9 ;;
        sky) echo 10a3fc ;;
        blue) echo 102ef2 ;;
        indigo) echo 401cef ;;
        violet) echo 781cef ;;
        purple) echo a923ef ;;
        magenta) echo d51bc0 ;;
        pink) echo ef0a74 ;;
        *) echo ;;
    esac
}
held=0 last= ign_was=
while :; do
    S=$(hmgapid setting-get 2>/dev/null)
    c=$(setting colour)
    b=$(setting brightness)
    i=$(echo $(hmgapid vehicle ignition 2>/dev/null))
    want="$c $b"
    again=
    [ "$i" = "ignition 1" ] && [ "$ign_was" != "ignition 1" ] && again=1
    ign_was=$i
    if [ "$want" != "$last" ] || [ -n "$again" ]; then
        h=$(hex "$c")
        # a new choice starts from the car's own light (a colour left over from before can't stay)
        if [ "$want" != "$last" ] && [ $held = 1 ]; then
            say "changed: $(hmgapid vehicle-do ambient_light release 2>&1)"
            held=0
        fi
        if [ -z "$h" ] && [ "$b" = car ]; then
            [ -n "$last" ] || say "the car's own light: nothing to send"
        else
            if [ -n "$h" ]; then
                r=$(hmgapid vehicle-do ambient_light color $h 2>&1)
                say "colour $c: $r"
                case $r in ok*) held=1 ;; esac
            fi
            if [ "$b" != car ]; then
                r=$(hmgapid vehicle-do ambient_light brightness $b 2>&1)
                say "brightness $b: $r"
                case $r in ok*) held=1 ;; esac
            fi
        fi
        last=$want
    fi
    sleep "$POLL_S"
done
