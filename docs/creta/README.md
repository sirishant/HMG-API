# Hyundai Creta Electric India: clean install

**Creta-specific, right-hand drive.** This is for the Creta Electric sold in India, product
`SU2IEV.IND`, head unit software `260430`, and nothing else. There is no guarantee for any other
car, region or software version, and the mods refuse to install on them. Use at your own risk.

| What | Where it comes from | Status on the Creta |
|---|---|---|
| NEMESIS 1.16.2 | [xwtk/hmg_nemesis](https://github.com/xwtk/hmg_nemesis) | Earlier version in daily use; 1.16 not yet tried |
| HMG-API core | This fork of [VysseCodes/HMG-API](https://github.com/VysseCodes/HMG-API), adds the Creta map | **Not yet tried on this car** |
| `wireless-carplay-aa` 2.2.0 | This fork | Same behaviour road-tested as Leafia; this form not yet tried |
| `wireless-creta-rhd` | This fork | Logic road-tested on the car; this form not yet tried |
| `ev-routing-creta` | The egmp-aa-ev-feat fork | Logic road-tested on the car; this form not yet tried |
| `drive-log-creta` | This fork | Not yet tried |
| CarPlay | Part of `wireless-carplay-aa` | **Not tried on this car.** Leave off for now |

Work through the stages in order, parked, car in Ready. Do one stage per sitting if you like: the
car works normally between stages. Remove the USB drive once each stage is done, because a drive
in the port can block wireless Android Auto.

## Stage 0: pre-flight (reads only, changes nothing)

1. Copy `preflight/NEMESIS.data` and `preflight/NEMESIS.sh` to the top level of a USB drive.
2. Plug it in, tap **Yes**, wait for "Pre-flight done".
3. Keep `PREFLIGHT.txt` from the drive.

**Check:** the product line says `SU2IEV.IND`. On the car this was written on, `app_process` is
`33f798115689d1859e8d2159dc88e7dc`, the same build as the EV6's that HMG-API knows, and `libdvm.so`
is `e3b8998a3c72a3e2065dcf2c2b35ae73`, whose layouts match what HMG-API's hooks expect. If yours
differ, stop: nothing below has been checked for your unit.

## Stage 1: remove every package

Skip a package you don't have. Uninstall the newest first.

1. Unplug any drive. NEMESIS Manager > Packages > **AA EV** package > Uninstall.
2. Real restart: car off, open the driver's door, wait for the screen to go dark.
3. Car in Ready. NEMESIS Manager > Packages > **HMG Leafia Plus** > Uninstall.
4. Real restart again.

**Check:** only `usb-handler` is listed, Android Auto works by cable or not at all, and wireless
Android Auto is gone. That is stock behaviour. Run the pre-flight again: the GPM, navigation, DiPO
and library lines must match `mods/wireless-carplay-aa/patches/su2iev/stock`. Don't go on until
they do: a hook map only fits stock files.

## Stage 2: update NEMESIS to 1.16.2

From 1.15 this goes through Engineering Mode, as the first install did:

1. Copy `ota_package.tar` from hmg_nemesis to a blank FAT32 or exFAT drive. Don't unpack it.
2. Plug it in. "No Supported Files" messages are expected.
3. Engineering Mode > USB Copy > Update DSP > Update OTA. Wait for it to finish and restart.

**Check:** Manager shows My Packages, Package Store, Android Apps and Settings.

NEMESIS 1.16 changes two things that matter here:

- **Run USB NEMESIS.sh is gone.** Use a drive with `NEMESIS.data` and tap Yes, or the drive log mod.
- **With Android down**, a drive only runs if it also has a file named `NEMESIS.emergency`.

### To start completely from scratch

Only if you want to prove the whole flow. Everything is removed, including NEMESIS itself.

1. Stage 1 first. Manager > Settings > **Wipe**, confirm twice. The head unit restarts.
2. Check NEMESIS Manager is gone.
3. Install NEMESIS again as in the steps above.

There is no NEMESIS recovery while it's removed, so don't stop halfway.

## Stage 3: HMG-API core

1. Unzip this fork's `hmgapi-<version>-nemesis.zip` to the top level of a blank drive. It carries
   the Creta hook map.
2. Plug it in and confirm "HMG-API". It doesn't restart by itself: until you restart, the app says
   "Hooks not running", which is expected. Use **Restart unit** in the app, then remove the drive.

The official download works too, but has no hook map for this car: in the app, use Settings > Hook
map > **Map this car from its files**, which only works on the stock files from Stage 1, then restart.

The bar then says "Hooks off for this car", because a new map must be checked on the car first:

3. Open the app from My Packages > HMG-API > **Launch**. Settings > Hook map > **Check the map on
   this car**. It only reads, and takes a minute or so.
4. When it passes, tap **Restart unit**.

**Check:** the bar says hooks are on, and HMG-API's tile is in the app menu.

If the head unit fails to start three times, HMG-API's boot guard turns its hooks off by itself.
If hooks stay off, make a Car Report and stop: don't add mods yet.

## Stage 4: Wireless mod and Creta display

1. Put `wireless-carplay-aa-2.2.0.zip` and `wireless-creta-rhd-1.0.0.zip` in `HMGAPI/updates/` on a
   drive. The app warns that the author isn't on HMG-API's list: that's expected for these mods.
2. HMG-API app > **Check USB updates**, install both.
3. In the Wireless mod's settings, turn **off** "Wireless CarPlay" and "CarPlay music and Siri".
4. **Apply changes**, restart.

**Check:** wireless Android Auto connects, and a route shows on the cluster with near scenes, the
bar and correct roundabouts. Turn `wireless-creta-rhd` off in the app and you get upstream's
behaviour, which is a useful test if something looks wrong.

## Stage 5: EV routing, then logging

1. Add `ev-routing-creta-1.0.0.zip` the same way, Apply changes, restart.
   **Check:** Maps shows battery on arrival.
2. Add `drive-log-creta-1.0.0.zip` if you want logs.

**Drive log:** make a folder `creta-drive-logs` on a drive and leave it plugged in. A log starts by
itself at every car start, about 0.3 MB a minute, each in its own folder. No folder, no log. Turn
the car off before removing the drive.

**EV logs:** make a folder `egmp-aa-ev-logs` on a drive. Logs copy over whenever it's plugged in.

## Updating

Put the new mod zip in `HMGAPI/updates/` and use **Check USB updates**. Updates must be signed by
the same key as the installed mod.

## If something goes wrong

| Problem | What to do |
|---|---|
| A mod misbehaves | Turn it off in the app, Apply changes |
| The app won't open | My Packages > HMG-API > Uninstall, restart |
| NEMESIS Manager won't open | Drive with the `hmgapi-<version>-usb.zip` contents and an empty `HMGAPI/UNINSTALL`, then Engineering Mode > USB Copy > DSP > Update OTA |
| Android won't start | The boot guard turns hooks off after three failed starts. If it still won't start, use a recovery drive with an empty `NEMESIS.emergency` at its top level |

Uninstalling HMG-API puts every file and setting back to the stock bytes. `UNINSTALL-PURGE` also
deletes its mods and settings.
