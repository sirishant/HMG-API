package hmgapi.mods.wireless;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.mods.wireless.api.TurnDisplay;

/**
 * The Creta display through the real AaTurns: a route's callbacks in, the frames the
 * cluster would get out. Expected frames are the car's own, from its logged guidance
 * and the parked test of every display code.
 */
public final class CretaRhdTest {
    static final int RIGHT = 2, LEFT = 1, UNSPEC = 3;
    static final int DEPART = 1, SLIGHT = 3, TURN = 4, SHARP = 5, U_TURN = 6, OFF_RAMP = 8, FORK = 9, RAB = 13, DEST = 19;
    static final int METERS = 1, KM_P1 = 3;
    static int fail, checks;
    static Cluster c;
    static Handler h;
    static AaTurns t;
    static List<Integer> traps;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            System.out.println("FAIL  " + what);
            fail = 1;
        }
    }

    static void eq(Object got, Object want, String what) {
        check(want.equals(got), what + ": got " + got + ", want " + want);
    }

    /** protobuf pieces, to build Maps' messages */
    static String v(int n) {
        StringBuilder b = new StringBuilder();
        while (n >= 0x80) {
            b.append(String.format("%02x", (n & 0x7f) | 0x80));
            n >>>= 7;
        }
        return b.append(String.format("%02x", n)).toString();
    }

    static String ld(int f, String hex) {
        return v(f << 3 | 2) + v(hex.length() / 2) + hex;
    }

    static String vf(int f, int n) {
        return v(f << 3) + v(n);
    }

    static String text(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(String.format("%02x", (int) ch));
        return b.toString();
    }

    static String dist(int m, String shown, int unit) {
        return (m > 0 ? vf(1, m) : "") + ld(2, text(shown)) + vf(3, unit);
    }

    /** The two dumps the library hands over for one second of guidance, then the callbacks. */
    static void second(int ev, int side, int angle, String road, int m, String shown, int unit, int destM) {
        String p = ld(1, ld(1, dist(m, shown, unit)) + vf(2, 7)) + ld(2, ld(1, dist(destM, "" + destM, METERS)));
        t.nextTurn("P " + (p.length() / 2) + " " + p, 0, 33, 0, 0);
        t.nextTurn(road, side, ev, angle, 0);
        t.nextTurnDistance(m, 10, Math.round(Float.parseFloat(shown) * 1000), unit);
    }

    static void step(int mapsType) {
        String s = ld(1, ld(1, vf(1, mapsType)));
        t.nextTurn("S " + (s.length() / 2) + " " + s, 0, 33, 0, 0);
    }

    static String tbt() {
        String last = null;
        for (String l : c.calls) if (l.startsWith("tbt ")) last = l;
        c.calls.clear();
        return last;
    }

    static int[] frame() {
        String[] p = tbt().split(" ");
        return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2]), Math.round(Float.parseFloat(p[3]) * 2),
                Integer.parseInt(p[8]), Integer.parseInt(p[9])};
    }

    /** type, side, angle, bar level, bar err */
    static void frame(String what, int type, int side, float angle, int bar, int err) {
        int[] f = frame();
        eq(f[0], type, what + " type");
        eq(f[1], side, what + " side");
        eq(f[2], Math.round(angle * 2), what + " angle x2");
        if (err == 0) eq(f[3], bar, what + " bar");
        eq(f[4], err, what + " bar err");
    }

    static void newRoute() throws Exception {
        c = new Cluster();
        h = new Handler();
        traps = new ArrayList<Integer>();
        t = new AaTurns(new android.content.Context(), c, h);
        java.lang.reflect.Constructor<?> k = Class.forName("hmgapi.mods.creta.CretaRhd").getDeclaredConstructors()[0];
        k.setAccessible(true);
        Class<?> trapType = Class.forName("hmgapi.mods.creta.CretaRhd$TrapFrame");
        Object trap = java.lang.reflect.Proxy.newProxyInstance(trapType.getClassLoader(), new Class<?>[] {trapType},
                (p, m, a) -> {
                    if ("bar".equals(m.getName())) traps.add((Integer) a[0]);
                    return null;
                });
        t.display = (TurnDisplay) k.newInstance(new android.content.Context(), c, h, trap);
        t.status(1);
        c.calls.clear();
    }

    public static void main(String[] a) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        Car.unit = Car.DISTANCE_KM;

        // far: the plain code, bar off; near: the scene and bar
        newRoute();
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        frame("left, far", 0x46, 65535, 270, 0, -1);
        second(TURN, LEFT, 0, "Main", 350, "350", METERS, 5000);
        frame("left at 350: still the card (the car's scene starts at 300)", 0x46, 65535, 270, 0, -1);
        second(TURN, LEFT, 0, "Main", 300, "300", METERS, 5000);
        frame("left at 300: the scene", 0x0d, 0, 270, 80, 0);
        second(RAB, UNSPEC, 90, "Main", 400, "400", METERS, 5000);
        frame("roundabout at 400: the scene (the car's from 400)", 0x21, 0, 270, 100, 0);
        second(RAB, UNSPEC, 90, "Main", 500, "500", METERS, 5000);
        frame("roundabout at 500: the card", 0xc9, 0, 270, 0, -1);
        second(TURN, RIGHT, 0, "Main", 120, "120", METERS, 5000);
        frame("right at 120", 0x0d, 0, 90, 30, 0);
        second(TURN, RIGHT, 0, "Main", 1500, "1.5", KM_P1, 5000);
        frame("km is far", 0x43, 65535, 90, 0, -1);

        // Maps' rounded value, not the exact one the library reports: the cluster steps like Maps
        String p = ld(1, ld(1, dist(848, "850", METERS)) + vf(2, 7)) + ld(2, ld(1, dist(5000, "5000", METERS)));
        t.nextTurn("P " + (p.length() / 2) + " " + p, 0, 33, 0, 0);
        t.nextTurnDistance(848, 10, 850000, METERS);
        eq(tbt().split(" ")[4], "850.0", "shows Maps' 850, not the exact 848");

        // the bar table, the car's own (a roundabout's scene starts at 400 m, a turn's at 300)
        int[][] rbars = {{390, 100}, {340, 90}};
        for (int[] b : rbars) {
            second(RAB, UNSPEC, 90, "Main", b[0], "" + b[0], METERS, 5000);
            eq(frame()[3], b[1], "roundabout bar at " + b[0] + " m");
        }
        int[][] bars = {{300, 80}, {270, 70}, {240, 60}, {200, 50}, {170, 40}, {130, 30}, {100, 20}, {60, 10}, {5, 10}};
        for (int[] b : bars) {
            second(TURN, LEFT, 0, "Main", b[0], "" + b[0], METERS, 5000);
            eq(frame()[3], b[1], "bar at " + b[0] + " m");
        }

        // slight and sharp: only exact 22.5 steps draw, and every one sent is on them
        second(SLIGHT, RIGHT, 0, "Main", 200, "200", METERS, 5000);
        frame("slight right", 0x0d, 0, 45, 50, 0);
        second(SLIGHT, LEFT, 0, "Main", 200, "200", METERS, 5000);
        frame("slight left", 0x0d, 0, 315, 50, 0);
        second(SHARP, RIGHT, 0, "Main", 200, "200", METERS, 5000);
        frame("sharp right", 0x0d, 0, 135, 50, 0);
        second(SHARP, LEFT, 0, "Main", 200, "200", METERS, 5000);
        frame("sharp left", 0x0d, 0, 225, 50, 0);
        second(SLIGHT, RIGHT, 0, "Main", 900, "900", METERS, 5000);
        frame("slight right far keeps its own", 0x42, 65535, 30, 0, -1);

        // U-turn, ramp, fork
        second(U_TURN, RIGHT, 0, "Main", 200, "200", METERS, 5000);
        frame("U-turn scene", 0x1f, 0, 90, 50, 0);
        second(OFF_RAMP, RIGHT, 0, "Main", 200, "200", METERS, 5000);
        frame("ramp right", 0x13, 0, 0, 50, 0);
        second(OFF_RAMP, LEFT, 0, "Main", 200, "200", METERS, 5000);
        frame("ramp left", 0x14, 0, 0, 50, 0);
        second(FORK, RIGHT, 0, "Main", 200, "200", METERS, 5000);
        frame("fork right", 0x13, 0, 0, 50, 0);
        second(FORK, LEFT, 0, "Main", 200, "200", METERS, 5000);
        frame("fork left", 0x14, 0, 0, 50, 0);

        // roundabouts: clockwise family, mirrored angle, checked on the road by heading
        second(RAB, UNSPEC, 90, "Main", 900, "900", METERS, 5000);
        frame("left exit, far", 0xc9, 0, 270, 0, -1);
        // slight-left exit: the car's own navigation sends 0xcb (0xca drew the loader)
        second(RAB, UNSPEC, 160, "Main", 900, "900", METERS, 5000);
        frame("slight left exit, far: the car's 0xcb", 0xcb, 0, 315, 0, -1);
        second(RAB, UNSPEC, 160, "Main", 200, "200", METERS, 5000);
        frame("slight left exit, near: the scene at 315", 0x21, 0, 315, 50, 0);
        second(RAB, UNSPEC, 90, "Main", 200, "200", METERS, 5000);
        frame("left exit, near", 0x21, 0, 270, 50, 0);
        second(RAB, UNSPEC, 225, "Main", 200, "200", METERS, 5000);
        frame("right exit, near", 0x21, 0, 45, 50, 0);
        second(RAB, UNSPEC, 180, "Main", 900, "900", METERS, 5000);
        frame("straight on", 0xc0, 0, 0, 0, -1);

        // the far list keeps Maps' code under a near scene
        newRoute();
        second(TURN, LEFT, 0, "Main", 200, "200", METERS, 5000);
        boolean list = false;
        for (String l : c.calls) list |= l.startsWith("hud f201" + "46");
        check(list, "turn list keeps code 0x46 near: " + c.calls);
        c.calls.clear();

        // the trap frame: on a change, repeated on the approach, never far
        newRoute();
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        eq(traps.toString(), "[0]", "far: one clear");
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        eq(traps.size(), 1, "far: no repeat");
        second(TURN, LEFT, 0, "Main", 150, "150", METERS, 5000);
        second(TURN, LEFT, 0, "Main", 150, "150", METERS, 5000);
        eq(traps.toString(), "[0, 40]", "near: on change");
        Thread.sleep(2050);
        second(TURN, LEFT, 0, "Main", 150, "150", METERS, 5000);
        eq(traps.toString(), "[0, 40, 40]", "near: repeated after 2 s");
        c.calls.clear();

        // destination: side, then arrived only below 20 m
        newRoute();
        step(41);
        second(DEST, UNSPEC, 0, "", 50, "50", METERS, 50);
        frame("destination left", 0x72, 0, 0, 0, -1);
        step(42);
        second(DEST, UNSPEC, 0, "", 30, "30", METERS, 30);
        frame("destination right", 0x71, 0, 0, 0, -1);
        step(39);
        second(DEST, UNSPEC, 0, "", 20, "20", METERS, 20);
        frame("destination centre, 20 m is not yet", 0x70, 0, 0, 0, -1);
        second(DEST, UNSPEC, 0, "", 19, "19", METERS, 19);
        eq(frame()[0], 3, "arrived below 20 m");

        // road names: on change, like the car
        newRoute();
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        int n = 0;
        for (String l : c.calls) n += l.equals("road [Main]") ? 1 : 0;
        eq(n, 1, "road once");
        c.calls.clear();
        second(TURN, LEFT, 0, "Main", 800, "800", METERS, 5000);
        check(c.calls.toString().indexOf("road [") < 0, "same road held back");
        c.calls.clear();
        second(TURN, LEFT, 0, "Second", 700, "700", METERS, 5000);
        check(c.calls.contains("road [Second]"), "new road goes out");

        // a reroute: nothing unless it takes more than a second
        newRoute();
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        c.calls.clear();
        t.status(3);
        Runnable later = h.delayed;
        check(later != null, "reroute timer set");
        t.status(1);
        later.run();
        check(c.calls.isEmpty(), "fast reroute: no screen " + c.calls);
        t.status(3);
        h.delayed.run();
        eq(tbt(), "tbt 10 65535 0.0 0.0 -3 0 15 0 -1", "slow reroute: Recalculating, the car's frame shape");

        // the compass: the car's heading, on its step, once the route ends
        newRoute();
        second(TURN, LEFT, 0, "Main", 900, "900", METERS, 5000);
        // CarPlay gives meters directly
        t.display.position(200, 900);
        t.display.send(0x43, 90f, 200f, 0, 32, 0);
        frame("CarPlay: right turn near", 0x0d, 0, 90, 50, 0);
        t.display.position(200, 900);
        t.display.send(0xc9, 270f, 200f, 0, 32, 0);
        frame("CarPlay: left exit roundabout near", 0x21, 0, 270, 50, 0);
        t.display.position(900, 2000);
        t.display.send(0xc9, 270f, 900f, 0, 32, 0);
        frame("CarPlay: left exit roundabout far", 0xc9, 0, 270, 0, -1);
        t.display.position(1500, 2000);
        t.display.send(0x43, 90f, 1.5f, 1, 32, 0);
        frame("CarPlay: 1.5 km stays the card", 0x43, 65535, 90, 0, -1);
        // a step with no direction (type 64) is the straight card, not the cluster's loader
        t.display.position(20, 2000);
        t.display.send(0x40, 0f, 20f, 0, 32, 0);
        frame("no direction: straight card, not the loader", 0x41, 65535, 0, 0, -1);
        // Maps' roundabout instruction as the road line: shortened so the exit fits
        check("At the roundabout, take the 2nd exit".equals(t.display.roadText("At the roundabout, take the 2nd exit")) == false
                && "Take the 2nd exit".equals(t.display.roadText("At the roundabout, take the 2nd exit")), "roundabout road line shortened");
        check("Main Rd".equals(t.display.roadText("Main Rd")) && t.display.roadText(null) == null
                && "At the roundabout, ".equals(t.display.roadText("At the roundabout, ")), "other road lines stay");
        // the bar's frame keeps the car navigation's speed limit; only the bar changes
        java.lang.reflect.Method tw = Class.forName("hmgapi.mods.creta.CretaRhd").getDeclaredMethod("trapWith", int[].class, int.class);
        tw.setAccessible(true);
        int[] navi = {-1, -1, 3, -1, 0, 0, 0, 0, 15, 0xffff, 3, 0, 60, 15, 1, 1, 15};
        int[] got = (int[]) tw.invoke(null, navi, 50);
        check(got[11] == 50 && got[12] == 60 && got[14] == 1 && navi[11] == 0, "trap frame: the navigation's speed limit stays, only the bar changes");
        got = (int[]) tw.invoke(null, null, 30);
        check(got[11] == 30 && got[12] == 255 && got[9] == 0xffff, "trap frame: stock clear before the navigation sent one");
        got = (int[]) tw.invoke(null, new int[3], 30);
        check(got.length == 17 && got[12] == 255, "trap frame: a short frame is ignored");
        Object d = t.display;
        java.lang.reflect.Method heading = d.getClass().getDeclaredMethod("setHeading", int.class, long.class);
        heading.setAccessible(true);
        heading.invoke(d, 120, System.nanoTime() / 1000000L);
        c.calls.clear();
        t.status(2);
        boolean tbtGood = false, blank = false;
        int subs = 0;
        for (String l : c.calls) {
            tbtGood |= l.startsWith("tbt 8 0 247.5 ");
            blank |= l.startsWith("tbt 0 ");
            subs += l.startsWith("sub ") ? 1 : 0;
        }
        check(tbtGood, "compass on the 22.5 step, mirrored: " + c.calls);
        check(!blank, "never the blank frame that shows the loader");
        eq(subs, 1, "sub frame once");
        c.calls.clear();
        h.delayed.run();
        check(c.calls.toString().contains("tbt 8 0 247.5"), "second compass frame");
        check(!c.calls.toString().contains("sub "), "no second sub frame");

        System.out.println(fail == 0 ? "ok    Creta display: " + checks + " checks" : "FAILED");
        if (fail != 0) throw new AssertionError("failed");
    }
}
