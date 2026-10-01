package com.hoangnt391.automessenger;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 9001;
    private android.content.SharedPreferences p;
    private Switch enabled, auto;
    private EditText prompt, apiKey, model;
    private TextView status;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("AutoMessenger", 0);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32,32,32,32);

        TextView title = new TextView(this);
        title.setText("AutoMessenger\nAI tự trả lời Messenger");
        title.setTextSize(25);
        l.addView(title);

        enabled = new Switch(this);
        enabled.setText("Bật dịch vụ nền");
        enabled.setChecked(p.getBoolean("enabled", false));
        l.addView(enabled);

        auto = new Switch(this);
        auto.setText("Tự động AI trả lời hội thoại");
        auto.setChecked(p.getBoolean("auto", false));
        l.addView(auto);

        apiKey = new EditText(this);
        apiKey.setHint("OpenAI API key (sk-...)");
        apiKey.setInputType(0x00000081); // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        apiKey.setText(p.getString("api_key", ""));
        l.addView(apiKey);

        model = new EditText(this);
        model.setHint("Model");
        model.setText(p.getString("model", "gpt-5.6-luna"));
        l.addView(model);

        prompt = new EditText(this);
        prompt.setHint("Phong cách trả lời AI");
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
            } else Toast.makeText(this, "Đã có quyền bong bóng", Toast.LENGTH_SHORT).show();
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
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) updateStatus();
    }

    private void updateStatus() {
        boolean accessibility = MessageAccessibilityService.isRunning();
        boolean key = !p.getString("api_key", "").trim().isEmpty();
        status.setText("\nTrạng thái: " +
                (accessibility ? "Trợ năng OK" : "Chưa bật Trợ năng") +
                " | API key: " + (key ? "đã nhập" : "chưa nhập") +
                "\nMở Messenger, vào một cuộc hội thoại rồi bật 'Tự động AI trả lời'.");
    }

    private void saveAndApply() {
        final boolean want = enabled.isChecked();
        final boolean autoWant = auto.isChecked();
        final String key = apiKey.getText().toString().trim();

        p.edit()
                .putBoolean("auto", autoWant)
                .putString("api_key", key)
                .putString("model", model.getText().toString().trim())
                .putString("prompt", prompt.getText().toString().trim())
                .apply();

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

        if (autoWant && key.isEmpty()) {
            auto.setChecked(false);
            Toast.makeText(this, "Muốn AI tự trả lời thì phải nhập OpenAI API key", Toast.LENGTH_LONG).show();
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

        Toast.makeText(this, "AutoMessenger đã chạy nền", Toast.LENGTH_SHORT).show();
    }
}