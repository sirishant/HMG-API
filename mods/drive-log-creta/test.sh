#!/bin/sh
# Off-car test of the logger under mksh, with a stand-in logcat and a folder as the drive.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d); trap 'kill $P 2>/dev/null; [ -f "$T/logcat.pid" ] && kill "$(cat "$T/logcat.pid")" 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/usb0" "$T/usb1"
printf '#!/bin/sh\necho "$@" >> %s/logcat.args\necho $$ > %s/logcat.pid\nwhile :; do echo "11-04 09:00:00.000 1 1 I HMG-API : a line of the log"; /bin/sleep 0.05; done\n' "$T" "$T" > "$T/bin/logcat"
printf '#!/bin/sh\necho "$*" >> %s/toasts\n' "$T" > "$T/bin/am"
printf '#!/bin/sh\necho test\n' > "$T/bin/getprop"
echo 6.3G > "$T/free"
printf '#!/bin/sh\necho "Filesystem Size Used Free Blksize"\necho "$1 7.5G 1.2G $(cat %s/free) 32768"\n' "$T" > "$T/bin/df"
# the car's toolbox ls -l: perms owner group size date time name (no link count)
printf '#!/bin/sh\n/bin/ls -l "$2" | while read -r p n o g s r; do echo "$p $o $g $s $r"; done\n' > "$T/bin/ls"
chmod +x "$T/bin/"*
S="$T/drive-log.sh"
while IFS= read -r l; do
    case "$l" in export\ PATH=*) echo "export PATH=$T/bin:/usr/bin:/bin" ;; *) printf '%s\n' "$l" ;; esac
done < svc/drive-log.sh > "$S"
fail=0
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fail=1; fi; }
DRIVES="$T/usb0 $T/usb1" POLL_S=0.2 SEG_BYTES=2000 mksh "$S" &
P=$!
sleep 0.6
check "no folder: nothing written" '[ -z "$(ls "$T/usb0")" ] && [ -z "$(ls "$T/usb1")" ] && [ ! -f "$T/logcat.args" ]'
mkdir "$T/usb1/creta-drive-logs"
sleep 0.8
check "folder: first log" '[ -f "$T/usb1/creta-drive-logs/log1/info.txt" ]'
check "drive without the folder untouched" '[ -z "$(ls "$T/usb0")" ]'
check "logcat to its own numbered files, no logcat rotation" '! grep -q -- "-f\|-r \|-n " "$T/logcat.args" && grep -q "MicomD:D" "$T/logcat.args" && grep -q "\*:S" "$T/logcat.args" && [ -s "$T/usb1/creta-drive-logs/log1/logcat-001.txt" ]'
sleep 1.5
check "a new file every SEG_BYTES, the old one complete" '[ -s "$T/usb1/creta-drive-logs/log1/logcat-002.txt" ] && [ "$(ls "$T/usb1/creta-drive-logs/log1" | grep -c logcat-)" -ge 2 ]'
check "one logcat running" '[ "$(pgrep -f "$T/bin/logcat" | wc -l)" = 1 ]'
check "said so" 'grep -q "Drive log running" "$T/toasts"'
rm -rf "$T/usb1/creta-drive-logs/log1"
sleep 1
check "a new start gets a new logcat" '[ "$(grep -c MicomD "$T/logcat.args")" -ge 2 ]'
echo 50M > "$T/free"
sleep 1
check "nearly full: the running log stops" 'grep -q "logging stopped" "$T/toasts"'
n=$(ls "$T/usb1/creta-drive-logs" | wc -l)
sleep 1
check "nearly full: no new log" '[ "$(ls "$T/usb1/creta-drive-logs" | wc -l)" = "$n" ]'
echo 6.3G > "$T/free"
rm -rf "$T/usb1/creta-drive-logs"
OLD=$(cat "$T/logcat.pid")
sleep 1
check "drive gone: logcat stopped" '! kill -0 "$OLD" 2>/dev/null'
[ $fail = 0 ]
