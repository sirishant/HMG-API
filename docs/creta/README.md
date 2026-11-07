# Hyundai Creta Electric India: getting started

Wireless Android Auto and CarPlay, turn-by-turn on the cluster, and EV routing with battery on
arrival in Google Maps (Android Auto) and Apple Maps (CarPlay, where iOS offers it).

**Creta-specific, right-hand drive.** This is for the Creta Electric sold in India, product
`SU2IEV.IND`, head unit software `260430`, and nothing else. The mods refuse any other car, region
or software. There is no guarantee, and you use it at your own risk.

## Before you start

- Download everything from the [latest release](https://github.com/sirishant/HMG-API/releases/latest).
- Use a blank FAT32 or exFAT USB drive.
- Stay parked with the car in Ready.
- **Leave Android running for 2 minutes after every start before you restart.** Quick restarts turn
  HMG-API's hooks off.

Already have HMG Leafia Plus, the AA EV package, or NEMESIS's own wireless projection or
turn-by-turn packages? Uninstall them first in NEMESIS Manager > My Packages, newest first, with a real
restart after each: car off, driver's door open, wait for the screen to go dark. NEMESIS won't install
HMG-API next to them.

## 1. Install NEMESIS

1. Copy `ota_package.tar` from [hmg_nemesis](https://github.com/xwtk/hmg_nemesis) to the drive. Don't
   unpack it.
2. Plug it in. "No Supported Files" messages are expected.
3. Engineering Mode > USB Copy > Update DSP > Update OTA. Wait for it to finish and restart.

NEMESIS Manager now shows My Packages, Package Store, Android Apps and Settings. Clear the drive.

**Already have NEMESIS?** Update it to 1.16.7 or later (older versions can't install from the
HMG-API drive): turn on Bluetooth tethering on your phone,
connect it to the car, then NEMESIS Manager > Settings > **Check for updates**. The head unit's Wi-Fi
can't join a phone's Wi-Fi hotspot while it runs wireless projection.

## 2. Install HMG-API and the mods

1. Unzip `hmgapi-1.0.7-nemesis.zip` to the top level of the drive.
2. Plug it in. NEMESIS's Package Store opens with the drive's packages. Install them in this order:
   1. **HMG-API**
   2. **Wireless CarPlay + Android Auto**
   3. **Creta cluster display (RHD)**, **Projection extras**, **EV routing (Creta)**, **CarPlay EV
      routing (Creta)**, **Climate (Creta)** and **Drive log (Creta)**

**Climate (Creta)** sets Driver Only each time the car is turned on with the climate on, until
the front passenger buckles up; then it's back to Sync. When the passenger's knob drops Sync, the
driver's side follows it and Sync comes back on. Sync turned off by hand stays off. The first
time, HMG-API asks on screen whether the mod may press the climate's buttons: tap **Allow**.
3. **Remove the drive.**
4. Open the app: NEMESIS Manager > My Packages > HMG-API > **Launch**. Tap **Restart unit**.
5. Open the app the same way. Settings > Hook map > **Check the map on this car**. It takes about a
   minute.
6. Tap **Restart unit**.

The app's title bar now says "Hooks on (map checked)", and HMG-API has a tile in the app menu.

## 3. Connect your phone

- **Android:** connect wireless Android Auto from the car's phone connection screen.
- **iPhone:** pair it from the same screen and allow CarPlay. For Apple Maps EV routing, after
  installing or updating CarPlay EV routing, delete the pairing on both the car and the iPhone and
  pair again (iOS keeps what the car offered at the first pairing), then add the car in Apple Maps
  (your picture, then Vehicles).

Keep USB drives out of the port unless you're logging.

## Logs

Use a drive without the install files: with `NEMESIS.data` on it, NEMESIS opens the Package Store
at every start.

- **Drive log:** make a folder `creta-drive-logs` on a drive and leave it plugged in. A log starts at
  every car start. Turn the car off before removing the drive.
- **EV log:** make a folder `egmp-aa-ev-logs` on a drive. Logs copy over whenever it's plugged in.

## Updating

Only use updates from this project's releases. The official HMG-API download doesn't include the
Creta map or the fixes this setup needs. If NEMESIS Manager offers an "HMG-API" update from the
Package Store, decline it.

From HMG-API 1.0.3, update from the app: make a folder `HMGAPI/updates` on a drive and copy the
release's `hmgapi-<version>.zip` and the mod zips into it (only the ones you want). Plug it in, then
in the app **Check USB updates**. Install HMG-API first if it's listed, then the mods. Your settings
stay. Then **Apply changes** and **Restart unit**. NEMESIS Manager's My Packages follows by itself:
HMG-API lists what it installed there at the new versions.

On HMG-API 1.0.1 or 1.0.2 (the 1.3.0 release or earlier), HMG-API only takes updates signed with the
official key, so update once through the Package Store: unzip the new `hmgapi-<version>-nemesis.zip`
to a drive, plug it in, update HMG-API first, then the mods, remove the drive, **Apply changes** and
**Restart unit**. After that, use the app as above.

## If something goes wrong

| What you see | What to do |
|---|---|
| A mod misbehaves | Turn it off in the app, then **Apply changes** |
| "Hooks not running" and no HMG-API tile | Reinstall HMG-API from the drive (section 2, steps 1 and 2), then **Apply changes** and **Restart unit** |
| The app won't open | NEMESIS Manager > My Packages > HMG-API > Uninstall, then restart |
| Android won't start | Use a drive with the `nemesis.zip` contents and an empty file named `NEMESIS.emergency` at its top level |

Uninstalling HMG-API puts every changed file and setting back to stock.
