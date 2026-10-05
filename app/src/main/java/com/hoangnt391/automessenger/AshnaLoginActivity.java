package com.hoangnt391.automessenger;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class AshnaLoginActivity extends Activity {
    private WebView webView;
    private TextView status;
    private boolean confirmed = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);

        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        Button close = new Button(this);
        close.setText("Đóng");
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        cp.topMargin = 12; cp.rightMargin = 12;
        root.addView(close, cp);

        status = new TextView(this);
        status.setText("Đăng nhập Ashna Web • có thể chuyển sang ứng dụng khác");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC222222);
        status.setPadding(20, 10, 20, 10);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(status, sp);

        setContentView(root);

        if (state != null) webView.restoreState(state);
        else webView.loadUrl("https://app.ashna.ai/chat?agent=gpt-6-sol");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                checkLogin();
            }
        });
    }

    private void checkLogin() {
        webView.evaluateJavascript("(function(){var vis=function(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!='none'&&s.visibility!='hidden';};var es=[].slice.call(document.querySelectorAll('button,a,[role=button],input'));var login=es.some(function(e){return vis(e)&&/sign in|log in|continue with google|continue with email|đăng nhập|login/i.test((e.innerText||e.value||e.getAttribute('aria-label')||''));});var composer=[].slice.call(document.querySelectorAll('textarea,input,[contenteditable=true]')).some(vis);var chat=/\\/chat/i.test(location.href)||/new chat/i.test(document.body?document.body.innerText:'');return JSON.stringify({login:login,composer:composer,chat:chat,url:location.href});})()", value -> {
            if (value != null && value.contains("\"login\":false") && value.contains("\"composer\":true") && value.contains("\"chat\":true")) {
                if (!confirmed) {
                    confirmed = true;
                    CookieManager.getInstance().flush();
                    getSharedPreferences("AutoMessenger", 0).edit().putBoolean("ashna_login_confirmed", true).apply();
                    status.setText("✓ Đã đăng nhập Ashna. Phiên đã lưu — bạn có thể Đóng.");
                    Toast.makeText(this, "Đã xác nhận đăng nhập Ashna Web.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
