package com.hoangnt391.automessenger;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class AshnaLoginActivity extends Activity {
    private static final String PREFS = "AutoMessenger";
    private WebView webView;
    private TextView status;
    private ProgressBar progress;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(2), dp(4), dp(2), dp(4));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private String ashnaUrl() {
        String agent = getSharedPreferences(PREFS, 0)
                .getString("ashna_agent", "gpt-6.1-sol").trim();
        if (agent.isEmpty()) agent = "gpt-6.1-sol";
        return "https://app.ashna.ai/chat?agent=" + android.net.Uri.encode(agent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(30, 24, 38));
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F3F8);

        // Compact app bar instead of a fixed full-screen close button.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(0xFF211A29);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(Color.WHITE);
        back.setAllCaps(false);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setPadding(0, 0, 0, 0);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Quay lại");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("Ashna Web", 18, Color.WHITE, true);
        TextView sub = label("Đăng nhập • phiên được lưu trên máy", 12, 0xFFD9CFDF, false);
        titleBox.addView(title);
        titleBox.addView(sub);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        Button refresh = new Button(this);
        refresh.setText("↻");
        refresh.setTextSize(23);
        refresh.setTextColor(Color.WHITE);
        refresh.setAllCaps(false);
        refresh.setMinWidth(0);
        refresh.setMinHeight(0);
        refresh.setPadding(0, 0, 0, 0);
        refresh.setBackgroundColor(Color.TRANSPARENT);
        refresh.setContentDescription("Tải lại Ashna");
        refresh.setOnClickListener(v -> webView.reload());
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));

        root.addView(bar);

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(12), dp(8), dp(12), dp(8));
        info.setBackgroundColor(0xFFF0EAF5);

        status = label("Đang mở Ashna Web…", 13, 0xFF4E3C5C, false);
        info.addView(status, new LinearLayout.LayoutParams(0, -2, 1));

        Button minimize = new Button(this);\n        minimize.setText("Thu nhỏ");\n        minimize.setAllCaps(false);\n        minimize.setTextColor(0xFF4E3C5C);\n        minimize.setBackground(bg(0xFFE1D7EA, 12));\n        minimize.setPadding(dp(8), 0, dp(8), 0);\n        minimize.setOnClickListener(v -> moveTaskToBack(true));\n        info.addView(minimize, new LinearLayout.LayoutParams(dp(82), dp(40)));\n\n        Button done = new Button(this);
        done.setText("Xong");
        done.setAllCaps(false);
        done.setTextColor(Color.WHITE);
        done.setBackground(bg(0xFF6F42C1, 12));
        done.setPadding(dp(12), 0, dp(12), 0);
        done.setOnClickListener(v -> confirmAndFinish());
        info.addView(done, new LinearLayout.LayoutParams(dp(72), dp(40)));
        root.addView(info);

        FrameLayout webBox = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.WHITE);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);

        webBox.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        webBox.addView(progress, pp);
        root.addView(webBox, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = label(
                "Có thể Thu nhỏ để sang Messenger/Zalo/WhatsApp xác nhận rồi quay lại. WebView không khóa màn hình; phiên đăng nhập vẫn được giữ bằng cookie/local storage.",
                11, 0xFF77707D, false);
        hint.setPadding(dp(12), dp(6), dp(12), dp(8));
        root.addView(hint);

        setContentView(root);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                status.setText("Đang tải Ashna Web…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                checkLogin();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        if (state != null) {
            webView.restoreState(state);
        } else {
            webView.loadUrl(ashnaUrl());
        }
    }

    private void checkLogin() {
        if (webView == null) return;
        String js = "(function(){"
                + "var vis=function(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "return r.width>0&&r.height>0&&s.display!='none'&&s.visibility!='hidden';};"
                + "var t=document.body?document.body.innerText:'';"
                + "var es=[].slice.call(document.querySelectorAll('button,a,input,textarea,[contenteditable=true]'));"
                + "var login=es.some(function(e){return vis(e)&&/sign in|log in|continue with google|continue with email|đăng nhập|login/i.test((e.innerText||e.value||e.getAttribute('aria-label')||''));});"
                + "var composer=[].slice.call(document.querySelectorAll('textarea,input,[contenteditable=true]')).some(function(e){return vis(e)&&!e.disabled;});"
                + "var chat=/\\/chat/i.test(location.href)||/new chat/i.test(t);"
                + "return JSON.stringify({login:login,composer:composer,chat:chat});})()";
        webView.evaluateJavascript(js, value -> {
            boolean ok = value != null
                    && value.contains("\"login\":false")
                    && value.contains("\"composer\":true")
                    && value.contains("\"chat\":true");
            if (ok) {
                getSharedPreferences(PREFS, 0).edit()
                        .putBoolean("ashna_login_confirmed", true).apply();
                status.setText("✓ Đã đăng nhập. Phiên đã lưu — bạn có thể chuyển ứng dụng hoặc bấm Xong.");
            } else {
                status.setText("Đăng nhập Ashna Web rồi quay lại đây để xác nhận.");
            }
        });
    }

    private void confirmAndFinish() {
        CookieManager.getInstance().flush();
        checkLogin();
        getSharedPreferences(PREFS, 0).edit()
                .putBoolean("ashna_login_confirmed", true).apply();
        Toast.makeText(this, "Đã lưu phiên Ashna Web.", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (webView != null) webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            try { webView.stopLoading(); } catch (Exception ignored) {}
            webView.destroy();
        }
        super.onDestroy();
    }
}
