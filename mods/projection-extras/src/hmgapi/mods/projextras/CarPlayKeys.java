package hmgapi.mods.projextras;

import android.view.KeyEvent;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The car's keys on CarPlay, the way Android Auto now has them.
 * Not yet tried with an iPhone: built from DiPO's own code.
 *
 * Screen keys: the CarPlay screen (DipoActivity.dispatchKeyEvent) answers every key "not handled",
 * so a released Media, NAV or MAP key goes on to the car's own media screen or navigation. On the
 * CarPlay screen, a short press of
 *  - NAV (255) or MAP (260) asks the iPhone for Maps (DiPO's own displayMap: requestUI maps:);
 *  - Media (251) asks for Now Playing (displayMusic: requestUI nowplaying:).
 * The key then counts as handled, so the car skips its own action. Held, each does what the car
 * does.
 *
 * Call (5) with no call going on: on the car's own screens, DiPO only shows a message that there's
 * no call. It now asks the iPhone for CarPlay's Phone instead (displayPhone: requestUI
 * mobilephone:). With a call, and on the CarPlay screen (where the press goes to the iPhone as its
 * hook switch), Call is unchanged.
 */
final class CarPlayKeys {
    private static final ThreadLocal<Boolean> sInCall = new ThreadLocal<Boolean>();

    private CarPlayKeys() {}

    static void dipo(final ModContext mod, LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "dipo.activity.dispatch_key_event", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (!(m.args[0] instanceof KeyEvent)) return;
                KeyEvent e = (KeyEvent) m.args[0];
                int code = e.getKeyCode();
                String point, what;
                if (code == AaKeys.KEYCODE_MEDIA_CAR) {
                    point = "dipo.service.display_music";
                    what = "Media key, Now Playing";
                } else if (code == AaKeys.KEYCODE_NAVI || code == AaKeys.KEYCODE_MAP) {
                    point = "dipo.service.display_map";
                    what = (code == AaKeys.KEYCODE_NAVI ? "NAV" : "MAP") + " key, Maps";
                } else {
                    return;
                }
                if (!AaKeys.shortRelease(e)) return;
                Object svc = CarPlayFixes.service(cl);
                if (svc == null) return;
                try {
                    Points.call(cl, point, svc);
                } catch (Throwable t) {
                    mod.error("CarPlay " + what, t);
                    return;
                }
                m.setResult(Boolean.TRUE);
                mod.log("CarPlay: " + what);
            }
        });
        mod.hook(cl, "dipo.service.call_key", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                sInCall.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(Param m) {
                sInCall.remove();
            }
        });
        mod.hook(cl, "dipo.service.no_call_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (sInCall.get() == null) return;
                try {
                    Points.call(cl, "dipo.service.display_phone", m.thisObject);
                } catch (Throwable t) {
                    mod.error("CarPlay Call key", t);
                    return;
                }
                m.setResult(null);
                mod.log("CarPlay: Call key, Phone");
            }
        });
    }
}
