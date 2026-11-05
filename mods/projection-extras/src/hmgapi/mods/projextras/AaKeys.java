package hmgapi.mods.projextras;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The car's keys on Android Auto, as the phone expects them.
 *
 * Call: GPM's key listener answers Call (5) with Android Auto's phone key (KEYCODE_TEL) only when
 * the head unit's spec level (ro.product.opt.stepofspec) is below 31. The Creta's is 105, so Call
 * sent nothing, and the car's own BTCALL_SHORTCUT service isn't there either. For the length of a
 * Call key event only, GPM reads a level below 31, so its own checks decide as on older units:
 * Android Auto on screen, no Bluetooth call going on, rear camera not showing.
 *
 * Screen keys: a key released on Android Auto's screen goes on to the car's own action for that
 * button unless the screen says it handled it (the car's ModeKeyDispatcher: "return by view").
 * On Android Auto's screen, a short press of
 *  - Media (251), which popped up the car's media screen, runs GPM's own onStartMusicView:
 *    Android Auto's media key, the phone's media screen;
 *  - NAV (255) or MAP (260), which opened the car's navigation over Android Auto, runs GPM's own
 *    onStartNaviView: Android Auto's navigation key, the phone's navigation app.
 * The key then counts as handled, so the car skips its own action. Held, each button does what
 * the car does (Media: the car's media screen).
 *
 * Short and held are the car's own: a press is held once the car marks it long (FLAG_LONG_PRESS,
 * the point where it runs a button's long function).
 *
 * From the car's own screens, a short Media press goes to the car, which (with Android Auto as
 * the source) brings Android Auto's screen up at its last view (GPM's shortcut handling:
 * onStartMusicCommand for every shortcut); a short NAV or MAP brings it up through NavKeys. Every car key also reaches GPM's key listener
 * (onAllKeyForService, before any screen gets it), so a short press is remembered (and forgotten
 * when Android Auto's own screen takes it), and if Android Auto's screen comes up within PENDING_MS, the same Android Auto key
 * goes to the phone then. If the car opens its own screen instead, nothing more happens.
 *
 * Voice guide: GPM shows "Press and hold the Voice Recognition button for Android Auto voice
 * recognition" (message 17) whenever the car's assistant starts. With the voice button swapped
 * (press for the phone's, hold for the car's), holding is the car's assistant, so the guide isn't
 * shown.
 */
final class AaKeys {
    static final String GPM = "com.google.android.projection.sink";
    static final int KEYCODE_CALL = 5, KEYCODE_MEDIA_CAR = 251, KEYCODE_NAVI = 255, KEYCODE_MAP = 260;
    static final int SPEC_WITH_TEL = 0;   // any level below 31
    static final int OSD_VR_GUIDE = 17;
    static final long PENDING_MS = 2000;

    private static final ThreadLocal<Boolean> sInCall = new ThreadLocal<Boolean>();
    private static volatile Object sService;
    /**
     * the car's own button presses (onAllKeyForService, before any screen): the one down now,
     * whether the car has marked it held (FLAG_LONG_PRESS, its own hold point), and a short one
     * waiting for Android Auto's screen
     */
    private static int sDownCode = -1, sPendingCode = -1;
    private static boolean sHeld;
    private static long sPendingAt;

    private AaKeys() {}

    /** a short press released now (held presses and cancelled ones are the car's) */
    static boolean shortRelease(KeyEvent e) {
        return e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout();
    }

    /** Android Auto's media or navigation key, through GPM's own onStartMusicView / onStartNaviView */
    static boolean aaKey(ModContext mod, ClassLoader cl, int code) {
        String point, what;
        if (code == KEYCODE_MEDIA_CAR) {
            point = "gpm.receiver_service.start_music_view";
            what = "Media key, media screen";
        } else {
            point = "gpm.receiver_service.start_navi_view";
            what = (code == KEYCODE_NAVI ? "NAV" : "MAP") + " key, navigation";
        }
        Object svc = sService;
        if (svc == null) return false;
        try {
            Points.call(cl, point, svc);
        } catch (Throwable t) {
            mod.error("Android Auto " + what, t);
            return false;
        }
        mod.log("Android Auto: " + what);
        return true;
    }

    static boolean screenKey(int code) {
        return code == KEYCODE_MEDIA_CAR || code == KEYCODE_NAVI || code == KEYCODE_MAP;
    }

    static void gpm(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.processName)) return;
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "gpm.receiver_service.on_create", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                sService = m.thisObject;
            }
        });
        mod.hook(cl, "gpm.main_activity.on_key_up", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                int code = (Integer) m.args[0];
                if (!screenKey(code)) return;
                if (!(m.args[1] instanceof KeyEvent)) return;
                KeyEvent e = (KeyEvent) m.args[1];
                if (e.getAction() != KeyEvent.ACTION_UP || e.isCanceled()) return;
                synchronized (AaKeys.class) {
                    if (sDownCode == code && sHeld) return;   // held: the car's own
                    sPendingCode = -1;   // the listener hears every key before the screen does
                }
                if (aaKey(mod, cl, code)) m.setResult(Boolean.TRUE);
            }
        });
        mod.hook(cl, "gpm.key_listener.all_keys", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                int action = (Integer) m.args[0], flags = (Integer) m.args[1], code = (Integer) m.args[3];
                int repeat = (Integer) m.args[6];
                if (!screenKey(code)) return;
                synchronized (AaKeys.class) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (repeat == 0 || sDownCode != code) {
                            sDownCode = code;
                            sHeld = false;
                        }
                        if ((flags & KeyEvent.FLAG_LONG_PRESS) != 0) sHeld = true;
                    } else if (action == KeyEvent.ACTION_UP && sDownCode == code && !sHeld
                            && !Boolean.TRUE.equals(m.args[7])) {
                        sPendingCode = code;
                        sPendingAt = SystemClock.uptimeMillis();
                    }
                }
            }
        });
        mod.hook(cl, "gpm.main_activity.on_resume", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                int code;
                synchronized (AaKeys.class) {
                    code = sPendingCode;
                    sPendingCode = -1;
                    if (code < 0 || SystemClock.uptimeMillis() - sPendingAt > PENDING_MS) return;
                }
                aaKey(mod, cl, code);
            }
        });
        mod.hook(cl, "gpm.key_listener.key_for_service", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (((Integer) m.args[3]) != KEYCODE_CALL) return;
                sInCall.set(Boolean.TRUE);
                if (((Integer) m.args[0]) == 0) mod.log("Android Auto: Call key, phone screen");
            }

            @Override
            protected void afterHookedMethod(Param m) {
                sInCall.remove();
            }
        });
        mod.hook(cl, "gpm.util.spec_step", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                if (sInCall.get() != null) m.setResult(SPEC_WITH_TEL);
            }
        });
        mod.hook(cl, "gpm.receiver_service.send_osd_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (((Integer) m.args[1]) == OSD_VR_GUIDE) m.setResult(null);
            }
        });
    }
}
