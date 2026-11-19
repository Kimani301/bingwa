package com.bn.dialer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebView;

/**
 * Keeps Bn alive in the background and looks after the accessibility service:
 *  - every 20 minutes it switches the service off and on again (waiting until no sale is being dialed);
 *  - every 2 minutes it switches it back on if it was turned off, or restarts it if it is on but dead.
 */
public class BnService extends Service {
    private static final long CYCLE_MS = 20L * 60L * 1000L;
    private static final long HEAL_MS = 2L * 60L * 1000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wl;
    private int strikes = 0;

    private final Runnable cycle = () -> attempt(0);

    private final Runnable heal = new Runnable() {
        @Override
        public void run() {
            try {
                if (A11y.canWrite(BnService.this)) {
                    if (!A11y.listed(BnService.this)) {
                        A11y.enable(BnService.this);
                        strikes = 0;
                    } else if (UssdService.inst == null) {
                        if (++strikes >= 2) {
                            strikes = 0;
                            A11y.restart(BnService.this, null);
                        }
                    } else {
                        strikes = 0;
                    }
                }
            } catch (Exception ignored) {
            }
            h.postDelayed(this, HEAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        h.postDelayed(heal, 5000);
        h.postDelayed(cycle, CYCLE_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bn", "Bn running", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "bn")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Bn is running")
                .setContentText("Watching for payments and dialing bundles")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bn:running");
            wl.acquire();
        }
        return START_STICKY;
    }

    /** Wait (up to about 5 minutes) for any sale in progress to finish, then restart the helper. */
    private void attempt(final int tries) {
        final WebView w = Bridge.web;
        if (w == null || !Bridge.ready || tries >= 15) {
            restartNow();
            return;
        }
        try {
            w.evaluateJavascript("(typeof busy==='function'&&busy())?'1':'0'", v -> {
                if (v != null && v.contains("1")) h.postDelayed(() -> attempt(tries + 1), 20000);
                else restartNow();
            });
        } catch (Exception e) {
            restartNow();
        }
    }

    private void restartNow() {
        if (A11y.canWrite(this)) {
            A11y.restart(this, () -> h.postDelayed(cycle, CYCLE_MS));
        } else {
            if (!A11y.listed(this) || UssdService.inst == null) alert();
            h.postDelayed(cycle, CYCLE_MS);
        }
    }

    /** Without the one-time setup Bn cannot restart the helper itself, so it asks you to. */
    private void alert() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("bn_alert", "Bn needs attention", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(this, 1, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "bn_alert")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Bn helper is off")
                    .setContentText("Tap to turn Bn on in Accessibility settings")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
