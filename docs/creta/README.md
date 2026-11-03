# Hyundai Creta Electric India: getting started

Wireless Android Auto and CarPlay, turn-by-turn on the cluster, and Google Maps EV routing with
battery on arrival.

**Creta-specific, right-hand drive.** This is for the Creta Electric sold in India, product
`SU2IEV.IND`, head unit software `260430`, and nothing else. The mods refuse any other car, region
or software. There is no guarantee, and you use it at your own risk.

## Before you start

- Download everything from the [latest release](https://github.com/sirishant/HMG-API/releases/latest).
- Use a blank FAT32 or exFAT USB drive.
- Stay parked with the car in Ready.
- **Leave Android running for 2 minutes after every start before you restart.** Quick restarts turn
  HMG-API's hooks off.

Already have HMG Leafia Plus or the AA EV package? Uninstall them first in NEMESIS Manager > Packages,
newest first, with a real restart after each: car off, driver's door open, wait for the screen to go
dark.

## 1. Install NEMESIS

1. Copy `ota_package.tar` from [hmg_nemesis](https://github.com/xwtk/hmg_nemesis) to the drive. Don't
   unpack it.
2. Plug it in. "No Supported Files" messages are expected.
3. Engineering Mode > USB Copy > Update DSP > Update OTA. Wait for it to finish and restart.

NEMESIS Manager now shows My Packages, Package Store, Android Apps and Settings. Clear the drive.

**Already have NEMESIS?** Update it to 1.16.4 or later: turn on Bluetooth tethering on your phone,
connect it to the car, then NEMESIS Manager > Settings > **Check for updates**. The head unit's Wi-Fi
can't join a phone's Wi-Fi hotspot while it runs wireless projection.

## 2. Install HMG-API

1. Unzip `hmgapi-1.0.0-beta.6-nemesis.zip` to the top level of the drive.
2. Plug it in and confirm "HMG-API". When it's done, **remove the drive**.
3. Open the app: NEMESIS Manager > My Packages > HMG-API > **Launch**. Tap **Restart unit**.
4. Open the app the same way. Settings > Hook map > **Check the map on this car**. It takes about a
   minute.
5. Tap **Restart unit**.

The app's title bar now says "Hooks on (map checked)", and HMG-API has a tile in the app menu.

## 3. Add the mods

1. Clear the drive and make a folder `HMGAPI/updates`. Put these in it:
   - `wireless-carplay-aa-2.4.1.zip`
   - `wireless-creta-rhd-1.2.0.zip`
   - `projection-extras-1.0.0.zip`
   - `ev-routing-creta-1.0.1.zip`
   - `drive-log-creta-1.0.3.zip`
2. Plug it in. HMG-API app > **Check USB updates**.
3. Install the Wireless mod first, then the others. A warning about the author is expected.
4. Tap **Apply changes**, then **Restart unit**.
5. Remove the drive.

## 4. Connect your phone

- **Android:** connect wireless Android Auto from the car's phone connection screen.
- **iPhone:** pair it from the same screen and allow CarPlay.

Keep USB drives out of the port unless you're logging.

## Logs

- **Drive log:** make a folder `creta-drive-logs` on a drive and leave it plugged in. A log starts at
  every car start. Turn the car off before removing the drive.
- **EV log:** make a folder `egmp-aa-ev-logs` on a drive. Logs copy over whenever it's plugged in.

## Updating

Only use updates from this project's releases. The official HMG-API download doesn't include the
Creta map or the fixes this setup needs. If NEMESIS Manager offers an "HMG-API" update from the
Package Store, decline it.

- **A mod:** put the new zip in `HMGAPI/updates`, then use **Check USB updates**, **Apply changes** and
  **Restart unit**.
- **HMG-API:** unzip the new `nemesis.zip` to a drive and confirm the reinstall. Your mods and settings
  stay. Remove the drive, then tap **Apply changes** and **Restart unit**.
- **After a NEMESIS update:** plug in the HMG-API drive once and confirm, so HMG-API's start-up hook
  goes back in.

## If something goes wrong

| What you see | What to do |
|---|---|
| A mod misbehaves | Turn it off in the app, then **Apply changes** |
| "Hooks not running" and no HMG-API tile | Repeat section 2, steps 1 and 2, then **Apply changes** and **Restart unit** |
| The app won't open | NEMESIS Manager > My Packages > HMG-API > Uninstall, then restart |
| Android won't start | Use a drive with the `nemesis.zip` contents and an empty file named `NEMESIS.emergency` at its top level |

Uninstalling HMG-API puts every changed file and setting back to stock.
