package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.os.HandlerThread;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Method;
import java.util.ArrayList;

import hmgapi.hook.Points;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 *
 * An iPhone pairs over Bluetooth; wireless CarPlay starts with iAP2 over Bluetooth
 * (RFCOMM/SPP), which the iAP2 server here can't do by itself. The bridge carries it:
 * SPP data from Broadcom's Bluetooth manager goes into the iAP2 server as transport data
 * (Iap2SvcTransportReadDone), and what the server sends back (notification 80) goes out
 * over SPP. The iAP2 server opens the session for BT_ATTACHED (HMG-API's hook there, part
 * "bridge"). Once the phone has joined the hotspot and the CarPlay session runs, iAP2
 * moves into the CarPlay session's own channel: bytes the phone sends there
 * (oniAPSendMessage) go to the server, and what the server sends for it
 * (notification 81) goes out through the CarPlay library.
 *
 * HMG-API binds the iAP2 server and hooks Broadcom's manager (a library), and only
 * observes DiPO's CarPlay callbacks; DiPO's own iAP2 listener is told to ignore what
 * belongs to the bridge's sessions (Dipo's hooks).
 *
 * persist.ev6.cpwl.enable=0 turns it off; persist.ev6.cpwl.handover=created moves iAP2
 * into the CarPlay channel as soon as the session is created (default: on its first data).
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    /** iAP2 components: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel */
    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;
    /** Broadcom's connected profiles: HFP, A2DP */
    static final int PROFILE_HFP = 0, PROFILE_A2DP = 1;
    /** after Enhanced Phone Projection 1.11: no CarPlay session this long after the Bluetooth start */
    static final long REJOIN_MS = 8000, IDLE_MS = 40000, HOTSPOT_OFF_MS = 2000;
    /** the car's power (MicomPowerManager): on, and delay mode / pre-off / off */
    static final String ACTION_ACC = "com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED";
    static final String EXTRA_ACC = "android.ibox.mode.EXTRA_ACC_STATUS";
    static final int ACC_ON = 0, ACC_DELAY = 6, ACC_PRE_OFF = 7, ACC_OFF = 0xff;
    static volatile boolean sCarOff;
    /** an iPhone whose CarPlay was ended on request (DISCONNECT_CARPLAY): not started again until it reconnects */
    static volatile String sHeld;
    static final String ACTION_DISCONNECT_CARPLAY = "com.daudio.app.dipo.DISCONNECT_CARPLAY";
    /** the iAP2 server's own broadcast when a session fails authentication (Iap2Authentication), with its component */
    static final String ACTION_IAP2_FAILED = "com.tcc.iap2.connectedfail", EXTRA_IAP2_COMPONENT = "com.telechips.android.iap2.component";
    /**
     * from the iAP2 server (projection-extras on the Creta, where its status hook is mapped): the
     * iPhone says wireless CarPlay isn't available (DeviceWirelessCarPlayUpdate)
     */
    static final String ACTION_CARPLAY_UNAVAILABLE = "hmgapi.wireless.CARPLAY_UNAVAILABLE";
    /**
     * from BtSettings (projection-extras on the Creta, where its points are mapped): the driver
     * connected or disconnected a phone on the car's Bluetooth screen. "what": phone (handsfree
     * only), audio (audio only), connect (both), disconnect; "address" (none for a cancel).
     */
    static final String ACTION_BT_BY_HAND = "hmgapi.wireless.BT_BY_HAND";
    static final String BY_HAND_PHONE = "phone", BY_HAND_AUDIO = "audio";
    /** the manual connect guard: a phone connected by hand for one profile gets no automatic CarPlay start for a while */
    static final HandGuard HAND = new HandGuard();
    static final long AUTH_RETRY_MS = 1000, AA_RECHECK_MS = 30000;
    /** the car's mode for Android Auto (GPM), available while it's connected */
    static final int MODE_ANDROID_AUTO = 0x74;
    /** after Enhanced Phone Projection 1.23: one more start of the Bluetooth session after it failed authentication */
    private static volatile boolean sAuthRetried;
    /** when the last BT_ATTACHED went out (elapsedRealtime; 0 none) */
    private static volatile long sAttachAt;
    /** an iPhone waiting for another phone's Android Auto to end before its CarPlay starts */
    private static volatile String sAaWait;
    private static int sWatchId;
    /** retries of the same phone since it last connected or a session started: a phone that keeps closing SPP isn't hammered */
    private static int sSameTries;
    static final int MAX_SAME_TRIES = 2;

    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    /** the phone's Bluetooth address from its last iAP2 session (DiPO's first-connect notice) */
    static volatile String sLastBtId;
    static ClassLoader cl;
    static Context context;
    private static int sCpFail, sCpSent;
    private static Handler sHandler, sCpTx;
    private static Method sNativeSend;
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    private static BtListener sBt;
    private static TxListener sTx;

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    /** DiPO registered its listener with Broadcom's manager (the manager is connected): ours goes next to it */
    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            String a = connectedIphone(m, null);
            if (a != null) scheduleSppConnect(a);
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    /** the iAP2 server is bound: register for Bluetooth and CarPlay-channel transport data */
    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            sIap2 = s;
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + s.Iap2SvcInit(sTx, info));
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    /** a system property (android.os.SystemProperties isn't in the public SDK) */
    static String prop(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            Object v = BluetoothDevice.class.getMethod("getVendor").invoke(d);
            if (v instanceof Integer) return (Integer) v == APPLE_VENDOR_ID;
        } catch (Throwable t) {
            // no vendor id on this build: by name
        }
        try {
            String n = d.getName();
            return n != null && (n.contains("iPhone") || n.contains("iPad"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * An iPhone connected over Bluetooth, from both of the manager's slots: the given one
     * if it's still there, else one with handsfree and music, then handsfree, then music.
     * (The manager's HFP call returns only the first slot with handsfree, so a second
     * iPhone, or one on music only, was never seen. After Evergreen's multi-phone note.)
     */
    static String connectedIphone(HfDeviceBTCMManager m, String prefer) {
        if (m == null) return null;
        String best = null;
        int bestScore = 0;
        BluetoothDevice[] slots;
        try {
            slots = new BluetoothDevice[] { m.IbtcmGetConnectedDevice1(), m.IbtcmGetConnectedDevice2() };
        } catch (Throwable t) {
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            return d != null && isApple(d) ? d.getAddress() : null;
        }
        for (BluetoothDevice d : slots) {
            if (d == null || !isApple(d)) continue;
            boolean[] p = null;
            try {
                p = m.IbtcmGetConnectedProfilesAll(d);
            } catch (Throwable t) {
                // no profile list: count it as connected
            }
            boolean hfp = p == null || (p.length > PROFILE_HFP && p[PROFILE_HFP]);
            boolean a2dp = p != null && p.length > PROFILE_A2DP && p[PROFILE_A2DP];
            if (!hfp && !a2dp) continue;
            int score = d.getAddress().equals(prefer) ? 4 : hfp && a2dp ? 3 : hfp ? 2 : 1;
            if (score > bestScore) {
                best = d.getAddress();
                bestScore = score;
            }
        }
        return best;
    }

    /** a session ended or its phone's SPP went: start with whichever iPhone is still connected */
    static void rescan(final String prefer) {
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sUsb || sSession || sCpWireless || sCarOff || !enabled()) return;
                    String a = connectedIphone(sBtcm, prefer);
                    if (a == null) return;
                    if (a.equals(prefer) && ++sSameTries > MAX_SAME_TRIES) {
                        log("not retrying " + a + " again until it reconnects");
                        return;
                    }
                    log("connected iPhone " + a + (a.equals(prefer) ? " (the same one)" : " (another one)"));
                    scheduleSppConnect(a);
                } catch (Throwable t) {
                    error("rescan", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    /** CarPlay's Bluetooth start: if no session comes, the phone gets the hotspot again, then the attempt ends */
    static void watch(final String addr) {
        final int id = ++sWatchId;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id != sWatchId || sCp != null || !sSession || sUsb) return;
                log("no CarPlay session " + (REJOIN_MS / 1000) + " s after the Bluetooth start: hotspot off and on");
                rejoinHotspot();
            }
        }, REJOIN_MS);
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id != sWatchId || sCp != null || !sSession || sUsb) return;
                log("no CarPlay session " + (IDLE_MS / 1000) + " s after the Bluetooth start: ending the attempt (Bluetooth stays)");
                sSession = false;
                sendDetach();
            }
        }, IDLE_MS);
    }

    /** the session came (or the attempt is over): the watchdog stands down */
    static void unwatch() {
        sWatchId++;
    }

    /** the hotspot off, then on again, so the phone joins afresh */
    static void rejoinHotspot() {
        final Context c = context;
        if (c == null) return;
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);
            Class<?> k = wifi.getClass();
            Object conf = k.getMethod("getWifiApConfiguration").invoke(wifi);
            k.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class).invoke(wifi, conf, false);
        } catch (Throwable t) {
            error("hotspot off", t);
            return;
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureHotspot(c);
            }
        }, HOTSPOT_OFF_MS);
    }

    /** the car's power: wireless CarPlay ends at delay mode or off, and starts again only once the car is on */
    static void watchPower(Context c) {
        if (c == null) return;
        try {
            Intent last = c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    onPower(i.getIntExtra(EXTRA_ACC, -1));
                }
            }, new IntentFilter(ACTION_ACC));
            if (last != null) onPower(last.getIntExtra(EXTRA_ACC, -1));
        } catch (Throwable t) {
            error("power status", t);
        }
    }

    static void onPower(int acc) {
        if (acc == ACC_DELAY || acc == ACC_PRE_OFF || acc == ACC_OFF) {
            if (sCarOff) return;
            sCarOff = true;
            log("car off (" + acc + "): wireless CarPlay ends, no new start until the car is on");
            unwatch();
            final String addr = sAddr;
            if (sSession) {
                sSession = false;
                sendDetach();
            }
            final HfDeviceBTCMManager m = sBtcm;
            if (addr != null && m != null) {
                handler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            log("IbtcmSppDisconnect(" + addr + ") = " + m.IbtcmSppDisconnect(addr));
                        } catch (Throwable t) {
                            error("sppDisconnect", t);
                        }
                    }
                });
            }
        } else if (acc == ACC_ON && sCarOff) {
            sCarOff = false;
            log("car on: wireless CarPlay can start again");
            rescan(sAddr);
        }
    }

    /** CarPlay was ended on request (the projection switch): that iPhone waits for its next Bluetooth connect */
    static void watchEndRequests(Context c) {
        if (c == null) return;
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    String addr = sAddr;
                    if (addr == null) return;
                    sHeld = addr;
                    unwatch();
                    log("CarPlay ended on request: " + addr + " waits for its next Bluetooth connect");
                }
            }, new IntentFilter(ACTION_DISCONNECT_CARPLAY));
        } catch (Throwable t) {
            error("end requests", t);
        }
    }

    static void scheduleSppConnect(final String addr) {
        if (addr == null || addr.equals(sHeld) || !enabled() || sUsb || sSession || sCpWireless || sCarOff) return;
        if (heldByHand(addr)) return;
        if (androidAutoUp()) {
            waitForAndroidAuto(addr);
            return;
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb || sSession || sCpWireless || sCarOff || !enabled()) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) log("SPP already up for " + addr);
                    else log("IbtcmSppConnect(" + addr + ") = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("sppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onSppUp(final String addr) {
        if (addr == null || sSession) return;
        sAddr = addr;
        if (sCpClient || sCpWireless || !enabled() || sUsb || sCarOff || addr.equals(sHeld)) return;
        if (heldByHand(addr)) return;
        if (androidAutoUp()) {
            waitForAndroidAuto(addr);
            return;
        }
        sSession = true;
        sCp = null;
        sAuthRetried = false;
        watch(addr);
        sendAttach(addr);
    }

    /** the iAP2 server's Bluetooth session for this iPhone (HMG-API's part there opens it) */
    static void sendAttach(final String addr) {
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) {
                        log("no context, cannot start session");
                        sSession = false;
                        return;
                    }
                    ensureHotspot(c);
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    sAttachAt = android.os.SystemClock.elapsedRealtime();
                    log("BT_ATTACHED sent for " + addr);
                } catch (Throwable t) {
                    sSession = false;
                    error("sendAttach", t);
                }
            }
        });
    }

    /**
     * Another phone's Android Auto is connected (the car's Android Auto mode is available).
     * After Enhanced Phone Projection 1.23: CarPlay doesn't start by itself next to it (with
     * two phones in the car every start would put up the car's "which one to keep" question);
     * the projection button still switches.
     */
    static boolean androidAutoUp() {
        Context c = context;
        if (c == null) return false;
        try {
            Object mm = Class.forName("com.hkmc.mode.ModeManager").getConstructor(Context.class).newInstance(c);
            return hasMode((int[]) mm.getClass().getMethod("getAvailableMediaModes").invoke(mm), MODE_ANDROID_AUTO);
        } catch (Throwable t) {
            return false;   // can't tell: CarPlay isn't held back
        }
    }

    static boolean hasMode(int[] modes, int mode) {
        if (modes != null) for (int m : modes) if (m == mode) return true;
        return false;
    }

    /** the iPhone's CarPlay starts once Android Auto is gone (looked at every AA_RECHECK_MS while it stays) */
    static void waitForAndroidAuto(final String addr) {
        if (addr.equals(sAaWait)) return;
        sAaWait = addr;
        log("another phone's Android Auto is up: CarPlay for " + addr + " waits for it to end");
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!addr.equals(sAaWait)) return;
                if (sUsb || sSession || sCpWireless || sCarOff || !enabled() || addr.equals(sHeld)) {
                    sAaWait = null;
                    return;
                }
                if (androidAutoUp()) {
                    handler().postDelayed(this, AA_RECHECK_MS);
                    return;
                }
                sAaWait = null;
                String a = connectedIphone(sBtcm, addr);
                if (a == null) return;
                log("Android Auto ended: CarPlay for " + a);
                scheduleSppConnect(a);
            }
        }, AA_RECHECK_MS);
    }

    /**
     * After Enhanced Phone Projection 1.23 (NEMESIS, AGPL-3.0): a Bluetooth session that fails
     * authentication (the iAP2 server's com.tcc.iap2.connectedfail for the Bluetooth component)
     * is started once more a second later; a second failure ends it as before.
     */
    static void watchAuthFailures(Context c) {
        if (c == null) return;
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    onAuthFailed(i.getIntExtra(EXTRA_IAP2_COMPONENT, -1));
                }
            }, new IntentFilter(ACTION_IAP2_FAILED));
        } catch (Throwable t) {
            error("authentication failures", t);
        }
    }

    /**
     * The server may report the Bluetooth component disconnected before or after this
     * broadcast, so the session flag isn't relied on: the failure counts while the phone is
     * still on SPP (sAddr), no CarPlay session came, and our BT_ATTACHED went out within IDLE_MS.
     */
    static void onAuthFailed(int component) {
        final String addr = sAddr;
        if (!authRetryWanted(component, addr != null, sCp != null || sCpWireless, sUsb || sCarOff,
                android.os.SystemClock.elapsedRealtime() - sAttachAt, sAttachAt > 0)) return;
        if (sAuthRetried) {
            log("iAP2 authentication failed again for " + addr + ": the attempt ends");
            return;
        }
        sAuthRetried = true;
        log("iAP2 authentication failed for " + addr + ": starting the Bluetooth session once more");
        unwatch();
        sSession = false;
        sendDetach();
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sSession || sCp != null || sCpWireless || sUsb || sCarOff || !enabled() || !addr.equals(sAddr)) return;
                sSession = true;
                watch(addr);
                sendAttach(addr);
            }
        }, AUTH_RETRY_MS);
    }

    /** a failed authentication is ours to retry: the Bluetooth component, the phone on SPP, no CarPlay, our attach recent */
    static boolean authRetryWanted(int component, boolean onSpp, boolean carPlay, boolean blocked, long sinceAttach, boolean attached) {
        return component == COMPONENT_BT_CARPLAY && onSpp && !carPlay && !blocked && attached && sinceAttach >= 0 && sinceAttach < IDLE_MS;
    }

    /**
     * After Enhanced Phone Projection 1.23 (NEMESIS, AGPL-3.0): the iPhone says wireless CarPlay
     * isn't available (its DeviceWirelessCarPlayUpdate, passed on from the iAP2 server by
     * projection-extras on cars whose map has that hook): with no CarPlay session yet, the
     * attempt ends now instead of after IDLE_MS. Bluetooth stays.
     */
    static void watchUnavailable(Context c) {
        if (c == null) return;
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    onUnavailable();
                }
            }, new IntentFilter(ACTION_CARPLAY_UNAVAILABLE));
        } catch (Throwable t) {
            error("wireless CarPlay status", t);
        }
    }

    static void onUnavailable() {
        if (!sSession || sCp != null || sCpWireless || sUsb) return;
        log("the iPhone says wireless CarPlay isn't available: ending the attempt now (Bluetooth stays)");
        unwatch();
        sSession = false;
        sAttachAt = 0;   // its session's end isn't an authentication failure to retry
        sendDetach();
    }

    /** the driver's own Bluetooth actions on the car's screen: a one-profile connect starts the manual connect guard */
    static void watchHandConnects(Context c) {
        if (c == null) return;
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context x, Intent i) {
                    String what = i.getStringExtra("what"), addr = i.getStringExtra("address");
                    if (!BY_HAND_PHONE.equals(what) && !BY_HAND_AUDIO.equals(what)) return;
                    HAND.connectedByHand(addr, android.os.SystemClock.elapsedRealtime());
                    log("manual connect guard: " + HandGuard.mask(addr) + " connected by hand (" + what
                            + " only), no automatic CarPlay start for it for " + (HandGuard.WINDOW_MS / 1000) + " s");
                }
            }, new IntentFilter(ACTION_BT_BY_HAND));
        } catch (Throwable t) {
            error("manual connects", t);
        }
    }

    /** an automatic CarPlay start for this phone is held back by the manual connect guard (logged once) */
    static boolean heldByHand(String addr) {
        long now = android.os.SystemClock.elapsedRealtime();
        int c = HAND.check(addr, now);
        if (c == HandGuard.HELD) {
            log("manual connect guard: CarPlay not started for " + HandGuard.mask(addr) + " (connected by hand, phone or audio only; "
                    + HAND.secondsLeft(now) + " s left), the next Bluetooth start after that goes ahead");
        }
        return c != HandGuard.FREE;
    }

    static void onUsbGone() {
        final String addr = sAddr;
        if (addr == null || sSession) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);
                    else rescan(addr);
                } catch (Throwable t) {
                    error("onUsbGone", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onCpSessionCreated(Object cp) {
        if (cp != null) sCp = cp;
        unwatch();
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + sAddr + ")");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(int transport) {
        sCpWireless = transport == 1 && !sUsb;
        sSameTries = 0;
        sAaWait = null;
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
    }

    /** iAP2 bytes from the phone over the CarPlay channel: to the iAP2 server (buffered until its session is up) */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (cp != null) sCp = cp;
            if (data == null || len <= 0 || sUsb) return;
            if (len >= data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            if (!sCpClient) startCpClient("iAP2 data from CarPlay channel");
            IIap2Service s = sIap2;
            if (sCpConnected && s != null) {
                flushPending(s);
                s.Iap2SvcTransportReadDone(b, len);
            } else {
                synchronized (sPendingIn) {
                    if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                }
            }
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    static void onCpSessionFinalized(int type) {
        try {
            log("CarPlay session finalized type=" + type + " cpClient=" + sCpClient);
            boolean client = sCpClient, wireless = sCpWireless;
            sCpWireless = false;
            sCpClient = false;
            sCpConnected = false;
            synchronized (sPendingIn) {
                sPendingIn.clear();
            }
            if (client) sendDetach();
            if (client || wireless) onUsbGone();
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    /** DiPO's iAP2 listener: ignore the Bluetooth and CarPlay-channel sessions ending while wireless CarPlay is up */
    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    /** DiPO's iAP2 listener: the CarPlay-channel session's identification isn't a new phone */
    static boolean dipoDropNoti(int what) {
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static void startCpClient(final String why) {
        if (sCpClient) return;
        sCpClient = true;
        sCpConnected = false;
        final String addr = sAddr;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static void onCpClientConnected() {
        sCpConnected = true;
        IIap2Service s = sIap2;
        if (s != null) flushPending(s);
    }

    private static void flushPending(IIap2Service s) {
        ArrayList<byte[]> all;
        synchronized (sPendingIn) {
            if (sPendingIn.isEmpty()) return;
            all = new ArrayList<byte[]>(sPendingIn);
            sPendingIn.clear();
        }
        for (byte[] b : all) {
            try {
                s.Iap2SvcTransportReadDone(b, b.length);
            } catch (Throwable t) {
                error("flushPending", t);
            }
        }
        log("flushed " + all.size() + " buffered CarPlay-channel packets");
    }

    private static synchronized Handler cpTx() {
        if (sCpTx == null) {
            HandlerThread t = new HandlerThread("hmgapi-cp-tx");
            t.start();
            sCpTx = new Handler(t.getLooper());
        }
        return sCpTx;
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    /** iAP2 bytes from the server for the CarPlay channel: out through the CarPlay library */
    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        final byte[] b = data.clone();
        cpTx().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object r = nativeSend().invoke(cp, b, b.length);
                    int rc = r instanceof Integer ? (Integer) r : -1;
                    if (rc == 0) {
                        sCpSent++;
                        if (sCpSent <= 3 || sCpSent % 200 == 0) log("CarPlay-channel iAP2 sent " + b.length + " bytes (#" + sCpSent + ")");
                    } else {
                        sCpFail++;
                        if (sCpFail <= 5 || sCpFail % 50 == 0) log("CarPlay-channel iAP2 send failed rc=" + rc + " len=" + b.length);
                    }
                } catch (Throwable t) {
                    error("writeCp native", t);
                }
            }
        });
    }

    static void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        if (sCpWireless) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        sAddr = null;
        unwatch();
        if (sSession) {
            sSession = false;
            sendDetach();
        }
        rescan(addr);
    }

    static void sendDetach() {
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    /** SPP data from the phone: to the iAP2 server */
    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len >= data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    /** iAP2 bytes from the server for the Bluetooth session: out over SPP */
    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    /** the head unit's hotspot in mode 2 (for connectivity), which wireless CarPlay joins */
    static void ensureHotspot(Context c) {
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            Class<?> k = wifi.getClass();
            boolean on = false;
            int mode;
            try {
                on = (Boolean) k.getMethod("isWifiApEnabled").invoke(wifi);
            } catch (Throwable t) {
                // no such call: treat as off
            }
            try {
                mode = (Integer) k.getMethod("getWifiApMode").invoke(wifi);
            } catch (Throwable t) {
                mode = -1;
            }
            if (on && mode == 2) {
                log("hotspot already up (mode 2)");
                return;
            }
            Object conf = k.getMethod("getWifiApConfiguration").invoke(wifi);
            Object r = k.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class).invoke(wifi, conf, true);
            log("setWifiApEnabledForConnectivity -> " + r + " (was on=" + on + " mode=" + mode + ")");
        } catch (Throwable t) {
            error("ensureHotspot", t);
        }
    }

    /** Broadcom's status callbacks: an iPhone connects (start SPP), SPP up/down, SPP data */
    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (isApple(d)) {
                    sSameTries = 0;
                    if (d.getAddress().equals(sHeld)) sHeld = null;
                    log("iPhone BT connected: " + d.getAddress());
                    scheduleSppConnect(d.getAddress());
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                if (!isApple(d)) return;
                String addr = d.getAddress();
                log("SPP status " + status + " for " + addr);
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    /** the iAP2 server's side: USB and CarPlay-channel sessions, and transport data to send */
    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            // what DiPO keeps as the current device (setCurrentIapDeviceName): a Bluetooth address is the notice's key
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) sUsb = true;
            else if (component == COMPONENT_CARPLAY_CLIENT) onCpClientConnected();
        }

        @Override
        public void disconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                sSession = false;
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                sCpConnected = false;
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
