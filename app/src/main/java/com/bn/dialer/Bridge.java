package com.bn.dialer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The "NativeBridge" the Bn web page talks to, plus helpers to push events into the page. */
public class Bridge {
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile WebView web;
    static volatile boolean ready = false;

    private final Context ctx;

    Bridge(Context c) {
        ctx = c.getApplicationContext();
    }

    static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /** Run JavaScript in the page now. Dropped if the page is not loaded yet. */
    static void js(final String script) {
        MAIN.post(() -> {
            WebView w = web;
            if (ready && w != null) w.evaluateJavascript(script, null);
        });
    }

    /** Payment texts must never be lost: run now, or keep them until the page is loaded. */
    static void sms(final Context c, final String script) {
        final Context app = c.getApplicationContext();
        MAIN.post(() -> {
            WebView w = web;
            if (ready && w != null) w.evaluateJavascript(script, null);
            else store(app, script);
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bn_queue", Context.MODE_PRIVATE);
    }

    private static synchronized void store(Context c, String script) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray a = new JSONArray(p.getString("q", "[]"));
            a.put(script);
            p.edit().putString("q", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Called when the page has loaded: replay anything that arrived while the app was closed. */
    static synchronized void flush(Context c) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray a = new JSONArray(p.getString("q", "[]"));
            p.edit().remove("q").apply();
            for (int i = 0; i < a.length(); i++) {
                final String s = a.getString(i);
                MAIN.postDelayed(() -> {
                    WebView w = web;
                    if (w != null) w.evaluateJavascript(s, null);
                }, 400L + i * 150L);
            }
        } catch (Exception ignored) {
        }
    }

    static void toast(String msg) {
        js("if(window.toast)toast(" + q(msg) + ");if(window.addLog)addLog(" + q(msg) + ")");
    }

    /* ---------------- methods the web page calls ---------------- */

    @JavascriptInterface
    public void dialUssd(final String code, final int simSlot) {
        MAIN.post(() -> {
            try {
                Wake.cancelLock();
                UssdService s = UssdService.inst;
                if (s == null) {
                    toast("Turn on Bn in Accessibility settings, or replies cannot be sent");
                } else {
                    s.resetText();
                }
                Uri uri = Uri.parse("tel:" + Uri.encode(code));
                TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                Bundle extras = new Bundle();
                if (simSlot >= 0) {
                    PhoneAccountHandle h = handleFor(simSlot, tm);
                    if (h != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
                }
                tm.placeCall(uri, extras);
            } catch (Exception e) {
                toast("Could not dial: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void replyUssd(final String text) {
        MAIN.post(() -> {
            UssdService s = UssdService.inst;
            if (s == null) toast("Turn on Bn in Accessibility settings to send USSD replies");
            else s.reply(text);
        });
    }

    @JavascriptInterface
    public void endUssd() {
        MAIN.post(() -> {
            UssdService s = UssdService.inst;
            if (s != null) s.dismiss();
        });
    }

    @JavascriptInterface
    public String a11yState() {
        return "{\"perm\":" + A11y.canWrite(ctx) + ",\"on\":" + (UssdService.inst != null)
                + ",\"listed\":" + A11y.listed(ctx) + "}";
    }

    @JavascriptInterface
    public void a11yRestartNow() {
        MAIN.post(() -> {
            if (!A11y.canWrite(ctx)) {
                toast("Auto-restart needs the one-time setup (see Settings > USSD helper)");
            } else {
                toast("Restarting USSD helper...");
                A11y.restart(ctx, () -> toast("USSD helper restarted"));
            }
        });
    }

    /** Like dialUssd, but first wakes and unlocks the phone if the screen is off (used for advanced offers). */
    @JavascriptInterface
    public void dialUssdWake(final String code, final int simSlot, final boolean wake) {
        if (!wake) {
            dialUssd(code, simSlot);
            return;
        }
        MAIN.post(() -> Wake.begin(ctx, () -> dialUssd(code, simSlot)));
    }

    @JavascriptInterface
    public void setUnlockPin(String pin) {
        if (pin == null || pin.trim().isEmpty() || !Vault.put(ctx, pin.trim())) toast("Could not save the PIN");
    }

    @JavascriptInterface
    public String hasUnlockPin() {
        return Vault.has(ctx) ? "true" : "false";
    }

    @JavascriptInterface
    public void clearUnlockPin() {
        Vault.clear(ctx);
    }

    /** Locks the phone, then 8 seconds later wakes it, unlocks it and locks it again. */
    @JavascriptInterface
    public void testWake() {
        MAIN.post(() -> {
            UssdService s = UssdService.inst;
            if (s == null) {
                toast("Turn on the Bn USSD helper first");
                return;
            }
            s.lockNow();
            MAIN.postDelayed(() -> Wake.begin(ctx, () -> {
                toast("Test: unlocked OK, locking again");
                Wake.scheduleLock();
            }), 8000);
        });
    }

    /** Texts a customer. simSlot: 0 = SIM 1, 1 = SIM 2, -1 = phone default. Returns "ok" or a short error code. */
    @JavascriptInterface
    public String sendSms(final String to, final String text, final int simSlot) {
        try {
            if (to == null || to.trim().isEmpty() || text == null || text.trim().isEmpty()) return "empty";
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return "no_permission";
            }
            SmsManager sm = smsManagerFor(simSlot);
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) sm.sendMultipartTextMessage(to.trim(), null, parts, null, null);
            else sm.sendTextMessage(to.trim(), null, text, null, null);
            return "ok";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private SmsManager smsManagerFor(int slot) {
        try {
            if (slot >= 0) {
                SubscriptionManager sub = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                SubscriptionInfo si = sub.getActiveSubscriptionInfoForSimSlotIndex(slot);
                if (si != null) return SmsManager.getSmsManagerForSubscriptionId(si.getSubscriptionId());
            }
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager d = ctx.getSystemService(SmsManager.class);
            if (d != null) return d;
        }
        return SmsManager.getDefault();
    }

    /* ---------------- SIM selection ---------------- */

    private PhoneAccountHandle handleFor(int slot, TelecomManager tm) {
        try {
            SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            TelephonyManager tel = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            SubscriptionInfo si = sm.getActiveSubscriptionInfoForSimSlotIndex(slot);
            List<PhoneAccountHandle> hs = tm.getCallCapablePhoneAccounts();
            if (si != null) {
                for (PhoneAccountHandle h : hs) {
                    if (Build.VERSION.SDK_INT >= 29 && tel.getSubscriptionId(h) == si.getSubscriptionId()) return h;
                    String id = h.getId();
                    if (id != null && (id.equals(String.valueOf(si.getSubscriptionId()))
                            || id.equals(si.getIccId()))) return h;
                }
            }
            if (slot < hs.size()) return hs.get(slot);
        } catch (Exception ignored) {
        }
        return null;
    }
}
