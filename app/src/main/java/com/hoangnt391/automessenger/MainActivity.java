package com.hoangnt391.automessenger;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 9001;
    private android.content.SharedPreferences p;
    private Switch enabled, auto;
    private EditText prompt, apiKey, model;
    private TextView status;
    private boolean firstLoad = true;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("AutoMessenger", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 28);
        root.setBackgroundColor(0xFFF7F5FA);

        TextView hero = new TextView(this);
        hero.setText("💬  AutoMessenger AI");
        hero.setTextSize(28);
        hero.setTextColor(0xFF4A2B72);
        hero.setTypeface(null, android.graphics.Typeface.BOLD);
        hero.setPadding(4, 8, 4, 2);
        root.addView(hero);

        TextView sub = new TextView(this);
        sub.setText("Trợ lý trả lời tin nhắn nhanh, tự nhiên");
        sub.setTextSize(14);
        sub.setTextColor(0xFF6F6878);
        sub.setPadding(4, 0, 4, 18);
        root.addView(sub);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(24);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 22);
        card.setBackground(cardBg);

        TextView section = new TextView(this);
        section.setText("⚙️  Tự động trả lời");
        section.setTextSize(18);
        section.setTextColor(0xFF27212E);
        section.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(section);

        enabled = new Switch(this);
        enabled.setText("Dịch vụ nền");
        enabled.setTextSize(15);
        enabled.setChecked(p.getBoolean("enabled", false));
        card.addView(enabled);

        auto = new Switch(this);
        auto.setText("Tự động trả lời bằng Gemini");
        auto.setTextSize(15);
        auto.setChecked(p.getBoolean("auto", false));
        card.addView(auto);

        apiKey = new EditText(this);
        apiKey.setHint("Gemini API key");
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(p.getString("api_key", ""));
        card.addView(apiKey);

        model = new EditText(this);
        model.setHint("Model  •  gemini-flash-latest");
        model.setSingleLine(true);
        model.setText(p.getString("model", "gemini-flash-latest"));
        card.addView(model);

        prompt = new EditText(this);
        prompt.setHint("Yêu cầu trả lời");
        prompt.setText(p.getString("prompt",
                "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không nhắc rằng bạn là AI."));
        prompt.setMinLines(3);
        prompt.setGravity(android.view.Gravity.TOP);
        card.addView(prompt);

        l.addView(card);

        TextView section2 = new TextView(this);
        section2.setText("🔐  Quyền cần thiết");
        section2.setTextSize(18);
        section2.setTextColor(0xFF27212E);
        section2.setTypeface(null, android.graphics.Typeface.BOLD);
        section2.setPadding(4, 22, 4, 10);
        l.addView(section2);

        Button overlay = new Button(this);
        overlay.setText("Bong bóng nổi");
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else Toast.makeText(this, "Đã có quyền bong bóng ✓", Toast.LENGTH_SHORT).show();
        });
        l.addView(overlay);

        Button accessibility = new Button(this);
        accessibility.setText("Trợ năng • Messenger / ứng dụng chat");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.addView(accessibility);

        Button save = new Button(this);
        save.setText("LƯU & KHỞI ĐỘNG");
        save.setTextSize(15);
        save.setAllCaps(false);
        save.setOnClickListener(v -> saveAndApply());
        l.addView(save);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFF5F5866);
        status.setPadding(4, 16, 4, 12);
        l.addView(status);

        scroll.addView(l);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateStatus();
        firstLoad = false;
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) {
            // Reload saved values whenever the settings screen is shown again.
            p = getSharedPreferences("AutoMessenger", 0);
            enabled.setChecked(p.getBoolean("enabled", false));
            auto.setChecked(p.getBoolean("auto", false));
            apiKey.setText(p.getString("api_key", ""));
            model.setText(p.getString("model", "gemini-flash-latest"));
            prompt.setText(p.getString("prompt",
                    "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                    "Không nhắc rằng bạn là AI. Không dùng markdown."));
            updateStatus();
        }
    }

    private void updateStatus() {
        boolean accessibility = MessageAccessibilityService.isRunning();
        boolean key = !p.getString("api_key", "").trim().isEmpty();
        boolean keyValid = p.getBoolean("key_valid", false);
        status.setText("\nTrạng thái: " +
                (accessibility ? "Trợ năng OK" : "Chưa bật Trợ năng") +
                " | Gemini key: " + (key ? (keyValid ? "đúng" : "chưa kiểm tra") : "chưa nhập") +
                "\nMở Messenger, vào một cuộc hội thoại rồi bật tự động trả lời.");
    }

    private void saveAndApply() {
        final boolean want = enabled.isChecked();
        final boolean autoWant = auto.isChecked();
        final String key = apiKey.getText().toString().trim();
        final String modelValue = model.getText().toString().trim();
        final String request = prompt.getText().toString().trim();

        p.edit()
                .putBoolean("auto", autoWant)
                .putString("api_key", key)
                .putString("model", modelValue)
                .putString("prompt", request)
                .putBoolean("key_valid", false)
                .apply();

        if (key.isEmpty()) {
            if (autoWant) {
                auto.setChecked(false);
                Toast.makeText(this, "Muốn AI tự trả lời thì phải nhập Gemini API key", Toast.LENGTH_LONG).show();
                updateStatus();
                return;
            }
            applyServiceState(want, false);
            return;
        }

        Toast.makeText(this, "Đang kiểm tra Gemini API key...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                AiClient.validateKey(key, modelValue);
                runOnUiThread(() -> {
                    p.edit().putBoolean("key_valid", true).apply();
                    Toast.makeText(this, "API key hợp lệ ✓", Toast.LENGTH_SHORT).show();
                    updateStatus();
                    applyServiceState(want, autoWant);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    p.edit().putBoolean("key_valid", false).apply();
                    auto.setChecked(false);
                    Toast.makeText(this, "API key sai hoặc model không hợp lệ", Toast.LENGTH_LONG).show();
                    updateStatus();
                    if (!autoWant) applyServiceState(want, false);
                });
            }
        }).start();
    }

    private void applyServiceState(boolean want, boolean autoWant) {
        if (!want) {
            p.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, AutoMessengerService.class));
            Toast.makeText(this, "Đã tắt AutoMessenger", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            enabled.setChecked(false);
            Toast.makeText(this, "Hãy cấp quyền bong bóng nổi trước", Toast.LENGTH_LONG).show();
            return;
        }

        if (autoWant && !p.getBoolean("key_valid", false)) {
            auto.setChecked(false);
            Toast.makeText(this, "API key chưa được xác thực", Toast.LENGTH_LONG).show();
            return;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            enabled.setChecked(false);
            p.edit().putBoolean("enabled", false).apply();
            Toast.makeText(this, "Bạn chưa cấp quyền ghi màn hình", Toast.LENGTH_LONG).show();
            return;
        }

        p.edit().putBoolean("enabled", true).apply();

        Intent s = new Intent(this, AutoMessengerService.class);
        s.putExtra(AutoMessengerService.EXTRA_RESULT_CODE, resultCode);
        s.putExtra(AutoMessengerService.EXTRA_DATA, data);
        startForegroundService(s);

        Toast.makeText(this, "AutoMessenger đã chạy nền với Gemini", Toast.LENGTH_SHORT).show();
    }
}