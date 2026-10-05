package com.hoangnt391.automessenger;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 9001;
    private static final String PREFS = "AutoMessenger";
    private static final String PROMPTS = "saved_prompts";
    private static final String DEFAULT_PROMPT =
            "Trả lời bằng tiếng Việt, tự nhiên, thân thiện và tinh tế. " +
            "Dựa vào ngữ cảnh để tạo câu trả lời phù hợp. Không ép buộc, không gây áp lực " +
            "và luôn tôn trọng cảm xúc cũng như quyền lựa chọn của người nhận.";

    private android.content.SharedPreferences p;
    private Switch enabled, auto;
    private TextView status, selectedPrompt;
    private LinearLayout promptList;
    private EditText directInput;
    private EditText ashnaKeyInput;
    private EditText ashnaModelInput;
    private TextView directResult;
    private TextView errorLog;
    private String activePromptName = "Mặc định";
    private String activePromptText = DEFAULT_PROMPT;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(PREFS, 0);
        loadActivePrompt();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 18, 22, 22);
        root.setBackgroundColor(Color.rgb(247,245,250));

        TextView title = tv("💬 AutoMessenger AI", 28, 0xFF4A2B72, true);
        root.addView(title);
        TextView subtitle = tv("Bong bóng chat • Prompt • Chat AI trực tiếp", 14, 0xFF6F6878, false);
        subtitle.setPadding(4, 2, 4, 14);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // ===== AUTOMATION =====
        LinearLayout autoCard = card();
        autoCard.addView(tv("⚡ Tự động trả lời", 19, 0xFF27212E, true));

        enabled = new Switch(this);
        enabled.setText("Bật dịch vụ nền / bong bóng");
        enabled.setChecked(p.getBoolean("enabled", false));
        autoCard.addView(enabled);

        auto = new Switch(this);
        auto.setText("Tự động xử lý tin nhắn mới");
        auto.setChecked(p.getBoolean("auto", false));
        autoCard.addView(auto);

        selectedPrompt = tv("", 15, 0xFF4A2B72, true);
        selectedPrompt.setPadding(0, 10, 0, 8);
        autoCard.addView(selectedPrompt);
        refreshSelectedPrompt();

        Button bubble = button("💬 Hiện / mở bong bóng");
        bubble.setOnClickListener(v -> {
            p.edit().putBoolean("bubble_hidden", false).apply();
            AutoMessengerService.requestShowBubble();
            Toast.makeText(this, "Đã yêu cầu hiện bong bóng ✓", Toast.LENGTH_SHORT).show();
        });
        autoCard.addView(bubble);
        content.addView(autoCard);

        // ===== PROMPTS =====
        LinearLayout promptCard = card();
        promptCard.addView(tv("📝 Prompt mẫu", 19, 0xFF27212E, true));
        promptCard.addView(tv("Prompt là mẫu/luật trả lời được lưu lại. Khi có tin nhắn mới, app kết hợp prompt + nội dung tin nhắn + ngữ cảnh rồi gửi cho AI.", 13, 0xFF6F6878, false));

        Button addPrompt = button("＋ Thêm prompt mới");
        addPrompt.setOnClickListener(v -> showPromptEditor(null, null));
        promptCard.addView(addPrompt);

        promptList = new LinearLayout(this);
        promptList.setOrientation(LinearLayout.VERTICAL);
        promptCard.addView(promptList);
        content.addView(promptCard);

        // ===== ASHNAAI =====
        LinearLayout aiCard = card();
        aiCard.addView(tv("🤖 AshnaAI", 19, 0xFF27212E, true));
        aiCard.addView(tv("Kết nối API trực tiếp, chạy nền không mở tab AI.", 13, 0xFF6F6878, false));

        ashnaKeyInput = new EditText(this);
        ashnaKeyInput.setHint("AshnaAI API key (sk-...)");
        ashnaKeyInput.setSingleLine(true);
        ashnaKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        ashnaKeyInput.setText(p.getString("ashna_api_key", ""));
        aiCard.addView(ashnaKeyInput);

        ashnaModelInput = new EditText(this);
        ashnaModelInput.setHint("Model ID, ví dụ: gpt-6.1-sol");
        ashnaModelInput.setSingleLine(true);
        ashnaModelInput.setText(p.getString("ashna_model", "gpt-6.1-sol"));
        aiCard.addView(ashnaModelInput);

        errorLog = tv("Chưa có lỗi.", 13, 0xFF7A1F1F, false);
        errorLog.setTextIsSelectable(true);
        errorLog.setLongClickable(true);
        errorLog.setPadding(14, 12, 14, 12);
        errorLog.setBackground(roundBg(0xFFFFF1F1, 18));
        aiCard.addView(errorLog);

        Button copyError = button("📋 Sao chép lỗi");
        copyError.setOnClickListener(v -> copyText(errorLog.getText().toString(), "Đã sao chép lỗi ✓"));
        aiCard.addView(copyError);

        Button testAshna = button("🔌 Kiểm tra kết nối AshnaAI");
        testAshna.setOnClickListener(v -> testAshnaConnection());
        aiCard.addView(testAshna);
        content.addView(aiCard);

        // ===== DIRECT AI CHAT =====
        LinearLayout chatCard = card();
        chatCard.addView(tv("🤖 Hỏi AI trực tiếp", 19, 0xFF27212E, true));
        chatCard.addView(tv("Khu vực này độc lập với luồng tự động. Nhập câu hỏi bất kỳ để hỏi AshnaAI qua API trực tiếp.", 13, 0xFF6F6878, false));

        directInput = new EditText(this);
        directInput.setHint("Ví dụ: Phân tích câu này hoặc viết một câu trả lời...");
        directInput.setMinLines(3);
        directInput.setGravity(Gravity.TOP);
        directInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatCard.addView(directInput);

        Button ask = button("➤ Gửi cho AshnaAI");
        ask.setOnClickListener(v -> askDirect());
        chatCard.addView(ask);

        directResult = tv("Chưa có câu trả lời.", 15, 0xFF302B36, false);
        directResult.setPadding(14, 12, 14, 12);
        directResult.setBackground(roundBg(0xFFF0EDF5, 18));
        chatCard.addView(directResult);

        Button copyQuestion = button("📋 Sao chép câu hỏi");
        copyQuestion.setOnClickListener(v -> copyText(directInput.getText().toString(), "Đã sao chép câu hỏi ✓"));
        chatCard.addView(copyQuestion);

        Button copyAnswer = button("📋 Sao chép câu trả lời");
        copyAnswer.setOnClickListener(v -> copyText(directResult.getText().toString(), "Đã sao chép câu trả lời ✓"));
        chatCard.addView(copyAnswer);
        content.addView(chatCard);

        // ===== PERMISSIONS / SAVE =====
        LinearLayout settingsCard = card();
        settingsCard.addView(tv("🔐 Quyền & cài đặt", 19, 0xFF27212E, true));

        Button overlay = button("Quyền bong bóng nổi");
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else Toast.makeText(this, "Đã có quyền bong bóng ✓", Toast.LENGTH_SHORT).show();
        });
        settingsCard.addView(overlay);

        Button accessibility = button("Trợ năng • Messenger / ứng dụng chat");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        settingsCard.addView(accessibility);

        Button save = button("LƯU & CHẠY");
        save.setTextSize(16);
        save.setOnClickListener(v -> saveAndApply());
        settingsCard.addView(save);

        status = tv("", 13, 0xFF5F5866, false);
        status.setPadding(4, 12, 4, 4);
        settingsCard.addView(status);
        content.addView(settingsCard);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        refreshPromptList();
        updateStatus();
    }

    private TextView tv(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(2, 6, 2, 6);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(20, 16, 20, 20);
        c.setBackground(roundBg(Color.WHITE, 24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, 14);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void refreshSelectedPrompt() {
        if (selectedPrompt != null) {
            selectedPrompt.setText("⭐ Prompt đang dùng: " + activePromptName);
        }
    }

    private void loadActivePrompt() {
        activePromptName = p.getString("active_prompt_name", "Mặc định");
        activePromptText = p.getString("active_prompt_text", DEFAULT_PROMPT);
    }

    private void refreshPromptList() {
        if (promptList == null) return;
        promptList.removeAllViews();
        List<String[]> items = readPrompts();
        if (items.isEmpty()) items.add(new String[]{"Mặc định", DEFAULT_PROMPT});

        for (String[] item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 6, 0, 6);

            TextView name = tv((item[0].equals(activePromptName) ? "⭐ " : "") + item[0], 15, 0xFF302B36, true);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

            Button use = button("Dùng");
            use.setOnClickListener(v -> {
                activePromptName = item[0];
                activePromptText = item[1];
                p.edit().putString("active_prompt_name", activePromptName)
                        .putString("active_prompt_text", activePromptText).apply();
                refreshSelectedPrompt();
                refreshPromptList();
                Toast.makeText(this, "Đã chọn prompt: " + activePromptName, Toast.LENGTH_SHORT).show();
            });
            row.addView(use);

            Button edit = button("Sửa");
            edit.setOnClickListener(v -> showPromptEditor(item[0], item[1]));
            row.addView(edit);

            if (!item[0].equals("Mặc định")) {
                Button del = button("Xóa");
                del.setOnClickListener(v -> deletePrompt(item[0]));
                row.addView(del);
            }
            promptList.addView(row);
        }
    }

    private List<String[]> readPrompts() {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(PROMPTS, "[]"));
            for (int i=0;i<a.length();i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("name"), o.optString("text")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void savePrompts(List<String[]> items) {
        JSONArray a = new JSONArray();
        for (String[] x : items) {
            JSONObject o = new JSONObject();
            try { o.put("name", x[0]); o.put("text", x[1]); } catch (Exception ignored) {}
            a.put(o);
        }
        p.edit().putString(PROMPTS, a.toString()).apply();
    }

    private void showPromptEditor(String oldName, String oldText) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(10, 4, 10, 0);

        EditText name = new EditText(this);
        name.setHint("Tên prompt");
        name.setText(oldName == null ? "" : oldName);
        box.addView(name);

        EditText text = new EditText(this);
        text.setHint("Mẫu / luật trả lời...");
        text.setMinLines(6);
        text.setGravity(Gravity.TOP);
        text.setText(oldText == null ? DEFAULT_PROMPT : oldText);
        box.addView(text);

        AlertDialogBuilder.show(this, oldName == null ? "Thêm prompt" : "Sửa prompt", box,
                "Lưu", () -> {
                    String n = name.getText().toString().trim();
                    String tx = text.getText().toString().trim();
                    if (n.isEmpty() || tx.isEmpty()) {
                        Toast.makeText(this, "Tên và nội dung prompt không được trống", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String[]> items = readPrompts();
                    if (oldName != null) {
                        for (String[] x : items) if (x[0].equals(oldName)) { x[0]=n; x[1]=tx; }
                    } else items.add(new String[]{n, tx});
                    savePrompts(items);
                    if (oldName != null && oldName.equals(activePromptName)) {
                        activePromptName=n; activePromptText=tx;
                        p.edit().putString("active_prompt_name", n).putString("active_prompt_text", tx).apply();
                    }
                    refreshPromptList();
                    refreshSelectedPrompt();
                });
    }

    private void deletePrompt(String name) {
        ArrayList<String[]> items = new ArrayList<>();
        for (String[] x : readPrompts()) if (!x[0].equals(name)) items.add(x);
        savePrompts(items);
        if (name.equals(activePromptName)) {
            activePromptName="Mặc định"; activePromptText=DEFAULT_PROMPT;
            p.edit().putString("active_prompt_name", activePromptName)
                    .putString("active_prompt_text", activePromptText).apply();
        }
        refreshPromptList();
        refreshSelectedPrompt();
        Toast.makeText(this, "Đã xóa prompt", Toast.LENGTH_SHORT).show();
    }

    private void testAshnaConnection() {
        final String key = ashnaKeyInput == null ? "" : ashnaKeyInput.getText().toString().trim();
        final String model = ashnaModelInput == null ? "gpt-6.1-sol" : ashnaModelInput.getText().toString().trim();
        if (key.isEmpty()) {
            if (errorLog != null) errorLog.setText("❌ Chưa nhập AshnaAI API key.");
            Toast.makeText(this, "Nhập AshnaAI API key trước", Toast.LENGTH_SHORT).show();
            return;
        }
        p.edit().putString("ashna_api_key", key)
                .putString("ashna_model", model.isEmpty() ? "gpt-6.1-sol" : model).apply();
        if (errorLog != null) errorLog.setText("⏳ Đang kiểm tra AshnaAI...");
        Toast.makeText(this, "Đang kiểm tra AshnaAI...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                AiClient.validateKey(key, model);
                runOnUiThread(() -> {
                    if (errorLog != null) errorLog.setText("✅ AshnaAI kết nối OK.");
                    Toast.makeText(this, "AshnaAI kết nối OK ✓", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    String err = "❌ AshnaAI lỗi:\n" + String.valueOf(e.getMessage());
                    if (errorLog != null) errorLog.setText(err);
                    Toast.makeText(this, "AshnaAI lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void askDirect() {
        final String q = directInput.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "Nhập câu hỏi trước", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MessageAccessibilityService.isRunning()) {
            directResult.setText("⚠️ Chưa kết nối Trợ năng. Chat trực tiếp cũng dùng Trợ năng để tự động đọc/gửi tin nhắn; AI chạy trực tiếp qua AshnaAI API.");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Cần bật Trợ năng")
                    .setMessage("Vào Cài đặt > Trợ năng > AutoMessenger và bật dịch vụ. Sau đó quay lại đây và gửi câu hỏi.")
                    .setNegativeButton("Để sau", null)
                    .setPositiveButton("Mở cài đặt", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return;
        }
        directResult.setText("🤖 Đang gửi câu hỏi sang AshnaAI...");
        executor.execute(() -> {
            try {
                String answer = AiClient.reply(p.getString("ashna_api_key", ""), p.getString("ashna_model", "gpt-4o-mini"), "Trả lời trực tiếp câu hỏi của người dùng. " +
                        "Không tự ý gửi câu trả lời sang người khác. Trả lời rõ ràng, hữu ích.", q);
                runOnUiThread(() -> directResult.setText(answer == null || answer.trim().isEmpty()
                        ? "Không đọc được câu trả lời từ AshnaAI." : answer));
            } catch (Exception e) {
                runOnUiThread(() -> { String err = "❌ Lỗi AshnaAI:\n" + String.valueOf(e.getMessage()); directResult.setText(err); if (errorLog != null) errorLog.setText(err); });
            }
        });
    }

    private void copyText(String s, String toast) {
        ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("AutoMessenger", s));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText("Trạng thái: " +
                (MessageAccessibilityService.isRunning() ? "Trợ năng OK" : "Chưa bật Trợ năng") +
                " • Prompt: " + activePromptName +
                " • AI: AshnaAI API");
    }

    private void saveAndApply() {
        boolean want = enabled.isChecked();
        boolean autoWant = auto.isChecked();

        String key = ashnaKeyInput == null ? "" : ashnaKeyInput.getText().toString().trim();
        String model = ashnaModelInput == null ? "gpt-4o-mini" : ashnaModelInput.getText().toString().trim();
        p.edit().putString("ashna_api_key", key).putString("ashna_model", model.isEmpty() ? "gpt-4o-mini" : model)
                .putBoolean("enabled", want).putBoolean("auto", autoWant)
                .putString("prompt", activePromptText).putString("active_prompt_name", activePromptName)
                .putString("active_prompt_text", activePromptText).putBoolean("bubble_hidden", false).apply();

        if (!want) {
            stopService(new Intent(this, AutoMessengerService.class));
            Toast.makeText(this, "Đã tắt AutoMessenger", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cấp quyền bong bóng nổi trước", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
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
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) {
            p = getSharedPreferences(PREFS, 0);
            enabled.setChecked(p.getBoolean("enabled", false));
            auto.setChecked(p.getBoolean("auto", false));
            loadActivePrompt();
            refreshSelectedPrompt();
            refreshPromptList();
            updateStatus();
        }
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // Small helper so the screen stays self-contained without XML layouts.
    static class AlertDialogBuilder {
        static void show(Activity a, String title, View view, String positive, Runnable action) {
            final android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                    .setTitle(title).setView(view).setNegativeButton("Hủy", null)
                    .setPositiveButton(positive, null).create();
            d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> { action.run(); d.dismiss(); }));
            d.show();
        }
    }
}
