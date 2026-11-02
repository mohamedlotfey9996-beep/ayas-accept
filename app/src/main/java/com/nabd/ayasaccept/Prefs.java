package com.nabd.ayasaccept;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Settings + a small persistent log (survives app restarts, keeps the last ~300 lines). */
final class Prefs {
    static final String FILE = "cfg";
    static final String K_ENABLED = "enabled";
    static final String K_OPEN_NOTIF = "open_notif";
    static final String K_SOUND = "sound";
    static final String K_REFRESH = "refresh";
    static final String K_DIAG = "diag";
    static final String K_PKG = "ayas_pkg";
    static final String K_LOG = "log";
    private static final int MAX_LINES = 300;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return get(c).getBoolean(K_ENABLED, true); }
    static boolean openNotif(Context c) { return get(c).getBoolean(K_OPEN_NOTIF, true); }
    static boolean sound(Context c) { return get(c).getBoolean(K_SOUND, true); }
    static boolean refresh(Context c) { return get(c).getBoolean(K_REFRESH, true); }
    static boolean diag(Context c) { return get(c).getBoolean(K_DIAG, true); }
    static String pkg(Context c) { return get(c).getString(K_PKG, null); }

    static synchronized void log(Context c, String msg) {
        SharedPreferences p = get(c);
        String time = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
        String old = p.getString(K_LOG, "");
        String all = time + "  " + msg + "\n" + old;
        // trim to MAX_LINES
        int idx = -1, n = 0;
        for (int i = 0; i < all.length(); i++) {
            if (all.charAt(i) == '\n' && ++n >= MAX_LINES) { idx = i; break; }
        }
        if (idx > 0) all = all.substring(0, idx + 1);
        p.edit().putString(K_LOG, all).apply();
    }
}
