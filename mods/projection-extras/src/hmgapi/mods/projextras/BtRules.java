package hmgapi.mods.projextras;

/**
 * The Bluetooth watchdog's rules, apart from the hooks so they can be tested off the car
 * (after Enhanced Phone Projection 1.23's reconnect watchdog, NEMESIS, AGPL-3.0; our own
 * code). One instance in DiPO's process; every call comes on the watchdog's thread.
 *
 * Once a phone has been on Bluetooth this drive, the watchdog looks every FAST_MS. When no
 * phone is connected any more it starts the car's own auto-connect (the one the car runs at
 * power-on, through Broadcom's manager): every FAST_MS for FAST_TRIES tries, then every
 * MID_MS up to MID_TRIES, then every SLOW_MS until a phone is back or the car goes off.
 *
 * It does nothing while:
 *  - the car is off (delay mode or off), or for CAR_ON_GRACE_MS after it comes on (the
 *    car's own power-on auto-connect runs then);
 *  - no phone has been connected since the car came on (it only brings back a link that
 *    went away; EPP's waits for the first CarPlay Bluetooth link, ours for any phone, so it
 *    also covers Android Auto: an educated guess, see BtWatch);
 *  - CarPlay runs (DiPO activated): CarPlay keeps the iPhone off Bluetooth on purpose;
 *  - Broadcom's manager is busy (connecting, paging, its own auto-connect) or no phone is
 *    paired: these tries aren't counted;
 *  - it's held: the driver disconnected or deleted a phone, or cancelled a connection, on the
 *    car's Bluetooth screen, or CarPlay was ended on the head unit. The hold lasts until a
 *    phone connects again by itself after none was connected, the driver connects one by
 *    hand, or the car goes off.
 */
final class BtRules {
    static final long FAST_MS = 15000, MID_MS = 60000, SLOW_MS = 180000;
    static final int FAST_TRIES = 20, MID_TRIES = 45;
    static final long CAR_ON_GRACE_MS = 30000;

    /** tick(): nothing to do; start the car's auto-connect; armed now; a phone is back after tries; hold released */
    static final int IDLE = 0, RECONNECT = 1, ARMED = 2, BACK = 3, RELEASED = 4;

    private boolean armed, carOff, held, goneWhileHeld;
    private int tries;
    /** the tries before the last BACK (for the log) */
    private int lastTries;
    private long carOnAt = -1;

    /** wait before the next look */
    long delay() {
        return tries < FAST_TRIES ? FAST_MS : tries < MID_TRIES ? MID_MS : SLOW_MS;
    }

    int tries() {
        return tries;
    }

    int lastTries() {
        return lastTries;
    }

    boolean isArmed() {
        return armed;
    }

    boolean isHeld() {
        return held;
    }

    boolean isCarOff() {
        return carOff;
    }

    /** delay mode, pre-off or off: nothing until the car is on again, and the next drive starts fresh */
    void carOff() {
        carOff = true;
        armed = false;
        held = false;
        tries = 0;
    }

    /** the car is on (from off, or the first status seen) */
    void carOn(long now) {
        if (!carOff && carOnAt >= 0) return;
        carOff = false;
        carOnAt = now;
        armed = false;
        held = false;
        tries = 0;
    }

    /** the driver (or the head unit's projection switch) let a phone go: no reconnect until one connects again; true if it's new */
    boolean hold() {
        boolean was = held;
        held = true;
        goneWhileHeld = false;
        tries = 0;
        return !was;
    }

    /** the driver connected a phone on the car's Bluetooth screen: a hold is over; true if there was one */
    boolean connectedByHand() {
        boolean was = held;
        held = false;
        return was;
    }

    /**
     * One look at the car's Bluetooth. connected: phones connected (any profile); busy:
     * Broadcom's manager is connecting, paging or auto-connecting; carPlay: DiPO's CarPlay is
     * active; paired: phones paired.
     */
    int tick(int connected, boolean busy, boolean carPlay, int paired, long now) {
        if (carOff) return IDLE;
        if (connected > 0) {
            int r = IDLE;
            if (held && goneWhileHeld) {
                held = false;
                r = RELEASED;
            }
            if (!armed) {
                armed = true;
                if (r == IDLE) r = ARMED;
            } else if (tries > 0 && r == IDLE) {
                lastTries = tries;
                r = BACK;
            }
            tries = 0;
            return r;
        }
        if (held) {
            goneWhileHeld = true;
            tries = 0;
            return IDLE;
        }
        if (!armed) return IDLE;
        if (carPlay) {
            tries = 0;
            return IDLE;
        }
        if (carOnAt >= 0 && now - carOnAt < CAR_ON_GRACE_MS) return IDLE;
        if (busy || paired <= 0) return IDLE;
        tries++;
        return RECONNECT;
    }
}
