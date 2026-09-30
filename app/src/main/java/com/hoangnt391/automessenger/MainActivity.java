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
    private EditText prompt;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("AutoMessenger", 0);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32,32,32,32);

        TextView title = new TextView(this);
        title.setText("AutoMessenger\nChạy nền + bong bóng chat");
        title.setTextSize(25);
        l.addView(title);

        enabled = new Switch(this);
        enabled.setText("Bật AutoMessenger");
        enabled.setChecked(p.getBoolean("enabled", false));
        l.addView(enabled);

        auto = new Switch(this);
        auto.setText("Tự động gửi câu trả lời");
        auto.setChecked(p.getBoolean("auto", false));
        l.addView(auto);

        prompt = new EditText(this);
        prompt.setHint("Yêu cầu cho AI");
        prompt.setText(p.getString("prompt",
                "Trả lời bằng tiếng Việt, tự nhiên, thân thiện, ngắn gọn."));
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
        accessibility.setText("Mở quyền Trợ năng");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.addView(accessibility);

        Button save = new Button(this);
        save.setText("Lưu cấu hình");
        save.setOnClickListener(v -> saveAndApply());
        l.addView(save);

        TextView info = new TextView(this);
        info.setText("\nKhi BẬT: Android sẽ hỏi quyền ghi/chia sẻ màn hình một lần. " +
                "Sau đó AutoMessenger chạy nền và hiện bong bóng.\n\n" +
                "Khi TẮT: dừng foreground service và dừng MediaProjection.");
        l.addView(info);

        setContentView(l);
    }

    private void saveAndApply() {
        final boolean want = enabled.isChecked();
        p.edit()
                .putBoolean("auto", auto.isChecked())
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