package com.crystalaltar.game;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView web;
    private FrameLayout root;
    private OnBackInvokedCallback backCb;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#05060C"));
        setContentView(root);
        createWebView(loader, b);

        // Android 15+ рисует приложение edge-to-edge: отодвигаем игру от системных панелей
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(getWindow(), root);
        ic.setAppearanceLightStatusBars(false);
        ic.setAppearanceLightNavigationBars(false);

        // targetSdk 36: onBackPressed() больше не вызывается — регистрируем обработчик «Назад» вручную
        if (Build.VERSION.SDK_INT >= 33) {
            backCb = () -> {
                if (web != null && web.canGoBack()) web.goBack(); else finish();
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCb);
        }
    }

    private void createWebView(final WebViewAssetLoader loader, Bundle saved) {
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#05060C"));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse res = loader.shouldInterceptRequest(r.getUrl());
                if (res == null && HOST.equals(r.getUrl().getHost())) {
                    // нет такого локального файла (например favicon) — отвечаем сразу, не идём в сеть
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return res;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (HOST.equals(r.getUrl().getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
            // если процесс WebView убит системой — пересоздаём, а не падаем
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (web == v) {
                    root.removeView(web);
                    web.destroy();
                    createWebView(loader, null);
                }
                return true;
            }
        });

        boolean restored = false;
        if (saved != null) restored = web.restoreState(saved) != null;
        if (!restored) web.loadUrl(START_URL);
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        if (web != null) web.saveState(o);
    }
    @Override @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCb != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCb);
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
