package com.bn.dialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

/**
 * Owns the one Bn page (the WebView that holds all the payment and dialing logic).
 * The page lives as long as the Bn process, not as long as the screen: the foreground service
 * creates it, and MainActivity only borrows it to show it. Swiping Bn away from recent apps
 * removes the screen but leaves the page running, so payments keep being dialed.
 * All methods must be called on the main thread.
 */
final class Host {
    private static MutableContextWrapper wrapper;

    private Host() {
    }

    /** Returns the page, creating and loading it the first time. */
    static WebView ensure(Context c) {
        WebView w = Bridge.web;
        if (w != null) return w;
        final Context app = c.getApplicationContext();
        wrapper = new MutableContextWrapper(app);
        w = new WebView(wrapper);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);   // lets the bundled page talk to Firebase
        try {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        } catch (Throwable ignored) {
        }

        w.addJavascriptInterface(new Bridge(app), "NativeBridge");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                Bridge.ready = true;
                Bridge.flush(app);
            }

            /** The system killed the page's renderer (low memory in the background): rebuild it instead of crashing Bn. */
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Bridge.ready = false;
                Bridge.web = null;
                try {
                    if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                    v.destroy();
                } catch (Throwable ignored) {
                }
                Bridge.MAIN.postDelayed(() -> {
                    try {
                        ensure(app);
                        MainActivity a = MainActivity.inst;
                        if (a != null && !a.isFinishing()) a.reattach();
                    } catch (Throwable ignored) {
                    }
                }, 500);
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                final MainActivity a = MainActivity.inst;
                if (a == null || a.isFinishing()) {
                    r.confirm();      // no screen: nobody to ask
                    return true;
                }
                new AlertDialog.Builder(a).setMessage(msg)
                        .setPositiveButton("OK", (d, x) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                final MainActivity a = MainActivity.inst;
                if (a == null || a.isFinishing()) {
                    r.cancel();       // no screen: never answer "yes" on the owner's behalf
                    return true;
                }
                new AlertDialog.Builder(a).setMessage(msg)
                        .setPositiveButton("OK", (d, x) -> r.confirm())
                        .setNegativeButton("Cancel", (d, x) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                final MainActivity a = MainActivity.inst;
                if (a == null || a.isFinishing()) {
                    r.cancel();
                    return true;
                }
                final EditText in = new EditText(a);
                in.setText(def == null ? "" : def);
                new AlertDialog.Builder(a).setMessage(msg).setView(in)
                        .setPositiveButton("OK", (d, x) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Cancel", (d, x) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });

        Bridge.ready = false;
        Bridge.web = w;
        w.loadUrl("file:///android_asset/index.html");
        return w;
    }

    /** Reloads a page that stopped answering. Sales are saved on the phone, so nothing is lost. */
    static void reload() {
        WebView w = Bridge.web;
        if (w == null) return;
        Bridge.ready = false;
        try {
            w.reload();
        } catch (Throwable ignored) {
        }
    }

    /** The screen is showing the page: dialogs and pickers need the activity as context. */
    static void useActivity(Activity a) {
        if (wrapper != null) wrapper.setBaseContext(a);
    }

    /** The screen is gone: give the page back to the app so the activity can be freed. */
    static void useApp(Context c) {
        if (wrapper != null) wrapper.setBaseContext(c.getApplicationContext());
    }

    /**
     * Makes sure the page and the foreground service are alive. Called when a payment text arrives
     * and after the phone restarts, so a swiped-away or killed Bn comes back by itself.
     */
    static void wake(Context c) {
        final Context app = c.getApplicationContext();
        Bridge.MAIN.post(() -> {
            try {
                ensure(app);
            } catch (Throwable ignored) {
            }
        });
        if (!BnService.up) {
            try {
                app.startForegroundService(new Intent(app, BnService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
