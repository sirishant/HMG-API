package hmgapi.mods.creta;

import java.lang.reflect.Method;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;
import hmgapi.mods.wireless.api.TurnDisplay;

/**
 * Registers the Creta Electric's cluster display with the Wireless mod, in Android
 * Auto's app and in DiPO for CarPlay. CRETA-SPECIFIC: mod.json's "system" keeps this mod off every other car.
 */
public final class CretaMod extends ModEntry {
    static final String GPM = "com.google.android.projection.sink";
    static final String DIPO = "com.daudio.app.dipo";
    static final String WIRELESS = "wireless-carplay-aa";
    static final String NAVIGATION = "com.mnsoft.navi";
    static final String TRAP_POINT = "framework.navigation_cluster.update_tbt_trap_info";
    /** the car navigation's latest trap frame, from its process to Android Auto's */
    static final String ACTION_NAVI_TRAP = "hmgapi.creta.NAVI_TRAP";
    static final int[] TRAP_CLEAR = CretaRhd.TRAP_CLEAR;

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (NAVIGATION.equals(app.packageName)) {
            shareNaviTrap(mod, app);
            return;
        }
        if (!GPM.equals(app.packageName) && !DIPO.equals(app.packageName)) return;
        Object r = mod.use(WIRELESS, "turn_display");
        if (!(r instanceof TurnDisplay.Registry)) {
            mod.log("the Wireless mod has no turn display here, nothing to do");
            return;
        }
        ((TurnDisplay.Registry) r).set(new TurnDisplay.Factory() {
            @Override
            public TurnDisplay create(Context context, Cluster cluster, Handler handler) {
                mod.log("Creta cluster display for this connection");
                return new CretaRhd(context, cluster, handler, trapFrame(mod, context));
            }
        });
    }

    /**
     * In the car navigation: every trap frame it sends (speed limit, cameras, tolls) also
     * goes to Android Auto's app, so the bar's frames carry them instead of clearing them.
     */
    private static void shareNaviTrap(final ModContext mod, final LoadedApp app) {
        mod.hook(null, TRAP_POINT, new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    if (p.args == null || p.args.length != TRAP_CLEAR.length) return;
                    int[] f = new int[TRAP_CLEAR.length];
                    for (int i = 0; i < f.length; i++) f[i] = (Integer) p.args[i];
                    app.context.sendBroadcast(new Intent(ACTION_NAVI_TRAP).setPackage(GPM).putExtra("trap", f));
                    app.context.sendBroadcast(new Intent(ACTION_NAVI_TRAP).setPackage(DIPO).putExtra("trap", f));
                } catch (Throwable t) {
                    mod.error("navigation trap frame", t);
                }
            }
        });
        mod.log("Creta: the navigation's speed limit and cameras go along with the bar");
    }

    static volatile int[] naviTrap;
    private static boolean listening;

    /** once per process: the navigation's frames as they come */
    private static synchronized void listenNaviTrap(ModContext mod, Context context) {
        if (listening) return;
        try {
            Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    int[] f = i.getIntArrayExtra("trap");
                    if (f != null && f.length == TRAP_CLEAR.length) naviTrap = f;
                }
            }, new IntentFilter(ACTION_NAVI_TRAP));
            listening = true;
        } catch (Throwable t) {
            mod.error("navigation trap frames", t);
        }
    }

    /**
     * The car's bar moves with the trap frame: the navigation's latest frame (or stock GPM's
     * clear before it sent one) with the level in place. HMG-API's Cluster has no call for
     * it, so it goes to the car's own NavigationCluster.
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
        listenNaviTrap(mod, context);
        return new CretaRhd.TrapFrame() {
            @Override
            public void bar(int level) {
                if (method == null) return;
                try {
                    int[] f = CretaRhd.trapWith(naviTrap, level);
                    Object[] args = new Object[f.length];
                    for (int i = 0; i < f.length; i++) args[i] = f[i];
                    method.invoke(cluster, args);
                } catch (Throwable t) {
                    mod.error("trap frame", t);
                }
            }
        };
    }
}
