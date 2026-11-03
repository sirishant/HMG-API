package hmgapi.mods.projextras;

import java.util.HashSet;
import java.util.Set;

/**
 * When Android Auto and CarPlay give way to each other: the timings and choices, apart from
 * the hooks, so they can be tested off the car. One instance per PhoneProjection process;
 * every call comes on its main thread.
 */
final class Rules {
    /** a switch question is answered within this, or it no longer counts */
    static final long ASK_MS = 60000;
    /** the projection button while CarPlay runs: CarPlay is asked to end at most this often */
    static final long DISCONNECT_GAP_MS = 30000;
    /** and Android Auto is tried again this often, for this long after asking */
    static final long RETRY_MS = 4000, RETRY_WINDOW_MS = 15000;
    /** the car's power (MicomPowerManager): delay mode, pre-off and off */
    static final int ACC_DELAY = 6;

    static final int TYPE_NONE = 0, TYPE_CARPLAY = 1, TYPE_ANDROID_AUTO = 2;

    private String askAddr;
    private long askAt = -1, disconnectAt = -1;
    /** phones whose Android Auto was ended on the head unit: no automatic start until their button */
    private final Set<String> aaEnded = new HashSet<String>();

    /** CarPlay started while Android Auto runs: ask, and remember for whom */
    void asked(String aaAddr, long now) {
        askAddr = aaAddr;
        askAt = now;
    }

    /** the answer is to our question: same phone, in time; it counts once */
    boolean answer(String addr, long now) {
        boolean ours = askAddr != null && askAddr.equals(addr) && now - askAt <= ASK_MS;
        if (ours || (askAt >= 0 && now - askAt > ASK_MS)) {
            askAddr = null;
            askAt = -1;
        }
        return ours;
    }

    /** CarPlay closed: an open question is moot */
    void carPlayClosed() {
        askAddr = null;
        askAt = -1;
    }

    /** the projection button wants Android Auto while CarPlay runs: ask CarPlay to end now? */
    boolean sendDisconnect(long now) {
        if (disconnectAt >= 0 && now - disconnectAt < DISCONNECT_GAP_MS) return false;
        disconnectAt = now;
        return true;
    }

    /** keep trying Android Auto while CarPlay winds down */
    boolean retry(long now) {
        return disconnectAt >= 0 && now - disconnectAt <= RETRY_WINDOW_MS;
    }

    void aaEndedOnHeadUnit(String addr) {
        if (addr != null) aaEnded.add(addr);
    }

    /** the phone's own button (a Bluetooth start) brings automatic starts back */
    void aaButton(String addr) {
        if (addr != null) aaEnded.remove(addr);
    }

    /** the car went to delay mode or off: the next drive starts fresh */
    void carOff() {
        aaEnded.clear();
    }

    /** an automatic Android Auto start: held while the car is off, or after it was ended on the head unit */
    boolean holdAutoStart(String addr, int acc) {
        return acc >= ACC_DELAY || (addr != null && aaEnded.contains(addr));
    }

    static boolean carOffStatus(int acc) {
        return acc >= ACC_DELAY;
    }
}
