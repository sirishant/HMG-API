package hmgapi.mods.projextras;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.lang.reflect.Method;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/**
 * The Bluetooth watchdog, and the driver's own Bluetooth actions (after Enhanced Phone
 * Projection 1.23's reconnect watchdog and manual connect guard, NEMESIS, AGPL-3.0; our own
 * code, written for HMG-API's hook points; EPP put its rules into the Bluetooth app itself).
 *
 * BtSettings: what the driver does on the car's Bluetooth screens goes through Broadcom's
 * manager (btsettings.btcm.*); each connect, disconnect, delete or cancel goes to DiPO as
 * hmgapi.wireless.BT_BY_HAND ("what": connect, phone, audio, disconnect; "address"). The
 * watchdog holds on a disconnect and lets go on a connect; the Wireless mod's bridge (2.5.3
 * and later) starts its manual connect guard on a handsfree-only ("phone") or audio-only
 * ("audio") connect: no automatic CarPlay start for that iPhone for 25 s.
 *
 * DiPO: the watchdog itself (the rules are BtRules). Once a phone has been on Bluetooth this
 * drive and none is any more, it starts the car's own auto-connect through Broadcom's manager
 * (IbtcmDoDriveAutoConnection: the Bluetooth service's btcmStartAutoConnection, the same call
 * EPP's watchdog makes), 15 s apart at first, then 60 s, then 3 min. Seen on the Creta
 * (2026-11-05): the Pixel dropped off Bluetooth 50 s into a drive while Android Auto restarted
 * and the car never paged it again, so Android Auto was gone for the remaining 37 minutes.
 *
 * Educated guesses, not yet tried on the Creta (labelled in the log, easy to take back):
 *  - EPP arms its watchdog on an iPhone's first CarPlay Bluetooth link; this one arms on any
 *    phone, so the Android Auto case above is covered too.
 *  - The car's auto-connect doesn't put anything on the screen (none is seen at power-on).
 *  - A phone the driver disconnects on the phone itself can't be told from one that dropped
 *    off: the watchdog brings it back (as EPP's does); disconnecting on the car's screen holds it.
 *  - CarPlay ended on the head unit (DISCONNECT_CARPLAY, the projection switch): the watchdog
 *    holds, as EPP's does, so the iPhone doesn't come back and start CarPlay again by itself.
 * Uninstalling or disabling the mod leaves the car's own Bluetooth behaviour: nothing is
 * written anywhere, the watchdog only calls the manager's public methods.
 */
final class BtWatch {
    static final String BTSETTINGS = "com.android.btsettings";
    static final String ACTION_BY_HAND = "hmgapi.wireless.BT_BY_HAND";
    static final String CONNECT = "connect", PHONE = "phone", AUDIO = "audio", DISCONNECT = "disconnect";
    static final String DISCONNECT_CARPLAY = "com.daudio.app.dipo.DISCONNECT_CARPLAY";
    static final int MAX_ERRORS = 3;

    private BtWatch() {}

    // ---------------------------------------------------------------- BtSettings

    static void btSettings(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        byHand(mod, app, cl, "btsettings.btcm.connect", CONNECT, "connect");
        byHand(mod, app, cl, "btsettings.btcm.only_hfp_connect", PHONE, "phone-only connect");
        byHand(mod, app, cl, "btsettings.btcm.only_a2dp_connect", AUDIO, "audio-only connect");
        byHand(mod, app, cl, "btsettings.btcm.disconnect", DISCONNECT, "disconnect");
        byHand(mod, app, cl, "btsettings.btcm.only_hfp_disconnect", DISCONNECT, "phone-only disconnect");
        byHand(mod, app, cl, "btsettings.btcm.only_a2dp_disconnect", DISCONNECT, "audio-only disconnect");
        byHand(mod, app, cl, "btsettings.btcm.remove_device", DISCONNECT, "delete");
        byHand(mod, app, cl, "btsettings.btcm.cancel_connection", DISCONNECT, "cancel");
        mod.log("Bluetooth: the driver's connects and disconnects on the car's Bluetooth screen go to the watchdog and CarPlay's manual connect guard");
    }

    /** before the manager's call (so DiPO knows before the phone's connection events come): tell DiPO */
    private static void byHand(final ModContext mod, final LoadedApp app, ClassLoader cl, String point, final String what, final String label) {
        mod.hook(cl, point, new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    Object a = p.args.length > 0 ? p.args[0] : null;
                    String addr = a instanceof BluetoothDevice ? ((BluetoothDevice) a).getAddress() : a instanceof String ? (String) a : null;
                    Intent i = new Intent(ACTION_BY_HAND).setPackage(Switch.DIPO).putExtra("what", what);
                    if (addr != null) i.putExtra("address", addr);
                    app.context.sendBroadcast(i);
                    mod.log("Bluetooth: " + label + " by hand on the car's screen" + (addr == null ? "" : " (" + mask(addr) + ")"));
                } catch (Throwable t) {
                    mod.error("Bluetooth: " + label, t);
                }
            }
        });
    }

    /** a Bluetooth address for the log: only its last two bytes */
    static String mask(String a) {
        if (a == null) return "(none)";
        int n = a.length();
        return n < 5 ? "xx" : "xx:xx:xx:xx:" + a.substring(n - 5);
    }

    // ---------------------------------------------------------------- DiPO: the watchdog

    static final BtRules rules = new BtRules();
    static volatile Object manager;
    static Handler handler;
    static ModContext mod;
    static Context context;
    private static int errors;
    private static boolean noDipoState;
    private static final Method[] M = new Method[6];
    private static final String[] NAMES = { "IbtcmIsReady", "IbtcmGetCountConnectedDevices", "IbtcmIsBusy", "IbtcmIsAutoConnection",
            "IbtcmGetPairedDevicesCount", "IbtcmDoDriveAutoConnection" };
    private static final int READY = 0, COUNT = 1, BUSY = 2, AUTO = 3, PAIRED = 4, AUTO_CONNECT = 5;

    static void dipo(final ModContext m, final LoadedApp app) {
        mod = m;
        context = app.context;
        HandlerThread t = new HandlerThread("hmgapi-btwatch");
        t.start();
        handler = new Handler(t.getLooper());
        // Broadcom's manager, once DiPO has it connected (one per process; the Wireless bridge watches the same call)
        m.hook(app.classLoader, "dipo.btcm.register_listener", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                manager = p.thisObject;
            }
        });
        try {
            Intent last = context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    power(i.getIntExtra(Switch.EXTRA_ACC, -1));
                }
            }, new IntentFilter(Switch.ACC_CHANGED));
            if (last != null) power(last.getIntExtra(Switch.EXTRA_ACC, -1));
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    final String what = i.getStringExtra("what");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (DISCONNECT.equals(what)) {
                                if (rules.hold()) mod.log("Bluetooth watchdog: held, a phone was let go on the car's Bluetooth screen; no reconnect until a phone connects again");
                            } else if (CONNECT.equals(what) || PHONE.equals(what) || AUDIO.equals(what)) {
                                if (rules.connectedByHand()) mod.log("Bluetooth watchdog: hold over, a phone was connected by hand");
                            }
                        }
                    });
                }
            }, new IntentFilter(ACTION_BY_HAND));
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (rules.hold()) mod.log("Bluetooth watchdog: held, CarPlay was ended on the head unit; the iPhone isn't brought back by itself");
                        }
                    });
                }
            }, new IntentFilter(DISCONNECT_CARPLAY));
        } catch (Throwable x) {
            m.error("Bluetooth watchdog: receivers", x);
        }
        handler.postDelayed(TICK, BtRules.FAST_MS);
        m.log("Bluetooth watchdog: on (a phone that drops off Bluetooth during a drive is brought back with the car's auto-connect)");
    }

    private static void power(final int acc) {
        if (acc < 0) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (Rules.carOffStatus(acc)) {
                    if (!rules.isCarOff()) mod.log("Bluetooth watchdog: car off (" + acc + "), standing down until it's on");
                    rules.carOff();
                } else {
                    boolean was = rules.isCarOff();
                    rules.carOn(SystemClock.elapsedRealtime());
                    if (was) mod.log("Bluetooth watchdog: car on, the car's own auto-connect goes first");
                }
            }
        });
    }

    static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            try {
                look();
            } catch (Throwable t) {
                if (++errors <= MAX_ERRORS) mod.error("Bluetooth watchdog", t);
            }
            handler.postDelayed(this, rules.delay());
        }
    };

    private static void look() throws Exception {
        Object m = manager;
        if (m == null || rules.isCarOff() || !Boolean.TRUE.equals(call(m, READY))) return;
        int connected = (Integer) call(m, COUNT);
        long now = SystemClock.elapsedRealtime();
        int r;
        if (connected > 0 || !rules.isArmed() || rules.isHeld()) {
            r = rules.tick(connected, false, false, 1, now);   // the rest only matters for a try
        } else {
            boolean busy = Boolean.TRUE.equals(call(m, BUSY)) || Boolean.TRUE.equals(call(m, AUTO));
            r = rules.tick(connected, busy, carPlayActive(), (Integer) call(m, PAIRED), now);
        }
        if (r == BtRules.ARMED) {
            mod.log("Bluetooth watchdog: armed, a phone is on Bluetooth (brought back if it drops off this drive)");
        } else if (r == BtRules.BACK) {
            mod.log("Bluetooth watchdog: a phone is back on Bluetooth after " + rules.lastTries() + " auto-connect tr" + (rules.lastTries() == 1 ? "y" : "ies"));
        } else if (r == BtRules.RELEASED) {
            mod.log("Bluetooth watchdog: hold over, a phone connected again");
        } else if (r == BtRules.RECONNECT) {
            int n = rules.tries();
            Object ok = call(m, AUTO_CONNECT);
            String next = n == BtRules.FAST_TRIES ? ", slowing down to every " + BtRules.MID_MS / 1000 + " s"
                    : n == BtRules.MID_TRIES ? ", slowing down to every " + BtRules.SLOW_MS / 60000 + " min" : "";
            mod.log("Bluetooth watchdog: no phone on Bluetooth, starting the car's auto-connect (try " + n + ") = " + ok + next);
        }
    }

    /** DiPO's CarPlay is active (DiPOManager, as the Bluetooth service's own check); when that can't be read, taken as active */
    private static boolean carPlayActive() {
        try {
            Object dm = context.getSystemService("dipo");
            return Boolean.TRUE.equals(dm.getClass().getMethod("isDiPOActivated").invoke(dm));
        } catch (Throwable t) {
            if (!noDipoState) {
                noDipoState = true;
                mod.error("Bluetooth watchdog: can't tell whether CarPlay runs, it stands down", t);
            }
            return true;
        }
    }

    private static Object call(Object m, int k) throws Exception {
        if (M[k] == null) M[k] = m.getClass().getMethod(NAMES[k]);
        return M[k].invoke(m);
    }
}
