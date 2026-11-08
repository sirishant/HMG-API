# Cluster turn-by-turn: what we measured on a Gen5W cluster

Findings from the Hyundai Creta Electric India (`SU2IEV.IND`, 260430, right-hand drive,
left-hand traffic), for anyone sending Android Auto or CarPlay turn-by-turn to a Gen5W
cluster. The code is in this repository (AGPL-3.0):

- `mods/wireless-creta-rhd/src/hmgapi/mods/creta/CretaRhd.java`: everything below, with
  its host test (`tools/test/.../CretaRhdTest.java`, 180 checks).
- `mods/wireless-carplay-aa/src/hmgapi/mods/wireless/api/TurnDisplay.java`: the hook a
  car-specific display plugs into; `AaTurns.java` and `dipo/CpTurns.java` feed it (the same
  Leafia-era turn-by-turn lineage as other Gen5W projects).

## How we measured

1. **A parked symbol sweep:** every turn-frame type and angle sent to the cluster, filmed and
   lined up with the MicomD log (`send_data: c => m : ff 8f 0d 01 0d 04 …`).
2. **The car's own navigation, logged on real drives:** its turn frames, turn lists
   (`ff c3 4d 80 4d 8e … f2 …`), trap frames and lane frames, compared with ours on the same
   roads.

Everything here is measured on this one cluster. Other clusters need the same sweep; the
first part takes about 20 minutes parked.

## What the cluster does (likely the same on other Gen5W clusters, not verified)

| Finding | Detail |
|---|---|
| Far card comes from the turn list | Far from a turn the cluster draws its card from the turn list (`f2`), whatever type the turn frame says. A turn-frame type of 0x40 or more doesn't change the card. |
| Near views are types under 0x40 | 0x0d turn arrow, 0x21/0x20 roundabout, 0x13/0x14 take right/left branch, 0x1f U-turn, 0x11/0x12 straight with a side road right/left (the car uses 0x12 with far code 0x94 near what is probably a left exit). |
| Only near views have a bar | The countdown bar shows on near views only (0x0d, 0x21, 0x11-0x14 seen). |
| The bar follows the trap frame | The bar level is the trap frame's (`update_tbt_trap_info`), not the turn frame's bar field. The car's own navigation keeps sending trap frames with level 0, which empties the bar; re-sending ours every 2 s keeps it. The trap frame also carries the speed-limit sign, so the car's limit and cameras can be passed along with the bar. |
| Bar table | Level by distance, read from the car's own frames: ≤60 m 10%, ≤100 m 20%, ≤130 m 30%, ≤170 m 40%, ≤200 m 50%, ≤240 m 60%, ≤270 m 70%, ≤300 m 80%, ≤350 m 90%, ≤400 m 100%. |
| Near-view distances | The car's navigation switches from the card to the near view at 300 m for a turn and up to 400 m for a roundabout. |
| Angles | Near arrows (0x0d) and near roundabouts (0x21) draw only on exact 22.5° steps; anything between shows no arrow. 0x0d doesn't draw at 180°. Slight turns: 45°/315°; the near U-turn: 90°. |
| No-direction step | Maps' "no direction" type 64 (0x40), e.g. straight out of a roundabout, draws the cluster's loading spinner, in the turn frame and as the far card from the list. Send straight (0x41) in both. |
| Other useful codes | 0x02 head-for-route arrow (the car's own start of a route and reroute), 0x08 compass (with the car's heading when not navigating), 0x09 "Calculating…", 0x0a "Recalculating…". |
| Road line | The cluster cuts long names: Maps' "At the roundabout, take the 2nd exit" fits once "At the roundabout, " is dropped. |
| Don't claim the cluster | Sending navigation-alive / operation status from the phone side can leave a spinner after projection ends on these cars. |

## Left-hand traffic only (right-hand-drive cars)

| Finding | Detail |
|---|---|
| Roundabout family | Traffic goes clockwise: Maps' right-hand-traffic codes 0x6N become the car's 0xcN. The car's own codes by exit angle: 0xc0 0°, 0xc1 45°, 0xc3 90°, 0xc4 135°, 0xc6 180°, 0xc9 270°, 0xcb 315°. 0xca (the table's slight left) draws the spinner: use 0xcb. 225° (0xc7) unseen. |
| Angles | Mirrored: 360° − the right-hand-traffic angle. |
| Near roundabout | 0x21 (left half lit) instead of 0x20. |

So a left/right-hand-traffic option is a small switch: the roundabout family, mirrored
angles and 0x20/0x21. The rest (near views, bar, trap frame, angle steps, 0x40) depends on
the cluster, not the traffic side.

## Not yet confirmed

- Whether 0x12 (not 0x14) is the car's near view for a left highway exit; one sighting.
- The 225° roundabout exit (0xc7).
