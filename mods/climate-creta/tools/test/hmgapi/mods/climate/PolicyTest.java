package hmgapi.mods.climate;

import java.util.ArrayList;
import java.util.List;

/** The climate policy against a made-up car that answers presses as the climate screen does. */
public final class PolicyTest {
    /** the car: presses take effect at its next report, as the climate unit's status does */
    static final class Sim {
        int ignition = 1, belt = 0, on = 1, sync = 1, driverOnly = 0;
        float driver = 22f, passenger = 22f;
        boolean deaf;   // presses lost (a step the car never shows)
        String refuse;   // HMG-API refuses presses with this
        final List<String> presses = new ArrayList<String>();

        Policy.Car look() {
            Policy.Car c = new Policy.Car();
            c.ignition = ignition;
            c.belt = belt;
            c.on = on;
            c.sync = sync;
            c.driverOnly = driverOnly;
            c.driver = driver;
            c.passenger = passenger;
            return c;
        }

        void press(String p) {
            presses.add(p);
            if (deaf) return;
            if ("driver_only on".equals(p)) {
                driverOnly = 1;
                sync = 0;
            } else if ("driver_only off".equals(p)) {
                driverOnly = 0;
                sync = 1;   // the car returns to Sync
                passenger = driver;
            } else if ("sync on".equals(p)) {
                sync = 1;
                passenger = driver;
            } else if ("driver_temp up".equals(p)) {
                driver += 0.5f;
            } else if ("driver_temp down".equals(p)) {
                driver -= 0.5f;
            }
        }

        /** the passenger's knob: Sync drops */
        void knob(float t) {
            passenger = t;
            sync = 0;
        }
    }

    static long now = 1000;
    static int fail;
    static final List<String> log = new ArrayList<String>();

    static void check(String what, String want, String got) {
        if (want.equals(got)) System.out.println("ok    " + what);
        else {
            System.out.println("FAIL  " + what + ": wanted [" + want + "], got [" + got + "]");
            fail = 1;
        }
    }

    static Policy policy() {
        log.clear();
        return new Policy(new Policy.Log() {
            @Override
            public void line(String text) {
                log.add(text);
            }
        });
    }

    /** n looks, 150 ms apart (the service's pace with the car on) */
    static void run(Policy p, Sim s, int n) {
        for (int i = 0; i < n; i++) {
            String press = p.next(s.look(), now);
            if (press != null) {
                if (s.refuse != null) {
                    s.presses.add("refused " + press);
                    p.sent(press, false, s.refuse, now);
                } else {
                    s.press(press);
                    p.sent(press, true, "ok climate " + press, now);
                }
            }
            now += 150;
        }
    }

    static String presses(Sim s) {
        String r = String.join(" | ", s.presses);
        s.presses.clear();
        return r;
    }

    static boolean logged(String part) {
        for (String l : log) if (l.contains(part)) return true;
        return false;
    }

    public static void main(String[] a) {
        // remote climate with the car off, then the car on: Driver Only
        Sim s = new Sim();
        s.ignition = 0;
        Policy p = policy();
        run(p, s, 20);
        check("remote climate, car off: nothing pressed", "", presses(s));
        s.ignition = 1;
        run(p, s, 5);
        check("car on, climate on, passenger not buckled: Driver Only", "driver_only on", presses(s));
        check("... once", "1", String.valueOf(p.stage));
        run(p, s, 20);
        check("... and not again", "", presses(s));
        // the passenger buckles up: back to Sync, Driver Only's own way
        s.belt = 1;
        run(p, s, 10);
        check("passenger buckles up: Driver Only off (the car returns to Sync)", "driver_only off", presses(s));
        check("... done for this drive", "2", String.valueOf(p.stage));
        run(p, s, 20);
        check("... nothing more", "", presses(s));
        // unbuckle and buckle again: nothing (once per drive), Driver Only isn't on
        s.belt = 0;
        run(p, s, 3);
        s.belt = 1;
        run(p, s, 5);
        check("buckling again: nothing", "", presses(s));
        // car off, then on again: a new drive
        s.ignition = 0;
        s.belt = 0;
        run(p, s, 3);
        check("car off: the next start is a new drive", "0", String.valueOf(p.stage));
        s.ignition = 1;
        run(p, s, 3);
        check("next start: Driver Only again", "driver_only on", presses(s));

        // the passenger already buckled at the start: no Driver Only
        s = new Sim();
        s.belt = 1;
        p = policy();
        run(p, s, 10);
        check("passenger buckled at the start: nothing pressed", "", presses(s));
        check("... done for this drive", "2", String.valueOf(p.stage));

        // climate off: nothing, then on during the drive: Driver Only then
        s = new Sim();
        s.on = 0;
        p = policy();
        run(p, s, 10);
        check("climate off: nothing", "", presses(s));
        s.on = 1;
        run(p, s, 3);
        check("climate on during the drive: Driver Only then", "driver_only on", presses(s));

        // a restart of the service in the same boot: no second Driver Only
        s = new Sim();
        s.driverOnly = 0;
        s.sync = 1;
        p = policy();
        p.stage = Policy.DRIVER_ONLY;   // what Main loads for the same boot id
        run(p, s, 10);
        check("restarted service, same drive: Driver Only not pressed again", "", presses(s));

        // ignition can't be read: nothing
        s = new Sim();
        s.ignition = -1;
        p = policy();
        run(p, s, 10);
        check("ignition unknown: nothing", "", presses(s));

        // HMG-API refuses (the owner hasn't allowed it yet): tried again, said once
        s = new Sim();
        s.refuse = "refused: waiting for the owner to allow climate for climate-creta (a question is on the screen)";
        p = policy();
        run(p, s, 4);
        check("refused: tried again", "refused driver_only on | refused driver_only on | refused driver_only on | refused driver_only on", presses(s));
        int said = 0;
        for (String l : log) if (l.contains("not sent")) said++;
        check("... said once", "1", String.valueOf(said));
        s.refuse = null;
        run(p, s, 2);
        check("... allowed: sent", "driver_only on", presses(s));

        // keeping Sync: the passenger's knob up two degrees
        s = new Sim();
        s.belt = 1;   // no Driver Only, so this is only about Sync
        p = policy();
        run(p, s, 5);
        presses(s);
        s.knob(24f);
        run(p, s, 40);
        check("passenger's knob: the driver steps up one at a time, then Sync",
                "driver_temp up | driver_temp up | driver_temp up | driver_temp up | sync on", presses(s));
        check("... both at 24, Sync on", "24.0 24.0 1", s.driver + " " + s.passenger + " " + s.sync);
        // down
        s.knob(23f);
        run(p, s, 30);
        check("knob down: steps down, then Sync", "driver_temp down | driver_temp down | sync on", presses(s));

        // Sync waits until the passenger side has been still for 700 ms
        s.knob(23.5f);
        p.next(s.look(), now);   // drop seen
        now += 150;
        String first = p.next(s.look(), now);
        check("one step needed: up", "driver_temp up", first);
        s.press(first);
        p.sent(first, true, "ok", now);
        now += 150;
        s.passenger = 24f;   // still turning
        String second = p.next(s.look(), now);
        check("... the passenger still turning: the step seen, the next one", "driver_temp up", second);
        s.press(second);
        p.sent(second, true, "ok", now);
        now += 150;
        check("... caught up but the passenger moved 150 ms ago: no Sync yet", "null", String.valueOf(p.next(s.look(), now)));
        presses(s);
        run(p, s, 6);
        check("... Sync once still for 700 ms", "sync on", presses(s));

        // Sync turned off by hand: left off
        s = new Sim();
        s.belt = 1;
        p = policy();
        run(p, s, 3);
        s.sync = 0;   // the owner's button: no passenger change
        run(p, s, 10);
        check("Sync off by hand: nothing", "", presses(s));
        check("... said", "true", String.valueOf(logged("turned off by hand")));
        s.passenger = 25f;
        run(p, s, 10);
        check("... the passenger's knob while it's off: not followed", "", presses(s));
        s.sync = 1;
        s.passenger = s.driver;
        run(p, s, 3);
        s.knob(23f);
        run(p, s, 20);
        check("Sync on again: followed again", "driver_temp up | driver_temp up | sync on", presses(s));

        // the driver changes their own temperature while catching up: Sync (the passenger follows the driver)
        s = new Sim();
        s.belt = 1;
        p = policy();
        run(p, s, 3);
        s.knob(26f);
        p.next(s.look(), now);
        now += 150;
        String step = p.next(s.look(), now);
        s.press(step);
        p.sent(step, true, "ok", now);
        now += 150;
        s.driver = 21f;   // the driver turned theirs down
        check("the driver turns theirs: Sync", "sync on", String.valueOf(p.next(s.look(), now)));

        // a step the car never shows: lost after 1 s, sent again, at most 40 per change
        s = new Sim();
        s.belt = 1;
        p = policy();
        run(p, s, 3);
        s.knob(23f);
        s.deaf = true;
        run(p, s, 14);   // 0 to 1950 ms: the first step, then again once it's 1 s old
        check("a lost step: sent again after 1 s", "driver_temp up | driver_temp up", presses(s));
        check("... said", "true", String.valueOf(logged("counted as lost")));
        run(p, s, 300);
        check("... at most 40 steps per change", "38", String.valueOf(s.presses.size()));
        check("... then stopped, said", "true", String.valueOf(logged("didn't catch up")));
        presses(s);

        // Driver Only on: the passenger side isn't followed
        s = new Sim();
        s.belt = 0;
        p = policy();
        run(p, s, 3);
        check("Driver Only set", "driver_only on", presses(s));
        s.passenger = 25f;
        run(p, s, 10);
        check("Driver Only on: the passenger side isn't followed", "", presses(s));

        // climate off: nothing followed
        s = new Sim();
        s.belt = 1;
        p = policy();
        run(p, s, 3);
        s.on = 0;
        s.knob(26f);
        run(p, s, 10);
        check("climate off: nothing", "", presses(s));
        System.exit(fail);
    }
}
