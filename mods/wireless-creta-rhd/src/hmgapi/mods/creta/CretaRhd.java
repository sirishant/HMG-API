package hmgapi.mods.creta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import hmgapi.hook.Cluster;
import hmgapi.mods.wireless.api.TurnDisplay;

/**
 * Android Auto on the cluster for the Hyundai Creta Electric, India (SU2IEV.IND, 260430):
 * right-hand drive, left-hand traffic. CRETA-SPECIFIC. Nothing here is checked on any
 * other car, and this mod's "system" in mod.json keeps it off every other car.
 *
 * Everything comes from this car's own navigation, logged on the road and in a parked
 * test of every display code. What the cluster does on this car:
 *
 *   - Far from a turn it draws its card from the turn list, whatever type the turn frame
 *     says. A type under 0x40 replaces the card with a scene, and only scenes have a bar.
 *   - The near arrow (0x0d) and near roundabout (0x21) draw only on exact 22.5 degree
 *     steps. 0x0d doesn't draw at 180.
 *   - The bar moves with the trap frame, not the turn frame's bar field.
 *   - The car's own traffic is clockwise round a roundabout, so roundabouts use the
 *     0xcx family and the mirrored angle.
 *
 * Runs on AaTurns's thread (the handler's). Maps' distance messages arrive as the "P"
 * and step messages as the "S" dumps from the AA library.
 */
final class CretaRhd implements TurnDisplay {
    /** The trap frame: stock GPM's clear of it, with the bar level in place of its fixed 100. */
    interface TrapFrame {
        void bar(int level);
    }

    private static final long FRESH_MS = 3000;
    // The car sends road names only when they change; Leafia's path repeated the same
    // name about once a second. A slow refresh covers a cluster that missed one.
    private static final long ROAD_REFRESH_MS = 10000;
    private static final int TYPE_ARRIVED = 3;
    private static final int TYPE_COMPASS = 8;
    // Only if Maps is really taking time: the two logged on the road took 0.42 and 0.53 s.
    private static final int TYPE_RECALCULATING = 0x0a;
    private static final int STATUS_REROUTING = 3;
    private static final long REROUTE_SHOW_MS = 1000;
    private static final int TYPE_DEST = 0x70;
    private static final int TYPE_DEST_RIGHT = 0x71;
    private static final int TYPE_DEST_LEFT = 0x72;
    private static final int STEP_DEST_LEFT = 41;
    private static final int STEP_DEST_RIGHT = 42;
    private static final int UNIT_METERS = 1;
    // The car keeps the distance on the destination screen down to 20 m.
    private static final int ARRIVED_M = 20;

    private static final int TYPE_RIGHT = 0x43;
    private static final int TYPE_LEFT = 0x46;
    private static final int TYPE_NEAR_TURN = 0x0d;
    private static final int TYPE_SLIGHT_RIGHT = 0x42;
    private static final int TYPE_SHARP_RIGHT = 0x44;
    private static final int TYPE_SHARP_LEFT = 0x45;
    private static final int TYPE_SLIGHT_LEFT = 0x47;
    private static final float SLIGHT_RIGHT_NEAR_DEG = 45f;
    private static final float SLIGHT_LEFT_NEAR_DEG = 315f;
    private static final int TYPE_UTURN_LEFT = 0x48;
    private static final int TYPE_UTURN_RIGHT = 0x49;
    private static final int TYPE_RAMP_RIGHT = 0x51;
    private static final int TYPE_RAMP_LEFT = 0x52;
    private static final int TYPE_FORK_RIGHT = 0xad;
    private static final int TYPE_FORK_LEFT = 0xae;
    // Scenes: take the right or left branch, and the car's own near U-turn, which it
    // sent at 90 degrees for list code 0x49.
    private static final int TYPE_NEAR_BRANCH_RIGHT = 0x13;
    private static final int TYPE_NEAR_BRANCH_LEFT = 0x14;
    private static final int TYPE_NEAR_UTURN = 0x1f;
    private static final float NEAR_UTURN_DEG = 90f;
    private static final int TYPE_NEAR_ROUNDABOUT = 0x21;
    private static final int FAMILY_MASK = 0xf0;
    private static final int FAMILY_ROUNDABOUT = 0xc0;
    private static final int ROUNDABOUT_RIGHT_FAMILY = 0x60;
    private static final int NEAR_E3 = 400000;
    // The car's own trap frames still reach the cluster and carry level 0, which empties
    // the bar. Repeating ours restores it within this time.
    private static final long TRAP_RESEND_MS = 2000;
    private static final int BAR_OFF = -1;
    // upper bound in m of each 10% step, read from the car's frames
    private static final int[] BAR_M = {60, 100, 130, 170, 200, 240, 270, 300, 350, 400};

    // the car's heading: whole degrees, about once a second, also during Android Auto
    private static final String NAVI_GPS_ACTION = "com.hkmc.telematics.gis.action.NAVI_GPS_CHANGED";
    private static final String NAVI_HEADING_EXTRA = "com.hkmc.telematics.gis.extra.HEADING";
    private static final long HEADING_FRESH_MS = 30000;
    private static final long COMPASS_PERIOD_MS = 1000;
    private static final int COMPASS_FRAMES = 3;
    private static final int COMPASS_WAIT_TICKS = 10;
    private static final int UNIT_NONE = 0x0f;
    private static final float COMPASS_STEP = 22.5f;

    private final Cluster cluster;
    private final Handler handler;
    private final TrapFrame trap;
    private final Context context;

    private int e3Value = -1, unitValue = -1;
    private long at;
    private int destMeters = -1, destType = TYPE_DEST;
    private long destAt;
    private boolean toDest;
    private String road;
    private long roadAt;
    private int trapLevel = -1;
    private long trapAt;
    private boolean rerouting;
    private int rerouteId, compassId;
    private int headingDeg = -1;
    private long headingAt;
    private BroadcastReceiver headingReceiver;

    CretaRhd(Context context, Cluster cluster, Handler handler, TrapFrame trap) {
        this.context = context;
        this.cluster = cluster;
        this.handler = handler;
        this.trap = trap;
    }

    long now() {
        return System.nanoTime() / 1000000L;
    }

    // ---- what Maps says

    /** "P <len> <hex>" is the distance message, "S <len> <hex>" the step. */
    @Override
    public void dump(String dump) {
        try {
            watchHeading();
            if (dump == null || dump.length() < 2) return;
            boolean distance = dump.startsWith("P ");
            if (!distance && !dump.startsWith("S ")) return;
            int sp = dump.indexOf(' ', 2);
            if (sp < 0) return;
            byte[] b = hex(dump.substring(sp + 1).trim());
            if (distance) parse(b, now());
            else parseStep(b);
        } catch (RuntimeException t) {
            e3Value = -1;
            destMeters = -1;
        }
    }

    /** 0x8007: field 1 the next turn, field 2 the destination; each {1 meters, 2 text, 3 unit}. */
    void parse(byte[] b, long nowMs) {
        byte[] step = field(b, 0, b.length, 1);
        byte[] dist = step == null ? null : field(step, 0, step.length, 1);
        byte[] dest = field(b, 0, b.length, 2);
        byte[] left = dest == null ? null : field(dest, 0, dest.length, 1);
        if (left != null) {
            destMeters = Math.max(0, varintField(left, 1));
            destAt = nowMs;
        }
        if (dist == null) return;
        byte[] text = field(dist, 0, dist.length, 2);
        int unit = varintField(dist, 3);
        if (text == null || unit <= 0) return;
        e3Value = Math.round(Float.parseFloat(new String(text)) * 1000f);
        unitValue = unit;
        at = nowMs;
    }

    /** 0x8006: field 1 the step, its field 1 the manoeuvre, whose field 1 is the type. */
    void parseStep(byte[] b) {
        byte[] step = field(b, 0, b.length, 1);
        byte[] man = step == null ? null : field(step, 0, step.length, 1);
        if (man == null) return;
        int t = varintField(man, 1);
        destType = t == STEP_DEST_LEFT ? TYPE_DEST_LEFT : t == STEP_DEST_RIGHT ? TYPE_DEST_RIGHT : TYPE_DEST;
    }

    private boolean fresh() {
        return e3Value >= 0 && now() - at <= FRESH_MS;
    }

    /** Maps' display value x 1000 if there is one, else the library's. */
    @Override
    public int e3(int fallback) {
        return fresh() ? e3Value : fallback;
    }

    @Override
    public int unit(int fallback) {
        return fresh() ? unitValue : fallback;
    }

    /** Under 1 km the cluster shows meters, so the shown steps need Maps' value here too. */
    @Override
    public int meters(int fallback) {
        return fresh() && unitValue == UNIT_METERS ? e3Value / 1000 : fallback;
    }

    private boolean near() {
        return fresh() && unitValue == UNIT_METERS && e3Value <= NEAR_E3;
    }

    // ---- what the cluster gets

    /** The car's clockwise family and mirrored angle, from the right-hand traffic table. */
    @Override
    public int roundaboutType(int type) {
        return (type & FAMILY_MASK) == ROUNDABOUT_RIGHT_FAMILY ? FAMILY_ROUNDABOUT | (type & 0x0f) : type;
    }

    @Override
    public int roundaboutAngle(int angle) {
        return 360 - angle;
    }

    /** Destination: the side, with the distance on, then "reached" below ARRIVED_M. */
    @Override
    public int destType(int type) {
        if (type == TYPE_ARRIVED) {
            toDest = true;
        } else if (!dest(type) || !toDest) {
            toDest = false;
            return type;
        }
        boolean live = destMeters >= 0 && now() - destAt <= FRESH_MS;
        return live && destMeters < ARRIVED_M ? TYPE_ARRIVED : destType;
    }

    private static boolean dest(int type) {
        return type == TYPE_DEST || type == TYPE_DEST_RIGHT || type == TYPE_DEST_LEFT;
    }

    private static boolean arrow(int type) {
        return type == TYPE_RIGHT || type == TYPE_LEFT || type == TYPE_SLIGHT_RIGHT || type == TYPE_SLIGHT_LEFT
                || type == TYPE_SHARP_RIGHT || type == TYPE_SHARP_LEFT;
    }

    private static boolean branchRight(int type) {
        return type == TYPE_RAMP_RIGHT || type == TYPE_FORK_RIGHT;
    }

    private static boolean branchLeft(int type) {
        return type == TYPE_RAMP_LEFT || type == TYPE_FORK_LEFT;
    }

    private static boolean uTurn(int type) {
        return type == TYPE_UTURN_LEFT || type == TYPE_UTURN_RIGHT;
    }

    private static boolean scene(int type) {
        return type == TYPE_NEAR_TURN || type == TYPE_NEAR_ROUNDABOUT || type == TYPE_NEAR_UTURN
                || type == TYPE_NEAR_BRANCH_RIGHT || type == TYPE_NEAR_BRANCH_LEFT;
    }

    private static boolean turn(int type) {
        return arrow(type) || branchRight(type) || branchLeft(type) || uTurn(type) || scene(type)
                || (type & FAMILY_MASK) == FAMILY_ROUNDABOUT;
    }

    /** The scene the car uses this close to a turn. Anything else stays the card. */
    int nearType(int type) {
        if (!near()) return type;
        if (arrow(type)) return TYPE_NEAR_TURN;
        if (branchRight(type)) return TYPE_NEAR_BRANCH_RIGHT;
        if (branchLeft(type)) return TYPE_NEAR_BRANCH_LEFT;
        if (uTurn(type)) return TYPE_NEAR_UTURN;
        return (type & FAMILY_MASK) == FAMILY_ROUNDABOUT ? TYPE_NEAR_ROUNDABOUT : type;
    }

    /** The arrow angle for the type Maps gave; call with the type before nearType. */
    float direction(int type, float fallback) {
        if (!near()) return fallback;
        if (type == TYPE_SLIGHT_RIGHT) return SLIGHT_RIGHT_NEAR_DEG;
        if (type == TYPE_SLIGHT_LEFT) return SLIGHT_LEFT_NEAR_DEG;
        if (uTurn(type)) return NEAR_UTURN_DEG;
        return branchRight(type) || branchLeft(type) ? 0f : fallback;
    }

    /** Side-street field: the car sends 0 on its scenes and destination, Leafia 0xffff. */
    static int side(int type, int fallback) {
        return scene(type) || dest(type) || (type & FAMILY_MASK) == FAMILY_ROUNDABOUT ? 0 : fallback;
    }

    /** Countdown percent for a turn, the caller's value otherwise. */
    int bar(int type, int fallback) {
        if (!turn(type) || fallback > 100) return fallback;
        if (near()) {
            int m = e3Value / 1000;
            for (int i = 0; i < BAR_M.length; i++) {
                if (m <= BAR_M[i]) return (i + 1) * 10;
            }
        }
        return 0;
    }

    /** On only for a near scene. An invalid distance (bar 0xfe, err -2) stays invalid. */
    int barErr(int type, int fallback) {
        return (!turn(type) || !near()) && fallback == 0 ? BAR_OFF : fallback;
    }

    /** The bar value for the trap frame if it has to go out now, else -1. */
    int trapBar(int type, int bar, int err, long nowMs) {
        int level = turn(type) && near() && err == 0 ? bar : 0;
        if (level == trapLevel && (level == 0 || nowMs - trapAt < TRAP_RESEND_MS)) return -1;
        trapLevel = level;
        trapAt = nowMs;
        return level;
    }

    /** One turn frame, as the car would send it at this distance. */
    @Override
    public void send(int type, float direction, float distance, int unit, int bar, int barErr) {
        cancelCompass();
        float d = direction(type, direction);
        int out = nearType(type);
        int err = barErr(out, barErr);
        int level = bar(out, bar);
        cluster.tbtInfo(out, side(out, 65535), d, distance, 0, 0, unit, level, err);
        int t = trapBar(out, level, err, now());
        if (t >= 0) trap.bar(t);
    }

    /** True when this road name went out recently and needs no new frame. */
    @Override
    public boolean roadUnchanged(String name) {
        long n = now();
        if (name != null && name.equals(road) && n - roadAt < ROAD_REFRESH_MS) return true;
        road = name;
        roadAt = n;
        return false;
    }

    /** Maps' guidance status. The screen goes up only for a reroute that is really taking time. */
    @Override
    public void status(int status) {
        if (status != STATUS_REROUTING) {
            rerouting = false;
            return;
        }
        if (rerouting || !fresh()) return;   // not guiding: not ours to touch
        rerouting = true;
        final int id = ++rerouteId;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (rerouting && id == rerouteId) {
                    trapLevel = -1;
                    cluster.tbtInfo(TYPE_RECALCULATING, 0xffff, 0f, 0f, -3, 0, 15, 0, -1);
                }
            }
        }, REROUTE_SHOW_MS);
    }

    /** Forget the route. */
    void routeEnded() {
        toDest = false;
        destType = TYPE_DEST;
        road = null;
        trapLevel = -1;
        rerouting = false;
        e3Value = -1;
        destMeters = -1;
    }

    // ---- compass after guidance: the car's heading only, never the phone's

    private void watchHeading() {
        if (headingReceiver != null || context == null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int d = i.getIntExtra(NAVI_HEADING_EXTRA, -1);
                if (d >= 0 && d <= 360) setHeading(d, now());
            }
        };
        try {
            context.registerReceiver(r, new IntentFilter(NAVI_GPS_ACTION));
            headingReceiver = r;
        } catch (RuntimeException e) {
            headingReceiver = null;
        }
    }

    void setHeading(int degrees, long nowMs) {
        headingDeg = degrees;
        headingAt = nowMs;
    }

    /** Mirrored (the car sends 90 for a heading of 270), on the step of the car's own frames. */
    static float clusterDeg(float heading) {
        return Math.round((360f - heading) / COMPASS_STEP) * COMPASS_STEP % 360f;
    }

    /**
     * The car resumes its compass but sends a frame only when the heading moves, so this
     * sends its exact frame a few times, then leaves the cluster to it. The sub frame goes
     * once: the car resumes that one by itself within 0.1 s, and repeats alternate with it.
     */
    @Override
    public void guidanceEnded() {
        watchHeading();
        routeEnded();
        final int id = ++compassId;
        handler.post(new Runnable() {
            int sent, tick;

            @Override
            public void run() {
                if (id != compassId || sent >= COMPASS_FRAMES || tick++ >= COMPASS_WAIT_TICKS + COMPASS_FRAMES) return;
                if (headingDeg >= 0 && now() - headingAt <= HEADING_FRESH_MS) {
                    float deg = clusterDeg(headingDeg);
                    cluster.tbtInfo(TYPE_COMPASS, 0, deg, 0f, 0, 0, 0, 0, -1);
                    if (sent == 0) cluster.tbtSubInfo(0f, -3, UNIT_NONE, -1, -1, -1, -1, deg, 0, -1);
                    sent++;
                }
                handler.postDelayed(this, COMPASS_PERIOD_MS);
            }
        });
    }

    private void cancelCompass() {
        compassId++;
    }

    // ---- protobuf, just enough for these two messages

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /** The first length-delimited field with this number. */
    private static byte[] field(byte[] b, int start, int end, int want) {
        int i = start;
        while (i < end) {
            int[] tag = varint(b, i);
            i = tag[1];
            int f = tag[0] >>> 3, w = tag[0] & 7;
            if (w == 0) {
                i = varint(b, i)[1];
            } else if (w == 2) {
                int[] n = varint(b, i);
                i = n[1];
                if (i + n[0] > end) return null;
                if (f == want) {
                    byte[] o = new byte[n[0]];
                    System.arraycopy(b, i, o, 0, n[0]);
                    return o;
                }
                i += n[0];
            } else if (w == 5) {
                i += 4;
            } else if (w == 1) {
                i += 8;
            } else {
                return null;
            }
        }
        return null;
    }

    /** A varint field's value, 0 if absent (proto3 leaves zero out). */
    private static int varintField(byte[] b, int want) {
        int i = 0;
        while (i < b.length) {
            int[] tag = varint(b, i);
            i = tag[1];
            int f = tag[0] >>> 3, w = tag[0] & 7;
            if (w == 0) {
                int[] v = varint(b, i);
                if (f == want) return v[0];
                i = v[1];
            } else if (w == 2) {
                int[] n = varint(b, i);
                i = n[1] + n[0];
            } else if (w == 5) {
                i += 4;
            } else if (w == 1) {
                i += 8;
            } else {
                return 0;
            }
        }
        return 0;
    }

    private static int[] varint(byte[] b, int i) {
        int v = 0, s = 0;
        while (i < b.length) {
            int c = b[i++];
            v |= (c & 0x7f) << s;
            if ((c & 0x80) == 0) break;
            s += 7;
        }
        return new int[] {v, i};
    }
}
