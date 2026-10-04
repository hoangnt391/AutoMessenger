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
    private EditText prompt;
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
        auto.setText("Tự động trả lời bằng ChatGPT");
        auto.setTextSize(15);
        auto.setChecked(p.getBoolean("auto", false));
        card.addView(auto);


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

        Button showBubble = new Button(this);
        showBubble.setText("Hiện lại bong bóng");
        showBubble.setOnClickListener(v -> {
            p.edit().putBoolean("bubble_hidden", false).apply();
            AutoMessengerService.requestShowBubble();
            Toast.makeText(this, "Đã bật lại bong bóng ✓", Toast.LENGTH_SHORT).show();
        });
        l.addView(showBubble);

        Button save = new Button(this);
        save.setText("LƯU & THOÁT");
        save.setTextSize(15);
        save.setAllCaps(false);
        save.setOnClickListener(v -> saveAndApply());
        l.addView(save);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFF5F5866);
        status.setPadding(4, 16, 4, 12);
        l.addView(status);

        Button exit = new Button(this);
        exit.setText("Thoát");
        exit.setAllCaps(false);
        exit.setOnClickListener(v -> {
            p.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, AutoMessengerService.class));
            Toast.makeText(this, "Đã tắt và thoát AutoMessenger", Toast.LENGTH_SHORT).show();
            finishAffinity();
        });
        l.addView(exit);

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
            prompt.setText(p.getString("prompt",
                    "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. " +
                    "Không nhắc rằng bạn là AI."));
            updateStatus();
        }
    }

    private void updateStatus() {
        boolean accessibility = MessageAccessibilityService.isRunning();
        status.setText("\nTrạng thái: " +
                (accessibility ? "Trợ năng OK" : "Chưa bật Trợ năng") +
                " | ChatGPT: ứng dụng" +
                "\nMở Messenger, vào một cuộc hội thoại rồi bật tự động trả lời.");
    }

    private void saveAndApply() {
        final boolean want = enabled.isChecked();
        final boolean autoWant = auto.isChecked();
        final String request = prompt.getText().toString().trim().isEmpty()
                ? "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn. Không nhắc rằng bạn là AI."
                : prompt.getText().toString().trim();

        p.edit()
                .putBoolean("enabled", want)
                .putBoolean("auto", autoWant)
                .putString("prompt", request)
                .remove("api_key")
                .remove("model")
                .remove("key_valid")
                .putBoolean("bubble_hidden", false)
                .apply();

        if (!want) {
            applyServiceState(false, false);
            Toast.makeText(this, "Đã tắt AutoMessenger", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cấp quyền bong bóng nổi trước", Toast.LENGTH_LONG).show();
            return;
        }

        applyServiceState(true, autoWant);
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

        Toast.makeText(this, "AutoMessenger đã chạy nền với ChatGPT", Toast.LENGTH_SHORT).show();
        finish();
    }
}