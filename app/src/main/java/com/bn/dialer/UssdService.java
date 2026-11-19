package com.bn.dialer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the phone's USSD pop-up, passes its text to the Bn page, and types/sends the next reply.
 * It only looks at windows from the phone/telephony system apps.
 */
public class UssdService extends AccessibilityService {
    static volatile UssdService inst;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String lastText = "";
    private long lastAt = 0;

    private static final Pattern SEND_BTN = Pattern.compile("(?i)^(send|reply|ok|okay|submit|yes|continue|accept)$");
    private static final Pattern CLOSE_BTN = Pattern.compile("(?i)^(cancel|dismiss|close|ok|okay|exit|no)$");
    private static final Pattern RUNNING = Pattern.compile("(?i)(ussd code running|please wait|loading)");

    private static class Scan {
        final StringBuilder text = new StringBuilder();
        boolean input = false;
        final List<AccessibilityNodeInfo> buttons = new ArrayList<>();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    void resetText() {
        lastText = "";
        lastAt = 0;
    }

    private static boolean phonePkg(CharSequence p) {
        if (p == null) return false;
        String s = p.toString();
        return s.contains("android.phone") || s.contains("telephony") || s.contains("telecom");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            int t = e.getEventType();
            if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
            if (!phonePkg(e.getPackageName())) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !phonePkg(root.getPackageName())) return;

            Scan sc = new Scan();
            scan(root, sc);
            if (sc.buttons.isEmpty()) return;               // a USSD pop-up always has a button
            String text = sc.text.toString().trim();
            if (text.isEmpty() || RUNNING.matcher(text).find()) return;

            long now = SystemClock.uptimeMillis();
            if (text.equals(lastText) && now - lastAt < 2500) return;   // same pop-up, repeated event
            lastText = text;
            lastAt = now;

            Bridge.js("window.onUssdResponse(" + Bridge.q(text) + ")");
            if (!sc.input) {
                // Final message (no reply box): the page has been told, now close it.
                h.postDelayed(this::dismiss, 700);
            }
        } catch (Exception ignored) {
        }
    }

    private void scan(AccessibilityNodeInfo n, Scan sc) {
        if (n == null) return;
        CharSequence cn = n.getClassName();
        String c = cn == null ? "" : cn.toString();
        CharSequence tx = n.getText();
        if (c.contains("EditText")) {
            sc.input = true;
        } else if (c.contains("Button")) {
            sc.buttons.add(n);
        } else if (tx != null && tx.length() > 0) {
            if (sc.text.length() > 0) sc.text.append('\n');
            sc.text.append(tx);
        }
        for (int i = 0; i < n.getChildCount(); i++) scan(n.getChild(i), sc);
    }

    /** Type a reply into the USSD box and press Send. */
    void reply(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Bridge.toast("No USSD window found to reply to");
            return;
        }
        AccessibilityNodeInfo in = findInput(root);
        if (in != null) {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            in.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        }
        lastAt = SystemClock.uptimeMillis();   // ignore the old pop-up closing
        h.postDelayed(() -> {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) press(r, SEND_BTN, true);
        }, 200);
    }

    /** Close the USSD pop-up (Cancel / OK). */
    void dismiss() {
        Wake.scheduleLock();
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || !phonePkg(r.getPackageName())) return;
        lastAt = SystemClock.uptimeMillis();
        press(r, CLOSE_BTN, false);
    }

    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence cn = n.getClassName();
        if (cn != null && cn.toString().contains("EditText")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findInput(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Click the button whose label matches; if none match (other languages), use the last (send) or first (cancel) button. */
    private void press(AccessibilityNodeInfo root, Pattern label, boolean lastIfNone) {
        Scan sc = new Scan();
        scan(root, sc);
        if (sc.buttons.isEmpty()) return;
        AccessibilityNodeInfo pick = null;
        for (AccessibilityNodeInfo b : sc.buttons) {
            CharSequence t = b.getText();
            if (t != null && label.matcher(t.toString().trim()).matches()) {
                pick = b;
                break;
            }
        }
        if (pick == null) pick = lastIfNone ? sc.buttons.get(sc.buttons.size() - 1) : sc.buttons.get(0);
        click(pick);
    }

    private void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        while (x != null && !x.isClickable()) x = x.getParent();
        if (x == null) x = n;
        x.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /* ---------------- screen off: unlock and lock ---------------- */

    interface UnlockResult {
        void done(boolean ok);
    }

    private boolean pinTyped = false;
    private long typedAt = 0;
    private int typeAttempts = 0;

    /** Reveals the PIN pad and types the saved PIN. Gives up after two tries so the phone never gets locked out. */
    void unlock(final String pin, final UnlockResult r) {
        pinTyped = false;
        typedAt = 0;
        typeAttempts = 0;
        h.postDelayed(() -> unlockStep(pin, r, 0), 700);
    }

    private void unlockStep(final String pin, final UnlockResult r, final int n) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!km.isKeyguardLocked() && pm.isInteractive()) {
            h.postDelayed(() -> r.done(true), 500);
            return;
        }
        if (n >= 36) {
            r.done(false);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!pinTyped || now - typedAt > 4000) {
            if (pinTyped && typeAttempts >= 2) {
                r.done(false);
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean typed = false;
            try {
                typed = root != null && typePin(root, pin);
            } catch (Exception ignored) {
            }
            if (typed) {
                pinTyped = true;
                typedAt = now;
                typeAttempts++;
            } else if (n % 3 == 1) {
                swipeUp();
            }
        }
        h.postDelayed(() -> unlockStep(pin, r, n + 1), 500);
    }

    private void swipeUp() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            Path p = new Path();
            p.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f);
            p.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 300));
            dispatchGesture(b.build(), null, null);
        } catch (Exception ignored) {
        }
    }

    void lockNow() {
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    private boolean typePin(AccessibilityNodeInfo root, String pin) {
        if (pin.matches("\\d+")) {
            AccessibilityNodeInfo[] keys = new AccessibilityNodeInfo[10];
            AccessibilityNodeInfo[] enter = new AccessibilityNodeInfo[1];
            findKeys(root, keys, enter);
            List<AccessibilityNodeInfo> seq = new ArrayList<>();
            for (char ch : pin.toCharArray()) {
                AccessibilityNodeInfo k = keys[ch - '0'];
                if (k == null) return false;
                seq.add(k);
            }
            clickSeq(seq, 0, enter[0]);
            return true;
        }
        AccessibilityNodeInfo f = findPasswordField(root);
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (Build.VERSION.SDK_INT >= 30) {
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return true;
    }

    private void clickSeq(final List<AccessibilityNodeInfo> l, final int i, final AccessibilityNodeInfo enter) {
        if (i >= l.size()) {
            if (enter != null) h.postDelayed(() -> click(enter), 250);
            return;
        }
        click(l.get(i));
        h.postDelayed(() -> clickSeq(l, i + 1, enter), 160);
    }

    /** Finds the number keys of the lock-screen PIN pad (by id first, then by the digit shown on the key). */
    private void findKeys(AccessibilityNodeInfo n, AccessibilityNodeInfo[] keys, AccessibilityNodeInfo[] enter) {
        if (n == null) return;
        String id = n.getViewIdResourceName();
        if (id != null) {
            if (id.matches(".*[:/]key[0-9]")) {
                int d = id.charAt(id.length() - 1) - '0';
                if (keys[d] == null) keys[d] = n;
            } else if (id.endsWith("key_enter")) {
                enter[0] = n;
            }
        }
        CharSequence pk = n.getPackageName();
        if (pk != null && pk.toString().contains("systemui")) {
            CharSequence t = n.getText();
            CharSequence cd = n.getContentDescription();
            String one = t != null ? t.toString().trim() : (cd != null ? cd.toString().trim() : "");
            if (one.length() == 1 && Character.isDigit(one.charAt(0))) {
                int d = one.charAt(0) - '0';
                if (keys[d] == null) keys[d] = n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) findKeys(n.getChild(i), keys, enter);
    }

    private AccessibilityNodeInfo findPasswordField(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence cn = n.getClassName();
        if (cn != null && cn.toString().contains("EditText")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findPasswordField(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
