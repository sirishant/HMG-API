# Installing HMG-API

Takes about 10 minutes. Park the car and keep it on (the head unit restarts once).

**Which download?** Get it from the [Releases](https://github.com/Open-HMG/HMG-API/releases) page:

| You have | Use | Steps |
|---|---|---|
| Nothing installed, no computer | `hmgapi-<version>-usb.zip` | [USB stick](#usb-stick) |
| NEMESIS | `hmgapi-<version>-nemesis.zip` | [NEMESIS](#nemesis) |
| A computer with adb | `hmgapi-<version>-adb.zip` | [adb](#adb) |

All three install the same thing, and [uninstalling](Uninstalling) puts every file
back exactly as it was.

## USB stick

1. Unzip `hmgapi-<version>-usb.zip` onto an empty USB stick (FAT32), at the top level.
2. Plug the stick into the car's USB port.
3. Open **Engineering Mode → USB Copy → DSP → Update OTA**.
   The screen shows "HMG-API … installing", then "HMG-API installed, restarting".
   Error 684 on the OTA screen afterwards is expected: nothing is undone.
4. Take the stick out once the head unit has restarted.

What happened is written to `HMGAPI/install.log` on the stick.

## NEMESIS

Needs NEMESIS 1.16 or newer (update NEMESIS first if Manager has no Package Store).

1. Unzip `hmgapi-<version>-nemesis.zip` onto the top level of a USB stick (FAT32 or
   exFAT): the stick gets `NEMESIS.data`, a `packages` folder and a README. Nothing
   else NEMESIS-related on the stick.
2. Plug it in once Android is up. NEMESIS Manager opens its Package Store on the
   stick's list: HMG-API and the mods it comes with. Install **HMG-API**, and any mods
   you want (a mod on its own installs HMG-API first).
3. Restart the head unit when it says so, and take the stick out.

Updating through NEMESIS later keeps your mods.

**HMG-API and NEMESIS show the same thing.** However you install HMG-API or a mod
(the app, adb, a USB stick or NEMESIS), NEMESIS 1.16 or newer lists it in My Packages,
and removing it anywhere takes it off there too. HMG-API always does the work; a
check after every install and uninstall, and at each start, fixes anything that
doesn't match. To run it by hand: `adb shell hmgapid engine nemesis-sync`.

**Packages HMG-API can't be installed with.** NEMESIS refuses to install HMG-API (or
one of its mods) while a package that changes the same files is installed, and the
other way round. Uninstall that package in NEMESIS Manager first:

| Package | Can't be installed with |
|---|---|
| HMG-API | HMG Leafia Plus (`wireless.carplay.aa`), `carplay.session`, `voicehandler.vr-gates` (the last two until we know they don't clash) |
| Wireless CarPlay + Android Auto (mod) | NEMESIS's Wireless Projection (`wireless.projection`) and Projection TBT (`projection.tbt`) |
| adb (mod) | NEMESIS's ADB over TCP (`adb.tcp`) |

## adb

```
unzip hmgapi-<version>-adb.zip
```
```
cd hmgapi-<version>-adb
```
```
./hmgapi-adb.sh install
```

Then restart the head unit. The first install needs root from NEMESIS.

## After installing

**No HMG-API tile in the app menu?** The tile and the ★ choice are added by HMG-API's
hooks, so a car without a hook map yet doesn't get them. Open the app from **NEMESIS
Manager → My Packages → HMG-API → Launch** instead, or over adb:
`adb shell "hmgapid sh 'sh /data/hmgapi/open.sh'"`. Both install the app first if it's
missing.

Open **HMG-API** from the app menu (or put it on a ★ key). The bar at the top says
whether HMG-API's hooks are on for your car:

- **Hooks on**: everything works. Add mods ([Installing and removing mods](Installing-and-removing-mods)).
- **Hooks off for this car**: HMG-API still works (mods' files, settings, apps and
  services), but mods that change the car's own apps wait for your car's hook map.
  Make a [Car Report](Car-Report) and send it: that's what's needed to add your car.

A USB stick left in the port can start wired Android Auto and keep wireless from
connecting, so take it out after installing.
