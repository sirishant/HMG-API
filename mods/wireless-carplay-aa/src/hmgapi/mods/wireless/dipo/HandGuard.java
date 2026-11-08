package hmgapi.mods.wireless.dipo;

/**
 * The manual connect guard (after Enhanced Phone Projection 1.23's, NEMESIS, AGPL-3.0; our
 * own code): an iPhone the driver connected by hand on the car's Bluetooth screen for its
 * handsfree only or its audio only gets no automatic CarPlay start for WINDOW_MS. Picking
 * one profile says "this phone on Bluetooth", not "start CarPlay"; once the window is over
 * the bridge starts CarPlay again on the phone's next Bluetooth start, as before. A full
 * connect (handsfree and audio) isn't counted: it's also what follows a new pairing, after
 * which CarPlay should start. Unlike EPP's, the guard doesn't connect audio by itself
 * when it holds a start: the driver's profile choice stays.
 *
 * Who connected what by hand comes from BtSettings (projection-extras on the Creta, where
 * its points are mapped), as Bridge.ACTION_BT_BY_HAND. No Android here, for the host test.
 */
final class HandGuard {
    static final long WINDOW_MS = 25000;
    /** check(): start as usual; held back (say so); held back (already said) */
    static final int FREE = 0, HELD = 1, HELD_QUIET = 2;

    private String addr;
    private long until;
    private boolean told;

    /** the driver connected this phone by hand, handsfree or audio only */
    synchronized void connectedByHand(String a, long now) {
        if (a == null || a.length() == 0) return;
        addr = a.toUpperCase();
        until = now + WINDOW_MS;
        told = false;
    }

    /** an automatic CarPlay start for this phone now: FREE, or HELD the first time it's held back */
    synchronized int check(String a, long now) {
        if (a == null || addr == null || !addr.equals(a.toUpperCase())) return FREE;
        if (now >= until) {
            addr = null;
            return FREE;
        }
        if (told) return HELD_QUIET;
        told = true;
        return HELD;
    }

    /** seconds the guard has left (for the log) */
    synchronized long secondsLeft(long now) {
        return addr == null || now >= until ? 0 : (until - now + 999) / 1000;
    }

    /** a Bluetooth address for the log: only its last two bytes */
    static String mask(String a) {
        if (a == null) return "(none)";
        int n = a.length();
        return n < 5 ? "xx" : "xx:xx:xx:xx:" + a.substring(n - 5);
    }
}
