package hmgapi.mods.projextras;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * NAV and MAP with Android Auto connected, from the car's own screens (in system_server, the
 * car's key dispatcher). On Android Auto's screen AaKeys already answers them.
 *
 * The car sends a released NAV (short) as NAVI_SHORTCUT, a held NAV as NAVI_SHORTCUT_LONG (at the
 * hold, its navigation's own long function), and MAP, short or held, as MAP_SHORTCUT on release:
 * each opens the car's navigation. Media goes to Android Auto when it's the media source, but no
 * stock rule sends NAV or MAP to a phone.
 *
 * With Android Auto available (the car's mode 0x74, which GPM sets once its video is up):
 *  - a short NAV or MAP opens Android Auto's screen instead (the same screen and flags GPM's own
 *    startMainActivity uses); AaKeys, which heard the press in GPM, then sends Android Auto's
 *    navigation key as the screen comes up;
 *  - a held NAV or MAP keeps the car's own: its navigation (NAV with its long function).
 * Without Android Auto, and for the car's own MAP_SHORTCUT (a star key set to Map, the automatic
 * map at a destination), everything is as stock.
 */
final class NavKeys {
    static final int KEYCODE_NAVI = 255, KEYCODE_MAP = 260;
    static final int MODE_ANDROID_AUTO = 0x74;
    static final String NAVI_SHORTCUT = "com.hkmc.intent.action.NAVI_SHORTCUT";
    static final String MAP_SHORTCUT = "com.hkmc.intent.action.MAP_SHORTCUT";
    static final String GPM = "com.google.android.projection.sink";
    static final String GPM_SCREEN = "com.google.android.projection.sink.ui.MainActivity";
    /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION, as GPM's own startMainActivity */
    static final int SCREEN_FLAGS = 0x10010000;
    /**
     * NEMESIS Manager opens on three NAV presses: it counts the car's NAVI_SHORTCUT broadcasts
     * (SetupReceiver). A NAV press taken for Android Auto still counts there: the same broadcast,
     * to NEMESIS Manager only (the car's navigation doesn't get it).
     */
    static final String NEMESIS = "com.nemesis.manager";

    static void tellNemesis(Context c) {
        if (c != null) c.sendBroadcast(new Intent(NAVI_SHORTCUT).setPackage(NEMESIS));
    }

    /** the button being handled now, on the dispatcher's thread (sendBroadcastKeyEvent) */
    private static final ThreadLocal<Integer> sKey = new ThreadLocal<Integer>();
    /** MAP held: the car marks the press long (FLAG_LONG_PRESS) before it's released */
    private static volatile boolean sMapHeld;
    private static boolean sInstalled;

    private NavKeys() {}

    /** In the zygote: once system_server loads automotive-service.jar, hook its key dispatcher. */
    static void zygote(final ModContext mod) {
        mod.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (sInstalled || p.getResult() == null) return;
                try {
                    ClassLoader cl = (ClassLoader) Points.get(null, "framework.hkmc_loader.class_loader", p.getResult());
                    install(mod, cl);
                } catch (Throwable t) {
                    mod.error("NAV and MAP keys", t);
                }
            }
        });
    }

    static void install(final ModContext mod, final ClassLoader cl) {
        sInstalled = true;
        mod.hook(cl, "framework.mode_key_dispatcher.send_broadcast_key_event", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int action = (Integer) p.args[0], code = (Integer) p.args[1], flags = (Integer) p.args[2];
                int repeat = (Integer) p.args[4];
                if (code != KEYCODE_NAVI && code != KEYCODE_MAP) return;
                if (code == KEYCODE_MAP && action == KeyEvent.ACTION_DOWN) {
                    if (repeat == 0) sMapHeld = false;
                    if ((flags & KeyEvent.FLAG_LONG_PRESS) != 0) sMapHeld = true;
                }
                sKey.set(code);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                sKey.remove();
            }
        });
        mod.hook(cl, "framework.mode_key_dispatcher.request_shortcut", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Integer code = sKey.get();
                if (code == null) return;
                String action = (String) p.args[0];
                boolean shortPress = NAVI_SHORTCUT.equals(action) || (MAP_SHORTCUT.equals(action) && !sMapHeld);
                if (!shortPress) return;
                try {
                    if (!androidAuto(cl, p.thisObject)) return;
                    Context c = (Context) Points.get(cl, "framework.mode_key_dispatcher.context", p.thisObject);
                    c.startActivity(new Intent().setClassName(GPM, GPM_SCREEN).addFlags(SCREEN_FLAGS));
                    if (code == KEYCODE_NAVI) tellNemesis(c);
                } catch (Throwable t) {
                    mod.error("NAV and MAP keys", t);
                    return;   // the car's own navigation, as stock
                }
                p.setResult(null);
                mod.log("Android Auto: " + (code == KEYCODE_NAVI ? "NAV" : "MAP") + " from a car screen, Android Auto's screen");
            }
        });
    }

    /** Android Auto is connected: the car's own mode for it is available */
    static boolean androidAuto(ClassLoader cl, Object dispatcher) throws Exception {
        Object state = Points.get(cl, "framework.mode_key_dispatcher.mode_state", dispatcher);
        Object info = state == null ? null : Points.call(cl, "framework.mode_manager_state.get_mode_info", state, MODE_ANDROID_AUTO);
        return info != null && Boolean.TRUE.equals(Points.call(cl, "framework.mode_info.is_available", info));
    }
}
