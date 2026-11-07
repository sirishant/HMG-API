package hmgapi.mods.climate;

/**
 * What to press on the climate, from what the car says. Plain Java: the service feeds it
 * the car's readings a few times a second and sends what it asks for (HMG-API's climate
 * command); the host test feeds it a made-up car.
 *
 * Driver Only for each drive, until the front passenger buckles up: once the car (the
 * ignition) and the climate are on, Driver Only is set, unless the passenger is buckled
 * by then. Remote climate before the drive (the car off) is left as it is, both sides, until
 * the car is turned on. When the passenger buckles up while Driver Only is on, the climate
 * goes back to Sync, once: Driver Only off, as the climate screen's button does (the car
 * returns to Sync), and Sync pressed only if it doesn't come back. The stage is kept with the head unit's boot id (Main), so a restart of the service
 * doesn't do it again; the car turned off starts the next drive over.
 *
 * Sync kept: the passenger's knob drops Sync on this car. When Sync drops with the
 * passenger's temperature changing, the driver's temperature follows it one step at a time
 * (one step at a time in flight, a step not seen within STEP_MS counts as lost, MAX_STEPS
 * per change), and once the passenger side has been still for STILL_MS, Sync goes back on.
 * The driver changing their own temperature meanwhile ends the catching up: Sync, so the
 * passenger follows the driver. Sync turned off by hand (nothing on the passenger side
 * changes within OWNER_MS) is left off: nothing is followed until Sync is on again. Nothing
 * while the climate is off or Driver Only is on.
 */
final class Policy {
    interface Log {
        void line(String text);
    }

    /** one look at the car; -1 and NaN where it didn't say */
    static final class Car {
        int ignition = -1, belt = -1, on = -1, sync = -1, driverOnly = -1;
        float driver = Float.NaN, passenger = Float.NaN;
    }

    static final int NEW = 0, DRIVER_ONLY = 1, DONE = 2;
    static final long STEP_MS = 1000, STILL_MS = 700, OWNER_MS = 500;
    static final int MAX_STEPS = 40;

    static final String DRIVER_ONLY_ON = "driver_only on", DRIVER_ONLY_OFF = "driver_only off", SYNC_ON = "sync on",
            UP = "driver_temp up", DOWN = "driver_temp down";

    final Log log;
    /** this drive: Driver Only still to set (NEW), set and waiting for the passenger (DRIVER_ONLY), nothing more (DONE) */
    int stage = NEW;
    boolean stageChanged;

    private Car last;
    private boolean beltWas, syncWanted;
    // keeping Sync
    private boolean paused, catching;
    private float target;
    private long passengerAt, dropAt = -1, offAt = -STEP_MS;
    private int steps;
    private String inFlight;
    private long inFlightAt;
    private String lastRefusal;

    Policy(Log log) {
        this.log = log;
    }

    private void stage(int s, String why) {
        if (stage == s) return;
        stage = s;
        stageChanged = true;
        log.line(why);
    }

    private void stopCatching() {
        catching = false;
        inFlight = null;
        dropAt = -1;
    }

    static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    /** the press to send now, or null */
    String next(Car c, long now) {
        try {
            return decide(c, now);
        } finally {
            last = c;
        }
    }

    private String decide(Car c, long now) {
        if (c.ignition != 1) {
            if (c.ignition == 0 && stage != NEW) stage(NEW, "Climate: the car is off; Driver Only again at the next start");
            beltWas = false;
            syncWanted = false;
            stopCatching();
            return null;
        }
        if (c.on != 1) {
            stopCatching();
            return null;
        }
        // Driver Only, once this drive, unless the passenger is already buckled
        if (stage == NEW) {
            if (c.belt < 0 || c.driverOnly < 0) return null;   // can't tell yet
            if (c.belt == 1) {
                stage(DONE, "Climate: the passenger is buckled at the start; Driver Only not set");
            } else if (c.driverOnly == 1) {
                stage(DRIVER_ONLY, "Climate: Driver Only is already on");
            } else {
                return DRIVER_ONLY_ON;
            }
        }
        // the passenger buckles up with Driver Only on (or Driver Only was this drive's): Sync, once.
        // As the climate screen does it: Driver Only off brings Sync back; Sync pressed only if it doesn't
        if (c.belt == 1 && (!beltWas && c.driverOnly == 1 || stage == DRIVER_ONLY)) syncWanted = true;
        if (c.belt >= 0) beltWas = c.belt == 1;
        if (syncWanted) {
            if (c.driverOnly == 0 && c.sync == 1) {
                syncWanted = false;
                if (stage == DRIVER_ONLY) stage(DONE, "Climate: the passenger buckled up; Sync for the rest of this drive");
            } else if (c.driverOnly == 1) {
                return now - offAt >= STEP_MS ? DRIVER_ONLY_OFF : null;
            } else if (c.driverOnly == 0 && c.sync == 0) {
                return now - offAt >= STEP_MS ? SYNC_ON : null;   // Driver Only off didn't bring Sync back
            } else {
                return null;
            }
        }
        return keepSync(c, now);
    }

    private String keepSync(Car c, long now) {
        Car p = last;
        if (c.driverOnly != 0 || c.sync < 0 || Float.isNaN(c.driver) || Float.isNaN(c.passenger)
                || p == null || p.on != 1 || p.driverOnly != 0 || p.sync < 0 || Float.isNaN(p.driver) || Float.isNaN(p.passenger)) {
            stopCatching();
            return null;
        }
        if (c.sync == 1) {
            if (paused) log.line("Climate: Sync is on again; following the passenger's knob again");
            paused = false;
            stopCatching();
            return null;
        }
        boolean passengerMoved = !same(c.passenger, p.passenger), driverMoved = !same(c.driver, p.driver);
        if (p.sync == 1 && !paused) {   // Sync just dropped
            if (passengerMoved || !same(c.driver, c.passenger)) startCatching(c, now);
            else dropAt = now;
        }
        if (paused) return null;
        if (!catching) {
            if (dropAt < 0) return null;
            if (passengerMoved || !same(c.driver, c.passenger)) {
                startCatching(c, now);
            } else if (now - dropAt >= OWNER_MS) {
                dropAt = -1;
                paused = true;
                log.line("Climate: Sync was turned off by hand; not following until it's on again");
                return null;
            } else {
                return null;
            }
        }
        if (passengerMoved) {
            target = c.passenger;
            passengerAt = now;
            steps = 0;
        }
        if (inFlight != null) {
            if (driverMoved) {
                boolean ours = UP.equals(inFlight) ? c.driver > p.driver : c.driver < p.driver;
                inFlight = null;
                if (!ours) return driverChose();
            } else if (now - inFlightAt >= STEP_MS) {
                log.line("Climate: a temperature step wasn't seen within " + STEP_MS + " ms; counted as lost");
                inFlight = null;
            } else {
                return null;
            }
        } else if (driverMoved && !passengerMoved) {
            return driverChose();
        }
        if (same(c.driver, target)) return now - passengerAt >= STILL_MS ? SYNC_ON : null;
        if (steps >= MAX_STEPS) {
            log.line("Climate: the driver's temperature didn't catch up in " + MAX_STEPS + " steps; stopped");
            stopCatching();
            return null;
        }
        return c.driver < target ? UP : DOWN;
    }

    private void startCatching(Car c, long now) {
        catching = true;
        dropAt = -1;
        target = c.passenger;
        passengerAt = now;
        steps = 0;
        inFlight = null;
        log.line("Climate: the passenger's knob dropped Sync; the driver's temperature follows to " + c.passenger);
    }

    private String driverChose() {
        stopCatching();
        log.line("Climate: the driver changed their temperature; Sync, so the passenger follows the driver");
        return SYNC_ON;
    }

    /** what came of a press: ok, or the refusal */
    void sent(String press, boolean ok, String answer, long now) {
        if (!ok) {
            if (!answer.equals(lastRefusal)) log.line("Climate: " + press + " not sent: " + answer);
            lastRefusal = answer;
            return;
        }
        lastRefusal = null;
        if (UP.equals(press) || DOWN.equals(press)) {
            inFlight = press;
            inFlightAt = now;
            steps++;
            return;
        }
        log.line("Climate: " + press);
        if (DRIVER_ONLY_ON.equals(press)) {
            stage(DRIVER_ONLY, "Climate: Driver Only for this drive; Sync when the passenger buckles up");
        } else if (DRIVER_ONLY_OFF.equals(press) || SYNC_ON.equals(press)) {
            offAt = now;   // the car's status follows; the next press waits for it
            if (SYNC_ON.equals(press)) stopCatching();
        }
    }
}
