package com.hoangnt391.automessenger;
import android.app.*;import android.os.*;import android.content.*;import android.provider.Settings;import android.text.InputType;import android.widget.*;
public class MainActivity extends Activity{
 android.content.SharedPreferences p; EditText key,prompt; Switch enabled,auto;
 public void onCreate(Bundle b){super.onCreate(b);p=getSharedPreferences("AutoMessenger",0);LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(32,32,32,32);
 TextView t=new TextView(this);t.setText("AutoMessenger\nAI trả lời tin nhắn");t.setTextSize(26);l.addView(t);
 enabled=new Switch(this);enabled.setText("Bật AutoMessenger");enabled.setChecked(p.getBoolean("enabled",false));l.addView(enabled);
 auto=new Switch(this);auto.setText("Tự động gửi câu trả lời");auto.setChecked(p.getBoolean("auto",false));l.addView(auto);
 key=new EditText(this);key.setHint("OpenAI API key");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(p.getString("key",""));l.addView(key);
 prompt=new EditText(this);prompt.setHint("Phong cách trả lời");prompt.setText(p.getString("prompt","Trả lời bằng tiếng Việt, lịch sự, ngắn gọn."));l.addView(prompt);
 Button a=new Button(this);a.setText("1. Mở quyền Trợ năng");a.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));l.addView(a);
 Button s=new Button(this);s.setText("2. Lưu cấu hình");s.setOnClickListener(v->{p.edit().putBoolean("enabled",enabled.isChecked()).putBoolean("auto",auto.isChecked()).putString("key",key.getText().toString().trim()).putString("prompt",prompt.getText().toString().trim()).apply();Toast.makeText(this,"Đã lưu",Toast.LENGTH_SHORT).show();});l.addView(s);
 TextView i=new TextView(this);i.setText("Luồng: tin nhắn mới → đọc màn hình → AI → điền câu trả lời → gửi nếu bật tự động.");l.addView(i);setContentView(l);}
}