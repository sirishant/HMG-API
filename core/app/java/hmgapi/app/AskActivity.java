package hmgapi.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONObject;

/**
 * The owner's OK for a mod's first use of a car command: the vehicle service opens
 * this (am start, extras mod and command) and refuses the command until it's answered.
 * Allow / Don't allow is kept per mod and command (hmgapid allow cmd.<mod>.<command>,
 * nocmd.<mod>.<command>); Settings, Vehicle forgets the answers.
 */
public class AskActivity extends Activity {
    static final String[][] WHAT = {
        { "charge_door", "open and close the charge door" },
        { "seat_heat", "turn seat heating up or down" },
        { "seat_vent", "turn seat ventilation up or down" },
        { "wheel_heat", "turn the steering wheel heat on" },
        { "conditioning", "start battery conditioning" },
        { "ambient_light", "change the ambient light" },
        { "guide_lines", "turn the parking guide lines on or off" },
        { "rear_camera", "show the rear camera" },
        { "startup_notice", "dismiss the startup notice" },
        { "climate", "press Sync, Driver Only and the driver's temperature on the climate" },
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String mod = getIntent().getStringExtra("mod"), cmd = getIntent().getStringExtra("command");
        if (mod == null || cmd == null || !mod.matches("[a-z0-9][a-z0-9._-]*") || !cmd.matches("[a-z0-9_]+")) {
            finish();
            return;
        }
        String what = "use " + cmd;
        for (String[] w : WHAT) if (w[0].equals(cmd)) what = w[1];
        LinearLayout list = Ui.page(this);
        final android.widget.TextView q = Ui.text(list, "Allow " + mod + " to " + what + "?", 26, true, Ui.TEXT);
        Ui.text(list, "A mod asked for this car command for the first time. Your answer is kept for this mod and command; "
                + "you can change it in HMG-API: Settings, Vehicle.", 17, false, Ui.GREY);
        final String fwhat = what;
        new Ui.Job<String>() {
            @Override
            String work() throws Exception {
                return new JSONObject(Hmgapid.call("mod-file", mod, "mod.json").output).optString("name", mod);
            }

            @Override
            void done(String name, Exception e) {
                if (e == null) q.setText("Allow " + name + " to " + fwhat + "?");
            }
        }.start(this);
        LinearLayout buttons = Ui.row(this);
        Ui.button(buttons, "Allow", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(mod, cmd, true);
            }
        });
        Ui.button(buttons, "Don't allow", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(mod, cmd, false);
            }
        });
        list.addView(buttons);
    }

    void answer(final String mod, final String cmd, final boolean yes) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                Hmgapid.call("allow", (yes ? "nocmd." : "cmd.") + mod + "." + cmd, "off");
                return Hmgapid.call("allow", (yes ? "cmd." : "nocmd.") + mod + "." + cmd, "on");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                finish();
            }
        }.start(this);
    }
}
