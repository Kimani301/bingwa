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
        root.setOnApplyWindowInsetsListener((v, ins)
