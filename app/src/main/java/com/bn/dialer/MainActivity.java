package com.bn.dialer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;

    // setup flow state
    private boolean explained = false;
    private boolean requested = false;
    private boolean requesting = false;
    private boolean giveUp = false;
    private boolean dialogOpen = false;
    private boolean askedBattery = false;
    private static boolean askedAccess = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF16A34A);

        web = new WebView(this);
        setContentView(web);
        Bridge.web = web;

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);   // lets the bundled page talk to Firebase

        web.addJavascriptInterface(new Bridge(this), "NativeBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                Bridge.ready = true;
                Bridge.flush(MainActivity.this);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Cancel", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                final EditText in = new EditText(MainActivity.this);
                in.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setView(in)
                        .setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Cancel", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");

        try {
            startForegroundService(new Intent(this, BnService.class));
        } catch (Exception ignored) {
        }
    }

    /* ---------------- first-run setup: permissions, accessibility, battery ---------------- */

    private List<String> missing() {
        List<String> need = new ArrayList<>();
        String[] all = {Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS};
        for (String p : all) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        return need;
    }

    private boolean permanentlyDenied(List<String> miss) {
        for (String p : miss) if (!shouldShowRequestPermissionRationale(p)) return true;
        return false;
    }

    private void request(List<String> miss) {
        requesting = true;
        requestPermissions(miss.toArray(new String[0]), 1);
    }

    private void ask(String title, String msg, String yes, final Runnable onYes, final Runnable onNo) {
        dialogOpen = true;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(yes, (d, w) -> {
                    dialogOpen = false;
                    if (onYes != null) onYes.run();
                })
                .setNegativeButton("Later", (d, w) -> {
                    dialogOpen = false;
                    if (onNo != null) onNo.run();
                })
                .show();
    }

    private void nextStep() {
        if (dialogOpen || requesting) return;

        final List<String> miss = missing();
        if (!miss.isEmpty()) {
            if (!explained) {
                explained = true;
                ask("Welcome to Bn",
                        "Bn needs a few permissions to work:\n\n"
                                + "\u2022 Phone: to dial your bundle codes\n"
                                + "\u2022 SMS: to read M-Pesa payment messages and text your customers\n"
                                + "\u2022 Notifications: to show that Bn is running\n\n"
                                + "Tap Allow on each question that follows. If a permission looks greyed out or says "
                                + "\"Restricted setting\", go to Settings > Apps > Bn > top-right menu > "
                                + "Allow restricted settings, then come back.",
                        "Continue", () -> request(miss), () -> giveUp = true);
            } else if (requested && permanentlyDenied(miss)) {
                if (!giveUp) {
                    ask("Permission needed",
                            "Android is no longer asking for some permissions. Open Bn's settings, tap Permissions "
                                    + "and allow Phone, SMS and Notifications.",
                            "Open settings", () -> {
                                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            }, () -> giveUp = true);
                }
            } else if (requested) {
                if (!giveUp) {
                    ask("Bn cannot work without these",
                            "Phone and SMS access are needed to dial codes and read payments. Please allow them.",
                            "Allow", () -> request(miss), () -> giveUp = true);
                }
            } else {
                request(miss);
            }
            return;
        }

        if (!accessOn() && !askedAccess) {
            askedAccess = true;
            ask("One more step",
                    "To send USSD replies automatically, turn on Bn in Accessibility settings "
                            + "(Installed apps > Bn > On).\n\nIf Android says the setting is restricted: "
                            + "Settings > Apps > Bn > top-right menu > Allow restricted settings, then try again.",
                    "Open settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), null);
            return;
        }

        if (!askedBattery) {
            askedBattery = true;
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        requesting = false;
        requested = true;
        nextStep();
    }

    private boolean accessOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    @Override
    protected void onResume() {
        super.onResume();
        nextStep();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // keep Bn running instead of closing it
    }

    @Override
    protected void onDestroy() {
        Bridge.ready = false;
        Bridge.web = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
