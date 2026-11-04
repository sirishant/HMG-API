package hmgapi.mods.projextras;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/**
 * The steering wheel's Call key opens Android Auto's phone screen.
 *
 * GPM's key listener answers Call (5) with Android Auto's phone key (KEYCODE_TEL) only when the
 * head unit's spec level (ro.product.opt.stepofspec) is below 31. The Creta's is 105, so Call
 * sends nothing to the phone, and the car's own BTCALL_SHORTCUT service isn't there either. For
 * the length of a Call key event only, GPM reads a level below 31, so its own checks decide as on
 * older units: Android Auto on screen, no Bluetooth call going on, rear camera not showing.
 */
final class CallKey {
    static final String GPM = "com.google.android.projection.sink";
    static final int KEYCODE_CALL = 5;
    static final int SPEC_WITH_TEL = 0;   // any level below 31

    private static final ThreadLocal<Boolean> sInCall = new ThreadLocal<Boolean>();

    private CallKey() {}

    static void gpm(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.processName) || !mod.settingBoolean("aa_call_key", true)) return;
        final ClassLoader cl = app.classLoader;
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
    }
}
