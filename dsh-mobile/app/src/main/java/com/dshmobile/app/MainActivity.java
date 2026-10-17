package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import android.view.*;
import java.io.*;

/** Loopback-only WebView. The supported server token URL establishes browser authentication. */
public final class MainActivity extends Activity {
    private WebView web;
    private TextView status;
    private HarnessService service;
    private String loaded;
    private ValueCallback<Uri[]> chooser;
    private final Runnable refresh = () -> {
        if (service == null) return;
        status.setText(service.state());
        String url = service.launchUrl();
        if (url != null && !url.equals(loaded) && allowed(Uri.parse(url))) { loaded = url; web.loadUrl(url); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { service = ((HarnessService.LocalBinder)b).service(); service.observe(refresh); }
        public void onServiceDisconnected(ComponentName n) { service = null; status.setText("Service disconnected; runtime state unknown"); }
    };
    static boolean allowed(Uri uri) {
        return WebOrigin.allowed(uri.toString());
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!RootRuntime.installed(this)) { startActivity(new Intent(this,SetupActivity.class)); finish(); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(1);
        box.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout bar = new LinearLayout(this);
        Button settings = Ui.outlineButton(this,"Settings"); settings.setOnClickListener(v -> startActivity(new Intent(this,SettingsActivity.class))); bar.addView(settings);
        Button screenshot = Ui.outlineButton(this,"Screenshot"); screenshot.setOnClickListener(v -> startActivity(new Intent(this,CaptureActivity.class))); bar.addView(screenshot);
        Button start = Ui.outlineButton(this,"Start"); start.setOnClickListener(v -> startForegroundService(new Intent(this,HarnessService.class))); bar.addView(start); box.addView(bar);
        status = Ui.hint(this,"Tap Start to authorize root and launch. No automatic restarts."); box.addView(status);
        web = new WebView(this);
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !allowed(request.getUrl()); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (allowed(request.getUrl())) return null;
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked", java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) { handler.cancel(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
                startActivityForResult(pick,42); return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        box.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(box);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
        bindService(new Intent(this,HarnessService.class),connection,BIND_AUTO_CREATE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==42 && chooser!=null) { chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data)); chooser=null; }
    }
    @Override protected void onDestroy() {
        if (service!=null) service.removeObserver(refresh);
        if (web!=null) { unbindService(connection); web.destroy(); }
        if (chooser!=null) chooser.onReceiveValue(null);
        super.onDestroy();
    }
}
