#!/bin/sh
# Off-car test of the light service under mksh, with a stand-in hmgapid (settings, ignition,
# and the ambient_light commands it was sent).
set -e
cd "$(dirname "$0")"
T=$(mktemp -d); trap 'kill $P 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/bin"
cat > "$T/bin/hmgapid" <<STUB
#!/bin/sh
case "\$1" in
    setting-get) cat "$T/settings" ;;
    vehicle) echo "ignition \$(cat "$T/ign")" ;;
    vehicle-do) shift; echo "\$*" >> "$T/sent"; echo "ok \$*" ;;
esac
STUB
printf '#!/bin/sh\n:\n' > "$T/bin/log"
chmod +x "$T/bin/"*
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
sent() { s=$(cat "$T/sent" 2>/dev/null | while read -r l; do printf '%s|' "$l"; done); : > "$T/sent"; echo "$s"; }
set_() { echo "{\"colour\": \"$1\", \"brightness\": \"$2\"}" > "$T/settings"; sleep 0.5; }
echo 1 > "$T/ign"
set_ car car
PATH="$T/bin:$PATH" POLL_S=0.1 mksh svc/light.sh > "$T/out" 2>&1 &
P=$!
sleep 0.5
check "defaults: nothing sent" "" "$(sent)"
set_ red car
check "red: one colour" "ambient_light color ff321d|" "$(sent)"
set_ red car
check "unchanged: nothing more" "" "$(sent)"
echo 0 > "$T/ign"; sleep 0.4; echo 1 > "$T/ign"; sleep 0.4
check "car on again: red again" "ambient_light color ff321d|" "$(sent)"
set_ red 5
check "brightness 5: release, colour, brightness" "ambient_light release|ambient_light color ff321d|ambient_light brightness 5|" "$(sent)"
set_ car 5
check "back to the car's colour, brightness kept: release, brightness" "ambient_light release|ambient_light brightness 5|" "$(sent)"
set_ car car
check "all the car's own: release only" "ambient_light release|" "$(sent)"
echo 0 > "$T/ign"; sleep 0.4; echo 1 > "$T/ign"; sleep 0.4
check "car on again with the car's own: nothing" "" "$(sent)"
set_ violet car
check "violet" "ambient_light color 781cef|" "$(sent)"
[ $fail = 0 ] && echo "ok    Ambient light (Creta): all checks"
[ $fail = 0 ]
