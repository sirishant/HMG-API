package hmgapi.mods.creta;

import java.lang.reflect.Method;

import android.content.Context;
import android.os.Handler;

import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;
import hmgapi.mods.wireless.api.TurnDisplay;

/**
 * Registers the Creta Electric's cluster display with the Wireless mod, in Android
 * Auto's app. CRETA-SPECIFIC: mod.json's "system" keeps this mod off every other car.
 */
public final class CretaMod extends ModEntry {
    static final String GPM = "com.google.android.projection.sink";
    static final String WIRELESS = "wireless-carplay-aa";

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.packageName)) return;
        Object r = mod.use(WIRELESS, "turn_display");
        if (!(r instanceof TurnDisplay.Registry)) {
            mod.log("the Wireless mod has no turn display here, nothing to do");
            return;
        }
        ((TurnDisplay.Registry) r).set(new TurnDisplay.Factory() {
            @Override
            public TurnDisplay create(Context context, Cluster cluster, Handler handler) {
                mod.log("Android Auto: Creta cluster display for this connection");
                return new CretaRhd(context, cluster, handler, trapFrame(mod, context));
            }
        });
    }

    /**
     * The car's bar moves with the trap frame. This is stock GPM's clear of that frame,
     * which also removes the speed-limit sign, with the level in place of its fixed 100.
     * HMG-API's Cluster has no call for it, so it goes to the car's own NavigationCluster.
     */
    private static CretaRhd.TrapFrame trapFrame(final ModContext mod, Context context) {
        Method m = null;
        Object nc = null;
        try {
            nc = Points.newInstance(null, "framework.navigation_cluster.new", context);
            Class<?>[] ints = new Class<?>[17];
            java.util.Arrays.fill(ints, int.class);
            m = nc.getClass().getMethod("updateTBTTrapInfo", ints);
        } catch (Throwable t) {
            mod.error("no trap frame, the bar won't move", t);
        }
        final Method method = m;
        final Object cluster = nc;
        return new CretaRhd.TrapFrame() {
            @Override
            public void bar(int level) {
                if (method == null) return;
                try {
                    method.invoke(cluster, -1, -1, 3, -1, 0, 0, 0, 0, 15, 0xffff, 3, level, 255, 15, 3, 3, 15);
                } catch (Throwable t) {
                    mod.error("trap frame", t);
                }
            }
        };
    }
}
