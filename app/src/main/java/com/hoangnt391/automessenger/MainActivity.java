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

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32,32,32,32);

        TextView title = new TextView(this);
        title.setText("AutoMessenger\nGemini AI tự trả lời Messenger");
        title.setTextSize(25);
        l.addView(title);

        enabled = new Switch(this);
        enabled.setText("Bật dịch vụ nền");
        enabled.setChecked(p.getBoolean("enabled", false));
        l.addView(enabled);

        auto = new Switch(this);
        auto.setText("Tự động Gemini trả lời hội thoại");
        auto.setChecked(p.getBoolean("auto", false));
        l.addView(auto);

        apiKey = new EditText(this);
        apiKey.setHint("Gemini API key");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(p.getString("api_key", ""));
        l.addView(apiKey);

        model = new EditText(this);
        model.setHint("Gemini model");
        model.setText(p.getString("model", "gemini-flash-latest"));
        l.addView(model);

        prompt = new EditText(this);
        prompt.setHint("Yêu cầu trả lời");
        prompt.setText(p.getString("prompt",
                "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                "Không nhắc rằng bạn là AI. Không dùng markdown."));
        prompt.setMinLines(3);
        l.addView(prompt);

        Button overlay = new Button(this);
        overlay.setText("Cấp quyền bong bóng nổi");
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                Toast.makeText(this, "Đã có quyền bong bóng", Toast.LENGTH_SHORT).show();
            }
        });
        l.addView(overlay);

        Button accessibility = new Button(this);
        accessibility.setText("Mở quyền Trợ năng cho Messenger");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.addView(accessibility);

        Button save = new Button(this);
        save.setText("Lưu và khởi động");
        save.setOnClickListener(v -> saveAndApply());
        l.addView(save);

        status = new TextView(this);
        l.addView(status);
        updateStatus();
        setContentView(l);
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