package com.nabd.ayasaccept;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status, log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        col.setPadding(pad, dp(40), pad, pad);
        col.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("قبول إياس");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1B2A4A"));
        col.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(12));
        col.addView(status);

        col.addView(button("١) فعّل الخدمة من إعدادات إمكانية الوصول", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        col.addView(button("٢) استثني التطبيق من توفير البطارية", v ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));

        col.addView(toggle("التشغيل", Prefs.K_ENABLED, true));
        col.addView(toggle("فتح إشعارات إياس فوراً", Prefs.K_OPEN_NOTIF, true));
        col.addView(toggle("صوت عالي بعد القبول", Prefs.K_SOUND, true));
        col.addView(toggle("تحديث الشاشة الرئيسية كل 4 ثواني", Prefs.K_REFRESH, true));

        col.addView(button("🔊 جرّب الصوت", v -> AcceptService.alert(this)));
        col.addView(button("إعادة التعرّف على تطبيق إياس", v -> {
            Prefs.get(this).edit().remove(Prefs.K_PKG).apply();
            AcceptService s = AcceptService.instance;
            if (s != null) s.reloadPkg();
            Prefs.log(this, "↩️ اتمسح التطبيق المتعرَّف عليه — افتح أي طلب في إياس");
            refresh();
        }));
        col.addView(button("مسح السجل", v -> {
            Prefs.get(this).edit().remove(Prefs.K_LOG).apply();
            refresh();
        }));

        TextView logTitle = new TextView(this);
        logTitle.setText("السجل");
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(16), 0, dp(4));
        col.addView(logTitle);

        log = new TextView(this);
        log.setTextSize(13);
        log.setTextIsSelectable(true);
        log.setBackgroundColor(Color.parseColor("#F3F4F6"));
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        col.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = AcceptService.instance != null;
        String pkg = Prefs.pkg(this);
        status.setText((on ? "🟢 الخدمة شغالة" : "🔴 الخدمة مش متفعّلة — دوس الزرار رقم ١")
                + "\n" + (pkg != null ? "تطبيق إياس: " + pkg
                : "⏳ افتح أي طلب في تطبيق إياس مرة واحدة عشان أتعرّف عليه"));
        String l = Prefs.get(this).getString(Prefs.K_LOG, "");
        log.setText(l.isEmpty() ? "لسه مفيش حاجة" : l);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Switch toggle(String text, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(16);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(Prefs.get(this).getBoolean(key, def));
        s.setOnCheckedChangeListener((btn, checked) ->
                Prefs.get(this).edit().putBoolean(key, checked).apply());
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
