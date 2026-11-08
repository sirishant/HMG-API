# Contributing

Thanks for helping. Bug reports, Car Reports and ideas go in
[issues](https://github.com/Open-HMG/HMG-API/issues/new/choose); code and docs in pull requests.

## Before a pull request

- Test off the car. No car files or keys needed for any of it.
  - `tools/test-all.sh --fast`: 27 of the 34 suites (the engine, the NEMESIS package
    scripts, the tools) in under a minute, on Linux or macOS. Needs a JDK, a C compiler
    and the Python packages at the versions in `tools/requirements.txt`:
    `pip install --require-hashes -r tools/requirements.txt` (in a venv if pip refuses).
    A plain `pip install bsdiff4` gets 1.4, which the delta tools don't work with.
  - `./build.sh`, then `tools/test-all.sh`: every suite, as CI runs them. Also needs
    clang + lld, qemu-arm (binfmt), mksh and the Android SDK, so in practice Linux.
    `git diff --name-only main | tools/test-select.py` names the tests your change
    needs, the same ones CI runs.
- Say which cars (model, region, software version) a change was tested on, or "off-car only".
- New or changed hook points: map them for every car (`tools/map-car.py`); if you have
  stock trees, `tools/test-hooks.py` must show no regressions.
- Mod scripts: only commands the head unit has (`tools/check-scripts.sh <mod>`).
- The mods HMG-API ships each have their own repo (Wireless CarPlay + Android Auto:
  [hmgapi-wireless-carplay-aa](https://github.com/Open-HMG/hmgapi-wireless-carplay-aa);
  [hmgapi-adb](https://github.com/Open-HMG/hmgapi-adb),
  [hmgapi-adbd](https://github.com/Open-HMG/hmgapi-adbd),
  [hmgapi-nemesis-shortcut](https://github.com/Open-HMG/hmgapi-nemesis-shortcut)): changes
  to a mod go there. A new hook point a mod needs starts here ([docs/mod-repos.md](docs/mod-repos.md), section 4).
- The wiki lives in `wiki/`; changes there are published when merged.
- Who reviews what (`.github/CODEOWNERS`): VysseCodes reviews root, signing, `/system`
  writes and CI (`.github/`, `core/hmgapid/`, `core/zygote/`, `core/loader/`,
  `install/`, `tools/lpatch/`, `SECURITY.md`, `CLAUDE.md`); everything else needs one
  of the Open-HMG maintainers.

## Rules

- Anything that writes to `/system` keeps a stock checksum check, a backup and an
  uninstall that restores the exact stock bytes.
- The repo holds no manufacturer apps or libraries: only hooks, hash-only recipes,
  delta patch sets, checksums and our own code.
- Never collect or publish per-car secrets (VIN, serials, keys, account tokens,
  `/data` contents), in code, reports, logs or issues.
- Vehicle commands go through the car's own services, never our own CAN frames.
- Commit messages describe the change in plain words.

By contributing you agree your work is licensed under the project's AGPL-3.0.
