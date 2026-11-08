#!/bin/sh
# Off-car test of the script check (hmgapi.engine.ScriptCheck): shell forms it must
# read as the shell does (continued lines, a|b) case patterns, nested parentheses in
# $((...))) without reporting missing commands, and the real misses it must still find.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d)
trap 'rm -rf "$CP" "$T"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
run() { printf '%s\n' "$2" > "$T/$1.sh"; java -cp "$CP" hmgapi.engine.ScriptCheck core/runtime/unit-commands.txt "$T/$1.sh" 2>/dev/null | sed "s|$T/||" || true; }

check "continued line: its options aren't commands" "ok    cont.sh" "$(run cont 'am broadcast -a x.TOAST \
    -e x.MESSAGE "$1" \
    --ez x.SHOWING true >/dev/null 2>&1 || true')"
check "continued line: a missing command after the break is found" "FAIL  cont2.sh: sed (not on the head unit; use \${var#x} \${var%x} \${var/a/b} or a case)" "$(run cont2 'cat f \
    | sed s/a/b/')"
check "an escaped backslash at the end doesn't continue" "ok    esc.sh" "$(run esc 'echo a\\
ls')"
check "a|b) case patterns aren't commands" "ok    case.sh" "$(run case 'case "$c" in
    [0-9]) d=$c;; a|A) d=10;; b|B) d=11;; f|F) d=15;;
    '"''"'|-|*[!0-9-]*) ;;
esac')"
check "a command after a case pattern is still checked" "FAIL  case2.sh: tail (not on the head unit; use keep the last line in a while read loop)" "$(run case2 'case "$x" in
    a|b) tail -n 1 f;;
esac')"
check "nested parentheses in arithmetic" "ok    arith.sh" "$(run arith 'm=$(( (b & 8388607) | 8388608 ))
e=$(( ((b >> 23) & 255) - 127 ))
if [ $(( $(date +%s) - t )) -gt 5 ]; then echo old; fi')"
check "a function definition is not a case pattern" "ok    fn.sh" "$(run fn 'f() { echo hi; }
f')"
check "still finds plain misses" "FAIL  plain.sh: head (not on the head unit; use read the first lines in a while read loop)" "$(run plain 'grep x f | head -n 1')"
[ $fail = 0 ] || exit 1
