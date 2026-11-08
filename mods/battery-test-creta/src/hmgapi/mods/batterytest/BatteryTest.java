package hmgapi.mods.batterytest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

/**
 * One-off test: where does the Creta keep a 12V reading?
 *
 * The telematics CAN service (tm_hscan_service, TM_HSCANService in system_server) answers
 * getBatSoc only while it sees the ignition on, and turns the CAN "not available" value 255
 * into -1; Battery Watchdog read 0 there. The car's body module logs "batSoc = 255" (no 12V
 * sensor value on its bus). The same service keeps an EV signal store (mCanCsm, a CanCsmHEV
 * on EVs) with the DC-DC converter's low-side voltage and current (get_CR_Ldc_ActVol_LS_V,
 * get_CR_Ldc_ActCur_LS_A), which charges the 12V battery while the car is on: possibly the
 * 12V bus voltage, if those frames reach the head unit at all (unknown: that's the test).
 *
 * Reads only: the service's own getters and the store's no-argument get/is methods whose
 * names are about the 12V battery, the converter or the ignition. Nothing is set or sent.
 * In system_server (where the service object itself is reachable), from 60 s after the car's
 * automotive services load, every 30 s; a line whenever a value changes and all values every
 * 10 minutes, in the HMG-API tag (the drive log).
 */
public final class BatteryTest extends ModEntry {
    static final String SERVICE = "tm_hscan_service";
    static final Pattern WANTED = Pattern.compile(
        "(?i).*(ldc|ibs|batsnsr|bat_?soc|auxbatt|volt|12v|ig3|ign_?(sts|stat|on)).*");
    static final String[] SERVICE_GETTERS = { "getBatSoc", "getBATSNSRState", "getBATSNSRInvalid",
        "getBATSNSRError", "get_BAT_AuxBattOpt", "getVehicleStartStatus", "getChargingStatus" };
    static final long FIRST_MS = 60000, EVERY_MS = 30000, FULL_MS = 600000;

    private static boolean sStarted;

    @Override
    public void zygoteInit(final ModContext mod) {
        mod.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (sStarted || android.os.Process.myUid() != 1000) return;
                sStarted = true;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        loop(mod);
                    }
                }, "hmgapi-12v-test");
                t.setDaemon(true);
                t.start();
            }
        });
    }

    static void loop(ModContext mod) {
        sleep(FIRST_MS);
        Object svc = null, csm = null;
        List<Method> store = new ArrayList<Method>();
        String last = null;
        long full = 0;
        int tries = 0;
        while (true) {
            try {
                if (svc == null) {
                    svc = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, SERVICE);
                    if (svc == null) {
                        if (tries++ % 20 == 0) mod.log("12V: " + SERVICE + " not registered yet");
                        sleep(EVERY_MS);
                        continue;
                    }
                    mod.log("12V: service " + svc.getClass().getName());
                    csm = field(svc, "mCanCsm");
                    if (csm != null) {
                        for (Method m : csm.getClass().getMethods()) {
                            if (m.getParameterTypes().length == 0 && !Modifier.isStatic(m.getModifiers())
                                && (m.getName().startsWith("get") || m.getName().startsWith("is"))
                                && WANTED.matcher(m.getName()).matches()) store.add(m);
                        }
                        StringBuilder ifs = new StringBuilder();
                        for (Class<?> c : csm.getClass().getInterfaces()) ifs.append(' ').append(c.getSimpleName());
                        mod.log("12V: signal store " + csm.getClass().getName() + " (" + ifs.toString().trim()
                            + "), " + store.size() + " getters: " + names(store));
                    } else {
                        mod.log("12V: no signal store (mCanCsm) in the service");
                    }
                }
                StringBuilder b = new StringBuilder();
                b.append("ign=").append(ign(svc));
                for (String g : SERVICE_GETTERS) b.append(' ').append(g).append('=').append(call(svc, g));
                for (Method m : store) b.append(' ').append(m.getName()).append('=').append(invoke(m, csm));
                String now = b.toString();
                long t = System.currentTimeMillis();
                if (!now.equals(last) || t - full >= FULL_MS) {
                    mod.log("12V: " + now);
                    last = now;
                    full = t;
                }
            } catch (Throwable e) {
                if (tries++ % 20 == 0) mod.error("12V test", e);
            }
            sleep(EVERY_MS);
        }
    }

    /** what the service itself goes by for getBatSoc (its body CAN ignition) */
    static String ign(Object svc) {
        try {
            Object body = svc.getClass().getMethod("getBodyCanApi").invoke(svc);
            return String.valueOf(body.getClass().getMethod("getIGNStatus").invoke(body));
        } catch (Throwable e) {
            return "?";
        }
    }

    static String call(Object o, String name) {
        try {
            return String.valueOf(o.getClass().getMethod(name).invoke(o));
        } catch (NoSuchMethodException e) {
            return "none";
        } catch (Throwable e) {
            return "!" + root(e).getClass().getSimpleName();
        }
    }

    static String invoke(Method m, Object o) {
        try {
            return String.valueOf(m.invoke(o));
        } catch (Throwable e) {
            return "!" + root(e).getClass().getSimpleName();
        }
    }

    static Object field(Object o, String name) throws IllegalAccessException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // the superclass, then
            }
        }
        return null;
    }

    static String names(List<Method> ms) {
        StringBuilder b = new StringBuilder();
        for (Method m : ms) b.append(b.length() == 0 ? "" : ",").append(m.getName());
        return b.toString();
    }

    static Throwable root(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // go on
        }
    }
}
