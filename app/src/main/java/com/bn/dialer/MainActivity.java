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
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private View topBar, bottomBar;
    private static MainActivity inst;

    // setup flow state
    private boolean explained = false;
    private boolean requested = false;
    private boolean requesting = false;
    private boolean giveUp = false;
    private boolean dialogOpen = false;
    private boolean askedBattery = false;
    private static boolean askedAccess = false;
    private static boolean checkedUpdate = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        web = new WebView(this);

        // Keep the app inside the safe area: a green strip behind the status bar and a
        // matching strip behind the navigation bar, with the page in between.
        topBar = new View(this);
        bottomBar = new View(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            topBar.getLayoutParams().height = ins.getSystemWindowInsetTop();
            bottomBar.getLayoutParams().height = ins.getSystemWindowInsetBottom();
            topBar.requestLayout();
            bottomBar.requestLayout();
            return ins;
        });
        setContentView(root);
        applyBars(0xFF350610, 0xFFFFFDFD, false);
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

        checkForUpdate();
    }

    /* ---------------- automatic update check ---------------- */

    /** Reads version.json from the install page and offers the new APK if there is a newer build. */
    private void checkForUpdate() {
        if (checkedUpdate) return;
        checkedUpdate = true;
        new Thread(() -> {
            try {
                String base = readAsset("update-url.txt").trim();
                if (base.isEmpty() || !base.startsWith("http")) return;
                if (!base.endsWith("/")) base += "/";
                HttpURLConnection c = (HttpURLConnection) new URL(base + "version.json?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                final int latest = j.optInt("code", 0);
                final String name = j.optString("version", "");
                int mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                if (latest <= mine) return;
                final String apk = base + "Bn.apk";
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Update available")
                            .setMessage("A new version of Bn" + (name.isEmpty() ? "" : " (" + name + ")")
                                    + " is ready.\n\nTap Update, open the downloaded file and tap Install. "
                                    + "Your sales, offers and settings are kept.")
                            .setPositiveButton("Update", (d, w) -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk)));
                                } catch (Exception ignored) {
                                }
                            })
                            .setNegativeButton("Later", null)
                            .show();
                });
            } catch (Exception ignored) {
                // offline or no update info: stay silent
            }
        }).start();
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        }
    }

    /** Colours the status bar (green) and navigation bar to match the page theme. */
    static void applyBars(final String top, final String bottom, final boolean dark) {
        final MainActivity a = inst;
        if (a == null) return;
        try {
            a.runOnUiThread(() -> a.applyBars(Color.parseColor(top), Color.parseColor(bottom), dark));
        } catch (Exception ignored) {
        }
    }

    private void applyBars(int top, int bottom, boolean dark) {
        topBar.setBackgroundColor(top);
        bottomBar.setBackgroundColor(bottom);
        getWindow().setStatusBarColor(top);
        getWindow().setNavigationBarColor(bottom);
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;            // white icons on green
        if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;    // dark icons on the light bar
        d.setSystemUiVisibility(f);
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
        if (inst == this) inst = null;
        Bridge.ready = false;
        Bridge.web = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
