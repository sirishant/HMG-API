# Vehicle commands: review before they're offered

The project rule: no CAN frames of our own; commands go through the car's own
services, and each one is reviewed on its own before mods get it. These are the
seven the community projects use today, found by matching their raw
`service call <service> <number>` and `transact(<number>)` calls against the car's
`automotive` framework (US EV6 260507). The numbers are the method's position in
the interface on that firmware; HMG-API calls the **method by name**, mapped per car
like the hook points, so a firmware that reorders the interface can't send a
different command.

**Decided (owner, 2026-10-30): 1-6 offered as proposed; 7 offered as option (b).**
All seven are built (`hmgapi.vehicle.Commands`, guards tested off-car against a fake
car); none has been sent from HMG-API on a car yet. A mod lists them in mod.json
(`"vehicle": {"commands": ["charge_door"]}`) and asks `hmgapid vehicle-do
charge_door open` or `api.command("charge_door", "open")`.

| Command | Arguments |
|---|---|
| `charge_door` | `open` / `close` |
| `seat_heat` | `driver` / `passenger` / `rear_left` / `rear_right`, `off` / `low` / `medium` / `high` (a two-level seat, the EV6's rear: `off` / `low` / `high`, from the seat's own option) |
| `seat_vent` | `driver` / `passenger` (no rear ventilation), `off` / `low` / `medium` / `high` (added at the owner's request, 2026-10-31) |
| `wheel_heat` | `on` / `off` |
| `conditioning` | `start` / `auto-start` (both optionally `<km> <minutes>`, then optionally `warm`) / `route <km> <minutes>` / `stop` / `status` |
| `ambient_light` | `color <rrggbb>` / `brightness <0-10>` / `release` / `owner_change release\|keep` |
| `guide_lines` | `on` / `off` / `restore` (cars with a plain rear camera; a 360-view car draws its own and refuses, EV6 2026-10-31) |
| `startup_notice` | `dismiss` (only after the owner turns on "Startup notice" in the HMG-API app, or `hmgapid allow startup_notice on`) |
| `climate` | `sync on` / `sync off` / `driver_only on` / `driver_only off` / `driver_temp up` / `driver_temp down` (Creta owner's request, 2026-11-07; see 9) |

To check on a car: `charge_door open` sends `false` (what Charge Port Button sends and
reports opening the door); `close` sends `true`, not yet seen. Ambient light: the car
has no getter for the owner's colour, so letting go asks the car to report its own
light (`requestSMLStatus`); whether that puts the owner's colour back is to be seen.
Conditioning: whether the car's own navigation overrides it while routing.

| # | Command | Framework method | Today | Proposed guard |
|---|---|---|---|---|
| 1 | Charge door open/close | `BCMManager.requestChargeLockDoor(boolean)` (`IBCMService` 84) | Charge Port Button (MAP ×3) | park only; once per 5 s |
| 2 | Front seat heat level | `USMManager.setFrontSeatHeatDrvStep/AstStep(int)` (`IUSMService` 213/214) | Cold-start Heat | levels the car's own UI offers; seat occupied not checked (car does) |
| 3 | Steering wheel heat on/off | `VRManager.set_StrgWhlHeated_Cmd_AVN(int)` (`IVRService` 16) | Cold-start Heat | on/off only |
| 4 | Battery conditioning | `HEVManager.setBattCondEvStation(int,int)`, `setBattCondRouteDis(int)`, `setBattCondRemainTime(int)` (`IHEVService` 62/65/66) | Launchpad (manual), EGMP (automatic) | one shared state; a manual stop wins; logged with the mod |
| 5 | Ambient light colour and brightness | `VehicleInfoManager.setSMLConfiguration(int,int)`, `setSMLRGB(byte[],byte[],byte[])` (`ISystemService` 77/78) | Ambient Sound | a lease: one holder; the owner's colour back when it ends |
| 6 | Parking guide lines on/off | `PgsManager.requestGuideLineState(int)` (`IPgsService` 33; read: 59) | Rear Dashcam | restore the previous state; only while the camera is the mod's |
| 7 | Startup notice dismiss | `IMicomFactoryService.hideConsentScreen()` (`com.hkmc.factory` 82) | Factory82 | see below: needs your call |

Reads used alongside them (not commands, no review needed beyond mapping): gear in
P (`ISystemService.getGearPStatus`, 36), HEV status flags (`IHEVService.getHEVStatus`,
3), conditioning indicator (`IBCMService.getBattCondIndSta`, 109), seat heat state
(`USMManager.getFrontSeatHeat_*State`), guide line state (`getRvmGuideLineState`, 59),
12V and battery health (`tm_hscan_service` 137/214, a telematics interface outside
`automotive`; to be mapped the same way).

## 1. Charge door

- **What stock does:** Kia Connect's remote "open/close charge door" goes through
  this exact method: `HKMC_TAppService` calls `BCMManager.requestChargeLockDoor`
  (with `false` for one remote command and another value for the other).
- **Risk:** low. The BCM decides whether the door may move; a door opening while
  driving is the case to prevent.
- **Guard:** gear in P (checked right before the call), at most once per 5 s,
  logged with the mod. Open question: which value opens and which closes (Charge Port
  Button sends `0`); to confirm on a car before it's offered.

## 2. Front seat heat

- **What stock does:** the climate/settings screens use `USMManager` for seat
  settings; the steps are the same levels the car's buttons offer.
- **Risk:** low; the car's own logic applies (occupancy, automatic shut-off).
- **Guard:** only the levels the car's `get…Option` reports as available; logged.
  Cold-start Heat converts its levels to the car's enum; HMG-API takes plain levels
  (0 off, 1-3) and converts per car.

## 3. Steering wheel heat

- **What stock does:** the voice command "turn on the heated steering wheel" goes
  through `VRManager.set_StrgWhlHeated_Cmd_AVN`; Engineering Mode's voice test calls
  it with 1 and 0.
- **Risk:** low; the same path a spoken command takes.
- **Guard:** 0/1 only.

## 4. Battery conditioning

- **What stock does:** these are how the car's navigation tells the battery system
  "routed to a charging station, X away / X minutes left", which makes the car
  precondition the battery. The projects use them to start conditioning without a
  navigation route (Launchpad sends a station type and distance/time).
- **Risk:** medium. The values describe a route that doesn't exist; the car spends
  energy heating or cooling the pack. Two mods (manual and automatic) can fight.
- **Guard:** one conditioning state for the unit, requests logged with the mod;
  a manual stop wins over an automatic start; values limited to what the car's
  navigation sends; stopped (values cleared) when the mod that started it is
  turned off.
- **Route values (EV routing):** the BMS times the heating from the distance and
  time it's given, so a mod routing to a real charger (EGMP AA EV: Google Maps'
  charging stop) starts with `auto-start <km> <minutes>` and keeps them current with
  `route <km> <minutes>` (only the mod conditioning may); without values it's 9 km,
  18 min (Launchpad's). 0-2046 km and 0-1022 min, as the navigation sends them
  (2047 / 1023 mean "no route"). The mod whose automatic request it is ending it
  (it arrived, or the route ended) isn't a manual stop: its next automatic start
  isn't blocked. A stop by the owner or another mod still is. Open question:
  whether the car's own navigation overrides these when it has a route; to check
  on a car.
- **The station event is the start, sent once:** distance, time, then station
  `2,3`. HEVService puts the station back to Invalid about 120 ms after any set,
  so it's never held. The 5 s keep-alive resends the distance and time only, as
  Launchpad's does; resending the station too repeated the start, and on a US EV6
  (260507) the car's conditioning indicator (`IBCMService` 109) then stayed off.
  The EV6 can take up to 5 minutes to switch on (Launchpad's field notes; about a
  minute on the Ioniq 5), and may not heat a pack at 20 °C / 68 °F or warmer.
- **Checks before a start** (as Launchpad's `PreconditioningController`): refused
  when the car isn't READY (HEV `EV_RDY`, 44), Battery Conditioning is off in the EV
  settings (HEV `EV_BATTERYCONDITIONING`, 94: 2 on), or the indicator (`IBCMService`
  `getBattCondIndSta`, 109) already shows the car conditioning on its own. A warm pack
  is refused with a way past it: a last word `warm` sends it anyway (a warning and an
  override, never a silent block). The limit is the pack's minimum temperature
  (`IClusterService.requestBatteryInfoReq`, answered by
  `INavigationClusterListener.onHandleBatteryInfoResp(int[8])`, `[1]`): EV6 and Ioniq 6
  20 °C / 68 °F, Ioniq 5 21 °C / 70 °F (Launchpad's `PreconditioningProfile`, from a Kia
  bulletin and owners' reports, not BMS calibration). A value that can't be read
  (no map point, no answer, a temperature outside -40..60 °C) doesn't block.
- **Activation:** after a start the indicator is read every tick (2 s). Not on within
  5 minutes on the EV6 (Launchpad, field-tested) or 2 minutes on other cars (the
  Ioniq 5 takes about 60-70 s): stopped, reported as `timed_out`, and automatic starts
  wait for a manual one (no retry loop). Once on, no deadline; off again while
  requested is the car ending it: stopped, `ended`. A stop is done once the indicator
  is off (`stopping` until then). Without an indicator reading there's no deadline
  (`requested`). Mods read it as `conditioning.state` or `conditioning status`.

## 5. Ambient light

- **What stock does:** the settings app's mood-lamp page uses `VehicleInfoManager`
  (`setSMLConfiguration` for brightness/mode, `setSMLRGB` for the colour zones).
- **Risk:** low, but a mod that changes it many times a second (Ambient Sound
  follows the microphone) floods the MICOM link.
- **Guard:** a lease: one mod holds the light at a time; the owner's colour and
  brightness are saved first and set again when the lease ends (mod off, removed,
  or its process gone); at most 10 changes a second.
- **Tested (US EV6 260507):** the colour changes only with *Custom colour* selected
  (a theme overrides it), and only to one of the 64 colours of the settings app's
  picker; the car drops any other RGB without answering, so `color` sends the
  nearest palette colour and says so. Each change is answered with
  `SYS_SML_STATUS_M_RESP` (R, G, B, brightness, dimmed while driving, drive mode),
  which is how the owner's colour can be read back. HMG-API's system_server hook
  passes that broadcast to the vehicle service (a receiver registered from the vehicle
  service itself was accepted but never got one). Release restored the owner's light.
- **The owner's menu change:** a colour or brightness reported that HMG-API didn't
  send (within 3 s) is the owner's change in the car's menu. By default the mod keeps
  the light and the owner's new choice becomes what its release restores. A mod that
  sends `owner_change release` loses the light instead: nothing is put back over the
  owner's choice, and its next change is refused until it releases.
- **Owner's switch:** *Block mod light control* in the HMG-API app (`hmgapid allow
  block_ambient_light on`), off by default: every change is refused and a holder is
  released (the owner's light put back). Brightness: the car's
  value 1 is off, 2-11 the menu's levels 1-10; `brightness 0` turns the light off.

## 6. Parking guide lines

- **What stock does:** the camera settings use `PgsManager.requestGuideLineState`.
- **Risk:** low; affects the reverse camera view's guide lines.
- **Guard:** only while the mod holds the camera (Camera API, later step); the
  previous state is read first and put back when it lets go.

## 7. Startup notice dismiss (needs your call)

- **What it is:** `hideConsentScreen` hides the screen shown at start that the
  driver must accept before using the head unit (a safety/legal notice). Stock
  calls it only from Engineering Mode's factory service.
- **Risk:** this skips a notice the manufacturer requires the driver to accept,
  and it's an undocumented factory call with no known inverse. Factory82's author
  notes it can fail before boot completes and that its effect isn't proven.
- **Options:** (a) not offered by HMG-API (Factory82 keeps its own service, which
  runs as a mod service unchanged); (b) offered behind an explicit owner setting
  that says what it skips, park only, once per start, after `sys.boot_completed`.
  Following your rule (warn, then let the owner decide), I'd lean to (b), but it's
  your project's name on it.

## 8. Seat ventilation (owner's request, 2026-10-31)

- **What stock does:** the climate app's seat heat/vent popup sets heat and
  ventilation through the same `USMManager.setFrontSeatHeatDrvStep/AstStep`; the car
  reports each seat as one step (body control's `*SeatHeatState`: 2 off, 3-5
  ventilation low-high, 6-8 heat low-high).
- **Risk:** low; the same setting as the climate screen.
- **Guard:** the four named levels only. Ventilation and heat are one setting per
  seat: turning one on turns the other off, as on the climate screen. Checked on the
  EV6 (2026-10-31): `seat_vent driver medium` / `low` ventilate the seat; rear seat
  heat `high` sets step 8 on a two-level seat.

## 9. Front climate: Sync, Driver Only, the driver's temperature (Creta owner's request, 2026-11-07)

- **What stock does:** the climate screen's buttons go through
  `IDATCService.setFrontClimateControl(flag, value)` (`DATCManager` in-process), which
  the climate service passes to the climate unit as it is (`HU_DATC_SyncSet`,
  `HU_DATC_DrvOnlySet`, `HU_DATC_DrTempUpDn`). Flags (`DATCFlag.DATCControl`, Creta 260430):
  `SYNC` 22, `DRIVER_ONLY` 24, `TEMP_DR_UPDN` 50.
- **Values:** Sync 2 on / 1 off, Driver Only 1 on / 0 off, the driver's temperature 1 up /
  2 down, one step a press. From Tilepad's notes on the US Ioniq 5 (the framework doesn't
  encode them); the Creta owner took them without an on-car check. Turning Driver Only off
  is how the climate screen goes back to Sync.
- **Risk:** low; the same presses as the climate screen. A wrong value would reach the
  climate unit as it is, which is why only these three controls and these values are
  offered.
- **Guard:** only those controls and values; the car on (body control `getIGNStatus`;
  accessory reads off, and a value that can't be read refuses) and the climate on
  (`DatcStatus.getOperationStatus` 1, pushed); 3 a second at most; logged with the mod.
- **Readings alongside:** `ignition`, `belt.driver` and `belt.passenger` (telematics CAN
  `getDrvStBltSwSta` / `getAsstStBltSwSta`: 1 buckled), and `climate.on`, `climate.sync`,
  `climate.driver_only`, `climate.driver_temp`, `climate.passenger_temp` from climate
  control's status and `onTemperatureInformation` (pushed; a status alone keeps the old
  temperatures). Mapped on the Creta only so far.
