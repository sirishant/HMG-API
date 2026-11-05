package hmgapi.mods.projextras;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * A call already going when CarPlay connects. A tester found that CarPlay then says the call is
 * on the car, but neither side hears anything; a call started once CarPlay is up works. Not yet
 * tried: built from DiPO's own code (a guess at the cause). The log says when a session starts
 * during a Bluetooth call and when the call is handed over.
 *
 * When a session starts during a Bluetooth handsfree call, DiPO (updateInitAppState) takes the
 * screen and the main audio from the iPhone for good ("isBTCall BORROW NEVER / NEVER", reason
 * 16). It gives them back in its "Bluetooth to CarPlay" step (message 5, setDipoConnectStatus),
 * which only does so once the session is active (DiPO status 4). That step runs 1 s after the
 * session details, and again when the iPhone turns its Bluetooth off for CarPlay, which wired
 * CarPlay does. Wireless CarPlay keeps Bluetooth on, so if the session isn't active by that one
 * second, nothing gives the audio back and the call stays silent.
 *
 * So: if this session started during a Bluetooth call, DiPO's own step runs again once the
 * session is active. With the same phone it hands the call to CarPlay; with another phone in the
 * call, DiPO's own wait for that call stays as it is.
 */
final class CarPlayCalls {
    /** DiPO's message: hand Bluetooth over to CarPlay (HANDLER_MESSAGE_DISCONNECT_BT) */
    static final int MSG_BT_TO_CARPLAY = 5;

    private static final ThreadLocal<Boolean> sStarting = new ThreadLocal<Boolean>();
    private static volatile boolean sHeldForCall;

    private CarPlayCalls() {}

    static void dipo(final ModContext mod, LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "dipo.service.start_carplay_session", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                sHeldForCall = false;
                sStarting.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                sStarting.remove();
                if (sHeldForCall) mod.log("CarPlay: session started during a Bluetooth call (DiPO holds the audio for it)");
            }
        });
        mod.hook(cl, "dipo.call_utils.is_bt_call", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (sStarting.get() != null && Boolean.TRUE.equals(p.getResult())) sHeldForCall = true;
            }
        });
        mod.hook(cl, "dipo.service.dipo_activated", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!sHeldForCall) return;
                sHeldForCall = false;
                try {
                    Points.call(cl, "dipo.service.post_message", p.thisObject, MSG_BT_TO_CARPLAY, 0L);
                    mod.log("CarPlay: active during a Bluetooth call, handing the call to CarPlay");
                } catch (Throwable t) {
                    mod.error("CarPlay call handover", t);
                }
            }
        });
    }
}
