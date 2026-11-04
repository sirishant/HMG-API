package hmgapi.mods.projextras;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;

/**
 * Android Auto and CarPlay take turns instead of running at once (after Enhanced Phone
 * Projection's switching, written for HMG-API's hook points):
 *  - CarPlay starts while Android Auto runs: the car's own question asks which to keep.
 *  - The projection button for Android Auto while CarPlay runs: CarPlay ends first.
 *  - Android Auto ended on the head unit doesn't start again by itself until that phone's
 *    button, and doesn't start automatically while the car is off.
 * DiPO's start broadcast doesn't say whether CarPlay is wired or which iPhone it is; DiPO's
 * side adds both, and ends CarPlay when PhoneProjection asks (DISCONNECT_CARPLAY).
 */
public final class Switch extends ModEntry {
    static final String DIPO = "com.daudio.app.dipo", PP = "com.daudio.app.phoneprojection";
    static final String DIPO_START = "com.daudio.app.dipo.start", DIPO_CLOSE = "com.daudio.app.dipo.close";
    static final String DISCONNECT_CARPLAY = "com.daudio.app.dipo.DISCONNECT_CARPLAY";
    static final String CHANGE_YES = "com.daudio.app.PhoneProjection.Wireless.CHANGE_USB_PP_YES";
    static final String ACC_CHANGED = "com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED";
    static final String EXTRA_ACC = "android.ibox.mode.EXTRA_ACC_STATUS";
    static final int TRANSPORT_WIRELESS = 1;

    static final String S = "phoneprojection.wireless_service", INFO = "phoneprojection.status_info";

    @Override
    public void appLoaded(ModContext mod, LoadedApp app) {
        if (DIPO.equals(app.packageName)) {
            dipo(mod, app);
            CarPlayFixes.dipo(mod, app);
        } else if (PP.equals(app.packageName)) {
            phoneProjection(mod, app);
        } else if (CarPlayFixes.IAP2.equals(app.packageName)) {
            CarPlayFixes.iap2(mod, app);
        } else if (CallKey.GPM.equals(app.packageName)) {
            CallKey.gpm(mod, app);
        }
    }

    // ---------------------------------------------------------------- DiPO

    static volatile int transport = -1;
    static volatile Object dipoService;

    private static void dipo(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "dipo.service.dipo_activated", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                transport = (Integer) p.args[0];
                dipoService = p.thisObject;
            }
        });
        mod.hook(null, "framework.context_wrapper.send_broadcast", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[0];
                if (i == null || !DIPO_START.equals(i.getAction()) || i.hasExtra("wired")) return;
                i.putExtra("wired", transport != TRANSPORT_WIRELESS);
                try {
                    Object name = dipoService == null ? null : Points.get(cl, "dipo.service.device_name", dipoService);
                    if (name != null) i.putExtra("name", String.valueOf(name));
                } catch (Throwable t) {
                    // no name: PhoneProjection says "iPhone"
                }
            }
        });
        try {
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    mod.log("CarPlay: ending it, PhoneProjection asked");
                    CarPlayFixes.endCarPlay(mod, cl);
                }
            }, new IntentFilter(DISCONNECT_CARPLAY));
        } catch (Throwable t) {
            mod.error("DiPO: disconnect receiver", t);
        }
        mod.log("DiPO: start says wired or wireless and the iPhone's name; CarPlay ends on request");
    }

    // ---------------------------------------------------------------- PhoneProjection

    static volatile Object service;
    static final Rules rules = new Rules();
    static Handler main;

    private static void phoneProjection(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        main = new Handler(Looper.getMainLooper());
        final boolean ask = true, button = true, auto = true;

        mod.hook(cl, S + ".on_create", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                service = p.thisObject;
                watchPower(mod, (Context) p.thisObject);
            }
        });
        mod.hook(cl, S + ".on_destroy", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (service == p.thisObject) service = null;
            }
        });

        // CarPlay started: if Android Auto runs, the car's own question
        mod.hook(cl, "phoneprojection.device_status_receiver.on_receive", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[1];
                if (i == null) return;
                if (DIPO_CLOSE.equals(i.getAction())) {
                    rules.carPlayClosed();
                    return;
                }
                if (!ask || !DIPO_START.equals(i.getAction())) return;
                try {
                    Object st = status(cl);
                    if (st == null || intOf(Points.call(cl, INFO + ".pp_type", st)) != Rules.TYPE_ANDROID_AUTO) return;
                    String bt = (String) Points.call(cl, INFO + ".bt_address", st);
                    String serial = (String) Points.call(cl, INFO + ".usb_serial", st);
                    String aaName = (String) Points.call(cl, INFO + ".device_name", st);
                    String iphone = i.getStringExtra("name");
                    rules.asked(bt, SystemClock.elapsedRealtime());
                    Points.call(cl, "phoneprojection.wireless_util.show_device_change", null, (Context) service, bt, serial,
                            Rules.TYPE_CARPLAY, 0, aaName, iphone == null || iphone.length() == 0 ? "iPhone" : iphone);
                    mod.log("CarPlay started while Android Auto runs: asking which to keep");
                } catch (Throwable t) {
                    mod.error("CarPlay start", t);
                }
            }
        });

        // the answer to our question: keep CarPlay (Android Auto ends) or keep Android Auto (CarPlay ends)
        mod.hook(cl, "phoneprojection.wireless_receiver.on_receive", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[1];
                if (i == null || !CHANGE_YES.equals(i.getAction())) return;
                if (!rules.answer(i.getStringExtra("deviceAddress"), SystemClock.elapsedRealtime())) return;
                p.setResult(null);
                if (i.getBooleanExtra("done", false)) {
                    mod.log("switch: CarPlay stays, Android Auto ends");
                    dropAndroidAuto(mod, cl);
                } else {
                    mod.log("switch: Android Auto stays, CarPlay ends");
                    ((Context) p.args[0]).sendBroadcast(new Intent(DISCONNECT_CARPLAY).setPackage(DIPO));
                }
            }
        });

        // the projection button for Android Auto while CarPlay runs: CarPlay ends first
        mod.hook(cl, S + ".connect_handler", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!button) return;
                try {
                    if (!Boolean.TRUE.equals(Points.call(cl, "phoneprojection.state.carplay_connected", null))) return;
                    final String bt = (String) p.args[0], serial = (String) p.args[1];
                    if (bt != null && bt.equals(carPlayAddress(cl))) return;   // that iPhone itself: stock
                    final Object svc = p.thisObject;
                    long now = SystemClock.elapsedRealtime();
                    if (rules.sendDisconnect(now)) {
                        mod.log("projection button while CarPlay runs: ending CarPlay first");
                        ((Context) svc).sendBroadcast(new Intent(DISCONNECT_CARPLAY).setPackage(DIPO));
                    }
                    p.setResult(null);
                    if (rules.retry(now)) {
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!rules.retry(SystemClock.elapsedRealtime())) return;
                                try {
                                    Points.call(cl, S + ".connect_handler", svc, bt, serial);
                                } catch (Throwable t) {
                                    mod.error("Android Auto retry", t);
                                }
                            }
                        }, Rules.RETRY_MS);
                    }
                } catch (Throwable t) {
                    mod.error("projection button", t);
                }
            }
        });

        // a disconnect with no projection of PhoneProjection's own while CarPlay runs: that's CarPlay
        mod.hook(cl, S + ".disconnect_handler", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (!button) return;
                try {
                    Object st = status(cl);
                    int type = st == null ? Rules.TYPE_NONE : intOf(Points.call(cl, INFO + ".pp_type", st));
                    if (type > Rules.TYPE_NONE) return;
                    if (!Boolean.TRUE.equals(Points.call(cl, "phoneprojection.state.carplay_connected", null))) return;
                    mod.log("disconnect while CarPlay runs: ending CarPlay");
                    ((Context) p.thisObject).sendBroadcast(new Intent(DISCONNECT_CARPLAY).setPackage(DIPO));
                    p.setResult(null);
                } catch (Throwable t) {
                    mod.error("disconnect", t);
                }
            }
        });

        if (!auto) return;
        // Android Auto ended on the head unit: no automatic start for that phone until its button
        mod.hook(cl, "phoneprojection.binder.disconnect_pp", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (intOf(p.args[0]) == Rules.TYPE_ANDROID_AUTO) rules.aaEndedOnHeadUnit((String) p.args[1]);
            }
        });
        mod.hook(cl, S + ".connect_device", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    Object info = p.args[0];
                    if (info == null) return;
                    String bt = (String) info.getClass().getMethod("getWLDevBtAddr").invoke(info);
                    if (Boolean.TRUE.equals(p.args[2])) {
                        rules.aaButton(bt);
                        return;
                    }
                    int ready = intOf(p.args[1]);
                    int transfer = intOf(info.getClass().getMethod("getWLTransferType").invoke(info));
                    if (transfer == 0 && ready == 1) return;   // wired: stock
                    if (!Boolean.TRUE.equals(info.getClass().getMethod("getWLDAutoConnection").invoke(info))) return;
                    if (!rules.holdAutoStart(bt, acc((Context) p.thisObject))) return;
                    p.args[0] = withoutAutoConnect(info);
                    mod.log("Android Auto: no automatic start for " + bt + " (car off, or ended on the head unit)");
                } catch (Throwable t) {
                    mod.error("automatic start", t);
                }
            }
        });
        mod.log("PhoneProjection: Android Auto and CarPlay take turns");
    }

    /** a copy of the device info with automatic connection off; the stored one stays as it is */
    static Object withoutAutoConnect(Object info) throws Exception {
        Parcel out = Parcel.obtain();
        try {
            info.getClass().getMethod("writeToParcel", Parcel.class, int.class).invoke(info, out, 0);
            out.setDataPosition(0);
            Object copy = info.getClass().getConstructor(Parcel.class).newInstance(out);
            copy.getClass().getMethod("setWLDAutoConnection", boolean.class).invoke(copy, false);
            return copy;
        } finally {
            out.recycle();
        }
    }

    static Object status(ClassLoader cl) {
        Object svc = service;
        return svc == null ? null : Points.get(cl, S + ".status", svc);
    }

    static String carPlayAddress(ClassLoader cl) {
        try {
            Object pp = Points.get(cl, S + ".pp_manager", service);
            return pp == null ? null : (String) pp.getClass().getMethod("getCPConnectedBTAddress").invoke(pp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Android Auto off, with the values PhoneProjection itself would use */
    static void dropAndroidAuto(ModContext mod, ClassLoader cl) {
        try {
            Object st = status(cl);
            if (st == null) return;
            int type = intOf(Points.call(cl, INFO + ".pp_type", st));
            if (type != Rules.TYPE_ANDROID_AUTO) return;
            String bt = (String) Points.call(cl, INFO + ".bt_address", st);
            Points.call(cl, S + ".disconnect_pp", service, type, bt, Points.call(cl, INFO + ".usb_serial", st),
                    intOf(Points.call(cl, INFO + ".transfer_type", st)));
            rules.aaEndedOnHeadUnit(bt);
        } catch (Throwable t) {
            mod.error("ending Android Auto", t);
        }
    }

    /** the car's power status from its sticky broadcast (0 on), or 0 if unknown */
    static int acc(Context c) {
        try {
            Intent last = c.registerReceiver(null, new IntentFilter(ACC_CHANGED));
            return last == null ? 0 : last.getIntExtra(EXTRA_ACC, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void watchPower(final ModContext mod, Context c) {
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    if (Rules.carOffStatus(i.getIntExtra(EXTRA_ACC, 0))) rules.carOff();
                }
            }, new IntentFilter(ACC_CHANGED));
        } catch (Throwable t) {
            mod.error("power status", t);
        }
    }

    static int intOf(Object o) {
        return o instanceof Integer ? (Integer) o : -1;
    }
}
