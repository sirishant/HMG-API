package hmgapi.mods.projextras;

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

    private static final ThreadLocal<Boolean> sInCall = new ThreadLocal<Boolean>();
    private static volatile Object sService;

    private AaKeys() {}

    /** a short press released now (held presses and cancelled ones are the car's) */
    static boolean shortRelease(KeyEvent e) {
        return e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout();
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
                String point, what;
                if (code == KEYCODE_MEDIA_CAR) {
                    point = "gpm.receiver_service.start_music_view";
                    what = "Media key, media screen";
                } else if (code == KEYCODE_NAVI || code == KEYCODE_MAP) {
                    point = "gpm.receiver_service.start_navi_view";
                    what = (code == KEYCODE_NAVI ? "NAV" : "MAP") + " key, navigation";
                } else {
                    return;
                }
                if (!(m.args[1] instanceof KeyEvent) || !shortRelease((KeyEvent) m.args[1])) return;
                Object svc = sService;
                if (svc == null) return;
                try {
                    Points.call(cl, point, svc);
                } catch (Throwable t) {
                    mod.error("Android Auto " + what, t);
                    return;
                }
                m.setResult(Boolean.TRUE);
                mod.log("Android Auto: " + what);
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
