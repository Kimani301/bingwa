package com.bn.dialer;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;

/**
 * Screen-off handling for advanced offers: wake the screen, unlock it with the saved PIN,
 * let the codes run, then lock the phone again. All calls happen on the main thread.
 */
final class Wake {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final long LOCK_DELAY_MS = 6000;

    static volatile boolean woke = false;   // true when Bn woke/unlocked the phone and still owes a lock
    private static PowerManager.WakeLock wl;
    private static Runnable lockTask;
    private static int tries;

    private Wake() {
    }

    static void cancelLock() {
        if (lockTask != null) {
            H.removeCallbacks(lockTask);
            lockTask = null;
        }
    }

    /** Runs {@code proceed} once the phone is awake and unlocked (or straight away if it already is). */
    @SuppressWarnings("deprecation")
    static void begin(Context c, final Runnable proceed) {
        cancelLock();
        final Context app = c.getApplicationContext();
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        boolean asleep = !pm.isInteractive() || km.isKeyguardLocked();
        if (!asleep) {
            proceed.run();
            return;
        }
        String pin = Vault.get(app);
        UssdService s = UssdService.inst;
        if (pin == null || s == null) {
            Bridge.toast(pin == null
                    ? "Screen is off: save your phone PIN in Settings > Screen off so Bn can unlock it"
                    : "Screen is off: turn on the Bn USSD helper so Bn can unlock the phone");
            proceed.run();
            return;
        }
        woke = true;
        try {
            if (wl != null && wl.isHeld()) wl.release();
            wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "bn:wake");
            wl.acquire(3 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        Bridge.toast("Waking the phone to run the codes");
        s.unlock(pin, ok -> {
            if (!ok) Bridge.toast("Could not unlock the phone (check the saved PIN)");
            H.postDelayed(proceed, 800);
        });
    }

    /** Called when a USSD session is over: lock the phone a few seconds later, unless another dial starts. */
    static void scheduleLock() {
        if (!woke) return;
        cancelLock();
        tries = 0;
        lockTask = new Runnable() {
            @Override
            public void run() {
                WebView w = Bridge.web;
                if (w != null && Bridge.ready && tries++ < 10) {
                    try {
                        w.evaluateJavascript("(typeof busy==='function'&&busy())?'1':'0'", v -> {
                            if (v != null && v.contains("1")) H.postDelayed(this, 4000);
                            else lockNow();
                        });
                        return;
                    } catch (Exception ignored) {
                    }
                }
                lockNow();
            }
        };
        H.postDelayed(lockTask, LOCK_DELAY_MS);
    }

    private static void lockNow() {
        lockTask = null;
        UssdService s = UssdService.inst;
        if (s != null) s.lockNow();
        woke = false;
        H.postDelayed(() -> {
            if (wl != null && wl.isHeld()) wl.release();
        }, 1500);
    }
}
