package com.bn.dialer;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Switches Bn's accessibility service off and on.
 * Android only allows this for apps that hold WRITE_SECURE_SETTINGS, which is granted once with:
 *   adb shell pm grant com.bn.dialer android.permission.WRITE_SECURE_SETTINGS
 */
final class A11y {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private A11y() {
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String raw(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s == null ? "" : s;
    }

    /** True when Bn's service is switched on in Accessibility settings. */
    static boolean listed(Context c) {
        for (String e : raw(c).split(":")) {
            if (e.startsWith(c.getPackageName() + "/")) return true;
        }
        return false;
    }

    /** The enabled-services list with Bn's entry removed (other apps' entries are kept). */
    private static String others(Context c) {
        StringBuilder b = new StringBuilder();
        for (String e : raw(c).split(":")) {
            if (e.isEmpty() || e.startsWith(c.getPackageName() + "/")) continue;
            if (b.length() > 0) b.append(':');
            b.append(e);
        }
        return b.toString();
    }

    static boolean enable(Context c) {
        try {
            ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, UssdService.class).flattenToString();
            String base = others(c);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    base.isEmpty() ? me : base + ":" + me);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean disable(Context c) {
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others(c));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Off, short pause, on. Works whether the service was on or off to begin with. */
    static boolean restart(final Context c, final Runnable done) {
        if (!canWrite(c)) return false;
        final Context app = c.getApplicationContext();
        disable(app);
        MAIN.postDelayed(() -> {
            enable(app);
            if (done != null) done.run();
        }, 2500);
        return true;
    }
}
