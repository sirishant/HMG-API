# hmgapid

HMG-API's root daemon and its client, one static ARM binary (no libc, raw syscalls).

```
hmgapid daemon            # needs full root, refuses otherwise
hmgapid boot              # the boot hook: returns at once; detached, starts the daemon and runs /data/hmgapi/boot.sh
hmgapid <op> [args...]    # any user: asks the daemon, prints its output, exits with its code
```

| Caller (SO_PEERCRED) | Ops |
|---|---|
| root (0), shell (2000: adb) | everything: `sh <command>`, `remount rw\|ro`, plus the HMG-API ops |
| the `hmgapi.app` uid (from `/data/system/packages.list`) | `mods`, `enable <id>`, `disable <id>`, `setting-get <id>`, `setting-set <id> <json>`, `log`, `reboot`, `services [mod]`, `service-restart\|stop\|start <mod> <name>`, `service-log <mod> <name>`, `usb [mod]`, `vehicle <reading>…` |
| an enabled mod's app (its `apps`, `state/apps.txt`) or service (by session) | its own mod only: `whoami`, `setting-get`, `setting-set <json>`, `services`, `service-restart\|stop\|start <name>`, `service-log <name>`, `usb`, `vehicle <reading>…` (the readings its mod.json lists) |
| anyone else | `ping`, `version` |

**Services.** The daemon runs the enabled mods' services (`state/services.txt`, written by the engine from `mod.json`): as root, system or the mod's app uid, each in its own session, with its own folder (`run/<mod>/<name>`) and log (`logs/<mod>/<name>.log`), started at boot or once `sys.boot_completed` is 1, restarted with backoff, stopped (TERM, then KILL) when the mod is turned off. It also runs `engine apps` once Android is up and whenever `state/apps.txt` changes.

**Boot.** `hmgapid boot` is the boot hook's only line (`install-recovery.sh`, `qb_runtime.sh`) and what HMG-API's NEMESIS service runs. It returns at once, so the boot script goes straight on to NEMESIS's start. A detached child (own session, none of the caller's fds) starts the daemon if none runs (the zygote wrapper usually started it already) and, if it's the first call this boot, runs `/data/hmgapi/boot.sh` (HMG-API's boot steps: binds, the app's installer, `hook-check.sh`, the NEMESIS sync, `engine recover`; output in `boot.log`). The lock is `/dev/.hmgapi-boot`, made with `O_EXCL` on tmpfs (gone at every boot). So whichever trigger comes first runs the boot steps, and the others do nothing.

Abstract socket `@hmgapid` (no file permissions involved; the bind is also the single-instance lock). Request `LFD1` + argc + length-prefixed args; response frames `O` (output, streamed) and `X` (exit code). Log: `/data/hmgapi/hmgapid.log`.

Started from NEMESIS's daemon (`native/su.c`, `util.c`); what reviewing it turned up and how hmgapid differs:

- any app got root (sockets `0666`, peer uid only logged) → ops per caller
- the pidfile "already running" check matched an unrelated process with the same pid and start time (seen on a car: the daemon was gone for a boot) → no pidfile
- `umask 0` left files world-writable → `umask 077`, and `/data/hmgapi` folders are set to `0700` at every start
- `/system` remounted rw at every start and left rw → only on request
- output buffered until the command exits, silently cut at 512 KB → streamed
- a client that sent nothing held a forked worker forever → 30 s request timeout, at most 8 workers
- started from adb it ran with adbd's capability set (CapEff `0xc0`) → refuses to start without full root

`./build.sh` → `out/hmgapid`. `./test.sh` runs the real daemon and client under qemu-arm against a temp root (the four caller kinds, streaming, 220 KB output, mod and settings ops, services (start, backoff, restart, stop, removal, identity by session), USB folders, bad ids, single instance, oversized and malformed requests).

Tested on a US EV6 (260507), 2026-10-27, started through the NEMESIS daemon (no boot hook yet): refuses weak root, `version`, `mods`, `sh` (CapEff `3fffffffff`, exit codes).

**Vehicle readings.** `vehicle <name>…` checks each name against
`state/vehicle.txt` (`<mod> <reading>`, written by the engine from each enabled mod's
`"vehicle": {"read": [...]}`) and passes the request to HMG-API's vehicle service
(`hmgapi.vehicle.Hub`, abstract socket `@hmgapi-vehicle`, root callers only), which
answers `<name> <value>` lines. hmgapid runs that service (mod `hmgapi`, the only
service allowed to run `/system/framework/hmgapi-hook.jar`) while any enabled mod
declares readings.

**Network keeper.** HMG-API's other own service, `hmgapi net`, runs
`state/net.sh` (the only script a `hmgapi` service may run; the engine writes it,
`hmgapi.engine.Network`) while any enabled mod has `"network"` asks or some are still
in: it applies `state/net.rules`, re-applies what the stock firewall removes, and takes
everything out when it's stopped (TERM).
