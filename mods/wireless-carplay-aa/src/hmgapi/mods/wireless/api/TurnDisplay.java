package hmgapi.mods.wireless.api;

import android.content.Context;
import android.os.Handler;

import hmgapi.hook.Cluster;

/**
 * Lets a car-specific mod decide how Android Auto's and CarPlay's turn-by-turn look on its
 * cluster. With none registered, AaTurns and CpTurns behave as they always have. All calls
 * come on the caller's thread. A mod that requires this one gets the Registry with use("wireless-carplay-aa",
 * "turn_display") and registers a Factory.
 */
public interface TurnDisplay {
    /** Makes one per connection, in the app that runs it (Android Auto's, or DiPO for CarPlay). */
    interface Factory {
        TurnDisplay create(Context context, Cluster cluster, Handler handler);
    }

    /** Holds the factory the car's mod registered. */
    final class Registry {
        private volatile Factory factory;

        public void set(Factory f) {
            factory = f;
        }

        public Factory get() {
            return factory;
        }
    }

    /** The library's raw navigation dump ("S ..." or "P ..."). */
    void dump(String dump);

    /** CarPlay's stand-in for dump: meters to the turn and to the destination (-1: not known). */
    void position(int meters, int destMeters);

    int meters(int fallback);

    int e3(int fallback);

    int unit(int fallback);

    /** The roundabout's exit angle as AaTurns's table reads it. */
    int roundaboutAngle(int angle);

    int roundaboutType(int type);

    /** The destination's display type, from the type AaTurns has. */
    int destType(int type);

    /** Sends the turn frame, in place of AaTurns's own. */
    void send(int type, float direction, float distance, int unit, int bar, int barErr);

    /** True to skip sending this road name. */
    boolean roadUnchanged(String road);

    void status(int status);

    /** Guidance ended: put the cluster right, in place of AaTurns's blank frames. */
    void guidanceEnded();
}
