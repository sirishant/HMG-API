# HMG-API installer: shared functions, sourced by install/uninstall on the head unit.
# mksh-safe: the unit has no sed, head, tail, wc or awk. ROOT prefixes paths in tests.
ROOT=${ROOT:-}
HOOK_BEGIN="# HMGAPI_HOOK begin"
HOOK_END="# HMGAPI_HOOK end"

# The boot hook: `hmgapid boot`, which returns at once (the boot script goes straight on
# to NEMESIS's start) and, detached, starts hmgapid and runs /data/hmgapi/boot.sh
# (HMG-API's boot steps) once per boot.
hook_block() {
    echo "$HOOK_BEGIN (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)"
    echo 'if [ -x /system/bin/hmgapid ]; then /system/bin/hmgapid boot; fi'
    echo "$HOOK_END"
}

# copy <file> without the HMG-API block to stdout
hook_strip() {
    _in=0
    while IFS= read -r _l || [ -n "$_l" ]; do
        case "$_l" in
            "$HOOK_BEGIN"*) _in=1; continue ;;
            "$HOOK_END"*) _in=0; continue ;;
        esac
        [ $_in = 1 ] || echo "$_l"
    done < "$1"
}

# hook_add <file> top|end: put the block after the first line (top: the file may
# end with "exit 0", as NEMESIS's install-recovery.sh does) or at the end. A
# missing file is created (and marked, so hook_remove deletes it again).
hook_add() {
    _f=$ROOT$1
    _t=$_f.hmgapi-new
    if [ ! -f "$_f" ]; then
        { echo "#!/system/bin/sh"; echo "# HMGAPI_CREATED"; hook_block; echo "exit 0"; } > "$_t" || return 1
        chmod 755 "$_t"
    else
        hook_strip "$_f" > "$_t.body" || return 1
        if [ "$2" = top ]; then
            _first=1
            while IFS= read -r _l || [ -n "$_l" ]; do
                echo "$_l"
                if [ $_first = 1 ]; then hook_block; _first=0; fi
            done < "$_t.body" > "$_t"
        else
            { cat "$_t.body"; hook_block; } > "$_t"
        fi
        rm -f "$_t.body"
        chmod 755 "$_t"
        [ -x "$_f" ] || chmod 644 "$_t"
    fi
    mv "$_t" "$_f"
}

# hook_remove <file>: take the block out; delete the file if HMG-API created it
hook_remove() {
    _f=$ROOT$1
    [ -f "$_f" ] || return 0
    if grep -q "^# HMGAPI_CREATED" "$_f"; then
        rm -f "$_f"
        return
    fi
    grep -q "^$HOOK_BEGIN" "$_f" || return 0
    hook_strip "$_f" > "$_f.hmgapi-new" || return 1
    chmod 755 "$_f.hmgapi-new"
    [ -x "$_f" ] || chmod 644 "$_f.hmgapi-new"
    mv "$_f.hmgapi-new" "$_f"
}
