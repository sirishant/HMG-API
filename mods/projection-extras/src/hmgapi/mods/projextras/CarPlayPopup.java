package hmgapi.mods.projextras;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.List;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;

/**
 * CarPlay stays behind PhoneProjection's popup (after Enhanced Phone Projection 1.23,
 * NEMESIS, AGPL-3.0): when DiPO's play-start command (onPlayStartCommand) would open the
 * CarPlay screen while PhoneProjection's WirelessAlertDialogActivity is on top (the
 * switch question, a connection message), the screen isn't opened over it. CarPlay itself
 * starts as usual; the owner opens its screen from the popup's answer or the home screen.
 * Only that one start in DiPO's process is held back: every other screen start goes ahead.
 */
final class CarPlayPopup {
    private static final ThreadLocal<Boolean> sPlayStart = new ThreadLocal<Boolean>();

    private CarPlayPopup() {}

    static void dipo(final ModContext mod, final LoadedApp app) {
        mod.hook(app.classLoader, "dipo.service.play_start", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                sPlayStart.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                sPlayStart.remove();
            }
        });
        mod.hook(null, "framework.context_wrapper.start_activity", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (sPlayStart.get() == null) return;
                Intent i = (Intent) p.args[0];
                ComponentName cn = i == null ? null : i.getComponent();
                String top = topActivity(app.context);
                if (!Rules.behindPopup(cn == null ? null : cn.getClassName(), top)) return;
                p.setResult(null);
                mod.log("CarPlay: PhoneProjection's popup is open, the CarPlay screen stays behind it");
            }
        });
        mod.log("CarPlay: its screen stays behind PhoneProjection's popup");
    }

    /** the activity on top, as DiPO's own helper reads it (null if not readable) */
    @SuppressWarnings("deprecation")
    static String topActivity(Context c) {
        if (c == null) return null;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> t = am == null ? null : am.getRunningTasks(1);
            if (t == null || t.isEmpty() || t.get(0).topActivity == null) return null;
            return t.get(0).topActivity.getClassName();
        } catch (Throwable e) {
            return null;
        }
    }
}
