package hmgapi.mods.projextras;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * CarPlay session fixes in DiPO and the iAP2 server, after Enhanced Phone Projection 1.11,
 * written for HMG-API's hook points:
 *  - a fresh key frame when the CarPlay screen reports a decode error
 *  - the screen re-attached when a session starts, and shown once a wireless session's
 *    video surface is ready; the screen request retried for 15 s instead of closing
 *  - DiPO's wired-only connection-error check cancelled for a wireless session
 *  - Siri's end handled after 300 ms, not 800
 *  - the hotspot's name and password from the hotspot itself when the iAP2 server has none
 *  - the Wi-Fi details sent again when the iPhone says wireless CarPlay is available, and
 *    the wireless mod's attempt ended at once when it says it isn't (after Enhanced Phone
 *    Projection 1.23, NEMESIS, AGPL-3.0)
 *  - ending CarPlay on request (PhoneProjection's DISCONNECT_CARPLAY)
 */
final class CarPlayFixes {
    static final String IAP2 = "com.tcc.iap2server";
    /** DiPO's messages: the screen request, the connection-error check, Siri's end */
    static final int MSG_SCREEN = 7, MSG_CONNECTION_CHECK = 15, MSG_SIRI_END = 46;
    static final long SIRI_END_STOCK_MS = 800, SIRI_END_MS = 300;
    static final long REATTACH_MS = 1500, SCREEN_RETRY_MS = 1000, SCREEN_RETRY_FOR_MS = 15000, RESTART_MS = 3000;
    /** DiPO's screen modes (from its logs): 0 none yet, 2 CarPlay on the local screen */
    static final int SCREEN_NONE = 0, SCREEN_LOCAL = 2;
    /** CarPlay's transport in session_started: 1 Wi-Fi */
    static final int TRANSPORT_WIFI = 1;
    /** the iAP2 server's own message: send the Wi-Fi details */
    static final int IAP2_SEND_WIFI = 12;

    static volatile boolean wireless;
    static volatile Activity activity;
    static final Map<Object, Long> screenWaitSince = new WeakHashMap<Object, Long>();
    static Handler main;

    private CarPlayFixes() {}

    static Object service(ClassLoader cl) {
        return Points.get(cl, "dipo.service.instance", null);
    }

    static void dipo(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        main = new Handler(Looper.getMainLooper());

        mod.hook(cl, "dipo.carplay_listener.decode_failure", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                Object err = p.args[0];
                if (err == null || "UNKNOWN".equals(String.valueOf(err))) return;
                try {
                    Object owner = Points.get(cl, "dipo.carplay_listener.owner", p.thisObject);
                    Object cp = owner == null ? null : Points.get(cl, "dipo.iap2.carplay", owner);
                    if (cp == null) return;
                    Points.call(cl, "dipo.carplay.force_key_frame", cp);
                    mod.log("CarPlay: screen error " + err + ", asked for a key frame");
                } catch (Throwable t) {
                    mod.error("key frame", t);
                }
            }
        });

        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                wireless = Integer.valueOf(TRANSPORT_WIFI).equals(p.args[0]);
                try {
                    Object svc = service(cl);
                    if (wireless && svc != null) Points.call(cl, "dipo.service.remove_message", svc, MSG_CONNECTION_CHECK);
                } catch (Throwable t) {
                    mod.error("connection check", t);
                }
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Activity a = activity;
                        if (a == null || a.isFinishing()) return;
                        try {
                            Points.call(cl, "dipo.activity.update_display", a);
                            mod.log("CarPlay: screen re-attached for the new session");
                        } catch (Throwable t) {
                            mod.error("re-attach", t);
                        }
                    }
                }, REATTACH_MS);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                wireless = false;
            }
        });

        mod.hook(cl, "dipo.activity.set_display", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                activity = (Activity) p.thisObject;
            }
        });
        mod.hook(cl, "dipo.activity.on_destroy", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (activity == p.thisObject) activity = null;
            }
        });
        mod.hook(cl, "dipo.service.set_display", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (p.args[0] == null || !wireless) return;
                try {
                    Points.call(cl, "dipo.service.show_screen", p.thisObject);
                } catch (Throwable t) {
                    mod.error("show screen", t);
                }
            }
        });

        mod.hook(cl, "dipo.service.post_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Integer.valueOf(MSG_SIRI_END).equals(p.args[0]) && Long.valueOf(SIRI_END_STOCK_MS).equals(p.args[1])) {
                    p.args[1] = SIRI_END_MS;
                }
            }
        });

        mod.hook(cl, "dipo.activity_handler.handle_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Message m = (Message) p.args[0];
                if (m == null || m.what != MSG_SCREEN) return;
                try {
                    Object svc = service(cl);
                    if (svc == null) return;
                    Object mode = Points.call(cl, "dipo.service.screen_mode", svc);
                    int screen = mode == null ? -1 : (Integer) Points.call(cl, "dipo.screen_mode.get_screen", mode);
                    boolean local = Boolean.TRUE.equals(Points.call(cl, "dipo.service.local_screen_on", svc));
                    if (screen != SCREEN_NONE && !(screen == SCREEN_LOCAL && local)) {
                        screenWaitSince.remove(p.thisObject);
                        return;
                    }
                    long now = android.os.SystemClock.elapsedRealtime();
                    Long since = screenWaitSince.get(p.thisObject);
                    if (since == null) screenWaitSince.put(p.thisObject, since = now);
                    if (now - since > SCREEN_RETRY_FOR_MS) {
                        screenWaitSince.remove(p.thisObject);
                        if (screen == SCREEN_LOCAL) {
                            Object a = Points.get(cl, "dipo.activity_handler.activity", p.thisObject);
                            if (a instanceof Activity) ((Activity) a).finish();
                            p.setResult(null);
                        }
                        return;
                    }
                    if (screen == SCREEN_LOCAL) Points.call(cl, "dipo.service.display_default", svc);
                    ((Handler) p.thisObject).sendEmptyMessageDelayed(MSG_SCREEN, SCREEN_RETRY_MS);
                    p.setResult(null);
                } catch (Throwable t) {
                    mod.error("screen request", t);
                }
            }
        });
        mod.log("CarPlay: key frames, screen re-attach, wireless checks, Siri timing");
    }

    /** CarPlay ends: its library stops and DiPO finalizes; the library is ready again a moment later */
    static void endCarPlay(final ModContext mod, final ClassLoader cl) {
        try {
            final Object svc = service(cl);
            if (svc == null) return;
            final Object player = Points.get(cl, "dipo.service.player", svc);
            if (player != null && Points.get(cl, "dipo.iap2.carplay", player) != null) Points.call(cl, "dipo.iap2.stop_carplay", player);
            Points.call(cl, "dipo.service.dipo_finalized", svc);
            if (player == null || main == null) return;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Points.call(cl, "dipo.iap2.start_carplay", player);
                    } catch (Throwable t) {
                        mod.error("CarPlay library restart", t);
                    }
                }
            }, RESTART_MS);
        } catch (Throwable t) {
            mod.error("ending CarPlay", t);
        }
    }

    /** the iAP2 server: the hotspot's details when it has none, and again when the iPhone is ready */
    static void iap2(final ModContext mod, final LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "iap2.service.send_accessory_config", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                String ssid = (String) p.args[0];
                if (ssid != null && ssid.length() > 0) return;
                try {
                    Object wifi = app.context.getSystemService(Context.WIFI_SERVICE);
                    Object conf = wifi.getClass().getMethod("getWifiApConfiguration").invoke(wifi);
                    String s = conf == null ? null : (String) conf.getClass().getField("SSID").get(conf);
                    String psk = conf == null ? null : (String) conf.getClass().getField("preSharedKey").get(conf);
                    s = unquote(s);
                    if (s == null || s.length() == 0) return;
                    p.args[0] = s;
                    boolean hasPsk = psk != null && psk.length() > 0;
                    p.args[1] = hasPsk;
                    p.args[2] = hasPsk ? unquote(psk) : "";
                    mod.log("iAP2: no hotspot name from the server, sent the hotspot's own");
                } catch (Throwable t) {
                    mod.error("hotspot details", t);
                }
            }
        });
        final Context ctx = app.context;
        mod.hook(cl, "iap2.service.set_wireless_carplay_status", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                try {
                    int status = Rules.wirelessStatus(p.args[0]);
                    if (status == 0 && ctx != null) {
                        // after Enhanced Phone Projection 1.23 (NEMESIS, AGPL-3.0): not available ends the
                        // wireless bridge's attempt in DiPO at once instead of after its idle time
                        ctx.sendBroadcast(new Intent(CARPLAY_UNAVAILABLE).setPackage(Switch.DIPO));
                        mod.log("iAP2: the iPhone says wireless CarPlay isn't available");
                        return;
                    }
                    if (status != 1) return;
                    @SuppressWarnings("unchecked")
                    BlockingQueue<Object> q = (BlockingQueue<Object>) Points.get(cl, "iap2.service.msg_queue", null);
                    if (q == null) return;
                    q.offer(Message.obtain(null, IAP2_SEND_WIFI, 0, 0));
                    mod.log("iAP2: the iPhone can do wireless CarPlay, Wi-Fi details sent again");
                } catch (Throwable t) {
                    mod.error("wireless status", t);
                }
            }
        });
        mod.log("iAP2: hotspot details");
    }

    /** to the wireless mod's bridge in DiPO (Bridge.ACTION_CARPLAY_UNAVAILABLE) */
    static final String CARPLAY_UNAVAILABLE = "hmgapi.wireless.CARPLAY_UNAVAILABLE";

    /** a Wi-Fi SSID or key without the one pair of quotes Android keeps around it */
    static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
