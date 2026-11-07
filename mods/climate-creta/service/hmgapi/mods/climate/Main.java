package hmgapi.mods.climate;

import android.util.Log;

import hmgapi.client.HmgApi;
import hmgapi.client.HmgApi.HmgApiException;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * The climate service: reads the car through HMG-API (ignition, the passenger's belt, the
 * climate's status and set temperatures) about seven times a second while the car is on,
 * once a second while it's off, and sends what Policy asks for with HMG-API's climate
 * command (the climate screen's own presses, guarded and logged by HMG-API). Each action is
 * logged under HMG-API's log tag, so drive logs have it.
 *
 * The drive's stage (Driver Only set, or done) is kept in the service's folder with the
 * head unit's boot id: a restart of the service in the same boot carries on where it was.
 */
public final class Main {
    static final String TAG = "HMG-API";
    static final String[] READ = { "ignition", "belt.passenger", "climate.on", "climate.sync", "climate.driver_only",
        "climate.driver_temp", "climate.passenger_temp" };
    static final long ON_MS = 150, OFF_MS = 1000, AWAY_MS = 2000;

    private Main() {}

    public static void main(String[] args) throws Exception {
        final HmgApi api = HmgApi.connect().timeout(3000);
        Policy p = new Policy(new Policy.Log() {
            @Override
            public void line(String text) {
                say(text);
            }
        });
        File data = new File(System.getenv("HMGAPI_DATA") == null ? "/data/local/tmp" : System.getenv("HMGAPI_DATA"));
        File stage = new File(data, "stage");
        String boot = bootId();
        p.stage = loadStage(stage, boot);
        say("Climate: started (this drive's stage " + p.stage + ")");
        String seen = null, failed = null;
        while (true) {
            long sleep = OFF_MS;
            try {
                Map<String, String> v = api.vehicle(READ);
                // what it reads, in the drive log whenever any of it changes ("-": HMG-API can't tell)
                StringBuilder s = new StringBuilder("Climate: reads");
                for (String r : READ) s.append(' ').append(r).append('=').append(v.get(r) == null ? "-" : v.get(r));
                if (!s.toString().equals(seen)) {
                    seen = s.toString();
                    say(seen);
                }
                failed = null;
                Policy.Car c = new Policy.Car();
                c.ignition = num(v.get("ignition"));
                c.belt = num(v.get("belt.passenger"));
                c.on = num(v.get("climate.on"));
                c.sync = num(v.get("climate.sync"));
                c.driverOnly = num(v.get("climate.driver_only"));
                c.driver = dec(v.get("climate.driver_temp"));
                c.passenger = dec(v.get("climate.passenger_temp"));
                long now = android.os.SystemClock.elapsedRealtime();
                String press = p.next(c, now);
                if (press != null) {
                    String[] w = press.split(" ");
                    try {
                        p.sent(press, true, api.command("climate", w[0], w[1]), now);
                    } catch (HmgApiException e) {
                        p.sent(press, false, String.valueOf(e.getMessage()), now);
                    }
                }
                if (p.stageChanged) {
                    p.stageChanged = false;
                    saveStage(stage, boot, p.stage);
                }
                if (c.ignition == 1) sleep = ON_MS;
            } catch (Exception e) {
                sleep = AWAY_MS;   // HMG-API restarting: try again
                String why = String.valueOf(e);
                if (!why.equals(failed)) say("Climate: can't read the car (" + why + "); trying again");
                failed = why;
            }
            Thread.sleep(sleep);
        }
    }

    static void say(String text) {
        System.out.println(text);
        Log.i(TAG, "[climate-creta] " + text);
    }

    static int num(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static float dec(String s) {
        try {
            return s == null ? Float.NaN : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    static String bootId() {
        try {
            return read(new File("/proc/sys/kernel/random/boot_id"));
        } catch (Exception e) {
            return "";
        }
    }

    static String read(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } finally {
            in.close();
        }
    }

    /** the stage saved in this boot, or a new drive */
    static int loadStage(File f, String boot) {
        try {
            String[] w = read(f).split(" ");
            if (w.length == 2 && !boot.isEmpty() && w[0].equals(boot)) return Integer.parseInt(w[1]);
        } catch (Exception e) {
            // none yet
        }
        return Policy.NEW;
    }

    static void saveStage(File f, String boot, int stage) {
        try {
            File tmp = new File(f.getPath() + ".new");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write((boot + " " + stage + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (!tmp.renameTo(f)) say("Climate: couldn't keep this drive's stage");
        } catch (Exception e) {
            say("Climate: couldn't keep this drive's stage: " + e);
        }
    }
}
