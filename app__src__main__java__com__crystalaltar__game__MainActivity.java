package com.crystalaltar.game;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
    private static final int REQ_FILE = 4101, REQ_SAVE = 4102;
    private ValueCallback<Uri[]> fileCb;      // выбор файла (импорт из «Загрузок», аватарка из галереи)
    private String pendingText;               // текст, который ждёт выбора места сохранения (Android 9 и ниже)

    /** Мост для index.html: window.AndroidBridge.saveText(...) и readClipboard() */
    private class Bridge {
        @JavascriptInterface
        public boolean saveText(String name, String text) {
            try {
                String safe = (name == null ? "crystal-altar.txt" : name).replaceAll("[^A-Za-z0-9._-]", "_");
                if (safe.length() > 80) safe = safe.substring(0, 80);
                if (!safe.endsWith(".txt")) safe += ".txt";
                final String body = text == null ? "" : text;
                if (Build.VERSION.SDK_INT >= 29) {
                    // Android 10+: пишем прямо в «Загрузки», разрешения не нужны
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return false;
                    OutputStream o = getContentResolver().openOutputStream(u);
                    if (o == null) return false;
                    try { o.write(body.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
                    toast("Файл сохранён в «Загрузки»");
                    return true;
                }
                // Android 9 и ниже: системный диалог «Сохранить как» (тоже без разрешений)
                pendingText = body;
                final String title = safe;
                runOnUiThread(new Runnable() { @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TITLE, title);
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) { pendingText = null; }
                }});
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
                    @Override public String call() {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData c = cm == null ? null : cm.getPrimaryClip();
                        if (c == null || c.getItemCount() < 1) return "";
                        CharSequence t = c.getItemAt(0).coerceToText(MainActivity.this);
                        return t == null ? "" : t.toString();
                    }
                });
                runOnUiThread(task);
                return task.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                return "";
            }
        }
    }

    private void toast(final String m) {
        runOnUiThread(new Runnable() { @Override public void run() {
            Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
        }});
    }

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

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            // <input type="file">: импорт из файла и выбор аватарки
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

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

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(res == RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(res, data) : null);
                fileCb = null;
            }
        } else if (req == REQ_SAVE) {
            String text = pendingText;
            pendingText = null;
            if (res == RESULT_OK && data != null && data.getData() != null && text != null) {
                try {
                    OutputStream o = getContentResolver().openOutputStream(data.getData());
                    if (o != null) {
                        try { o.write(text.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
                        toast("Файл сохранён");
                    }
                } catch (Exception e) { toast("Не удалось сохранить файл"); }
            }
        }
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
