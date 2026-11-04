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
 * Media: a short press of Media (251) on Android Auto's screen popped up the car's media screen.
 * It now runs GPM's own onStartMusicView: Android Auto's media key, the phone's media screen.
 * Holding Media still opens the car's media screen.
 *
 * Voice guide: GPM shows "Press and hold the Voice Recognition button for Android Auto voice
 * recognition" (message 17) whenever the car's assistant starts. With the voice swap on, holding
 * is the car's assistant, so the guide isn't shown.
 */
final class AaKeys {
    static final String GPM = "com.google.android.projection.sink";
    static final int KEYCODE_CALL = 5, KEYCODE_MEDIA_CAR = 251;
    static final int SPEC_WITH_TEL = 0;   // any level below 31
    static final int OSD_VR_GUIDE = 17;

    private static final ThreadLocal<Boolean> sInCall = new ThreadLocal<Boolean>();
    private static volatile Object sService;

    private AaKeys() {}

    static void gpm(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.processName)) return;
        final ClassLoader cl = app.classLoader;
        final boolean call = mod.settingBoolean("aa_call_key", true);
        final boolean media = mod.settingBoolean("aa_media_key", true);
        mod.hook(cl, "gpm.key_listener.key_for_service", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                if (sService == null) {
                    sService = Points.get(cl, "gpm.key_listener.service", m.thisObject);
                }
                if (!call || ((Integer) m.args[3]) != KEYCODE_CALL) return;
                sInCall.set(Boolean.TRUE);
                if (((Integer) m.args[0]) == 0) mod.log("Android Auto: Call key, phone screen");
            }

            @Override
            protected void afterHookedMethod(Param m) {
                sInCall.remove();
            }
        });
        if (call) {
            mod.hook(cl, "gpm.util.spec_step", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param m) {
                    if (sInCall.get() != null) m.setResult(SPEC_WITH_TEL);
                }
            });
        }
        if (media) {
            mod.hook(cl, "gpm.main_activity.on_key_up", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param m) {
                    if (((Integer) m.args[0]) != KEYCODE_MEDIA_CAR || !(m.args[1] instanceof KeyEvent)) return;
                    KeyEvent e = (KeyEvent) m.args[1];
                    if (e.getEventTime() - e.getDownTime() >= ViewConfiguration.getLongPressTimeout()) return;
                    Object svc = sService;
                    if (svc == null) return;
                    m.setResult(Boolean.FALSE);
                    mod.log("Android Auto: Media key, media screen");
                    Points.call(cl, "gpm.receiver_service.start_music_view", svc);
                }
            });
        }
        if (mod.settingBoolean("voice_swap", true)) {
            mod.hook(cl, "gpm.receiver_service.send_osd_message", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param m) {
                    if (((Integer) m.args[1]) == OSD_VR_GUIDE) m.setResult(null);
                }
            });
        }
    }
}
