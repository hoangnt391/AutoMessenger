package com.hoangnt391.automessenger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {
    private android.content.SharedPreferences p;
    private Switch enabled, auto;
    private EditText prompt;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("AutoMessenger", 0);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 32, 32, 32);

        TextView t = new TextView(this);
        t.setText("AutoMessenger\nAI trả lời tin nhắn");
        t.setTextSize(26);
        l.addView(t);

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

        Button a = new Button(this);
        a.setText("Mở quyền Trợ năng");
        a.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.addView(a);

        Button s = new Button(this);
        s.setText("Lưu cấu hình");
        s.setOnClickListener(v -> {
            p.edit()
                .putBoolean("enabled", enabled.isChecked())
                .putBoolean("auto", auto.isChecked())
                .putString("prompt", prompt.getText().toString().trim())
                .apply();
            Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show();
        });
        l.addView(s);

        TextView i = new TextView(this);
        i.setText("Zalo/Messenger → Trợ năng đọc ngữ cảnh → AI local → điền trả lời → gửi nếu bật tự động.");
        l.addView(i);
        setContentView(l);
    }
}
