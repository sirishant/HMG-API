#!/bin/sh
# Off-car test of the logger under mksh, with a stand-in logcat and a folder as the drive.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d); trap 'kill $P 2>/dev/null; [ -f "$T/logcat.pid" ] && kill "$(cat "$T/logcat.pid")" 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/usb0" "$T/usb1"
printf '#!/bin/sh\necho "$@" >> %s/logcat.args\necho $$ > %s/logcat.pid\nexec /bin/sleep 600\n' "$T" "$T" > "$T/bin/logcat"
printf '#!/bin/sh\necho "$*" >> %s/toasts\n' "$T" > "$T/bin/am"
printf '#!/bin/sh\necho test\n' > "$T/bin/getprop"
chmod +x "$T/bin/"*
S="$T/drive-log.sh"
while IFS= read -r l; do
    case "$l" in export\ PATH=*) echo "export PATH=$T/bin:/usr/bin:/bin" ;; *) printf '%s\n' "$l" ;; esac
done < svc/drive-log.sh > "$S"
fail=0
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fail=1; fi; }
DRIVES="$T/usb0 $T/usb1" POLL_S=0.2 mksh "$S" &
P=$!
sleep 0.6
check "no folder: nothing written" '[ -z "$(ls "$T/usb0")" ] && [ -z "$(ls "$T/usb1")" ] && [ ! -f "$T/logcat.args" ]'
mkdir "$T/usb1/creta-drive-logs"
sleep 0.8
check "folder: first log" '[ -f "$T/usb1/creta-drive-logs/log1/info.txt" ]'
check "drive without the folder untouched" '[ -z "$(ls "$T/usb0")" ]'
check "logcat limited and rotating" 'grep -q -- "-r 102400 -n 30" "$T/logcat.args" && grep -q "MicomD:D" "$T/logcat.args" && grep -q "\*:S" "$T/logcat.args"'
check "said so" 'grep -q "Drive log running" "$T/toasts"'
rm -rf "$T/usb1/creta-drive-logs/log1"
sleep 1
check "a new start gets a new logcat" '[ "$(grep -c MicomD "$T/logcat.args")" -ge 2 ]'
rm -rf "$T/usb1/creta-drive-logs"
OLD=$(cat "$T/logcat.pid")
sleep 1
check "drive gone: logcat stopped" '! kill -0 "$OLD" 2>/dev/null'
[ $fail = 0 ]
