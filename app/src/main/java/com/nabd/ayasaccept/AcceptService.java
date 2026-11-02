package com.nabd.ayasaccept;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the Ayas lawyers app and grabs new requests as fast as possible.
 *
 *  FAST PATH (app open on home screen, status = متاح):
 *    Each category card (قضية عاجلة / جلسة عاجلة / مراجعة عقد / الأسئلة القانونية) is watched.
 *    The moment a card's number goes up, the card is tapped -> the request opens -> "قبول" is pressed.
 *    No notification round-trip, no cold start.
 *
 *  BACKUP PATH (app in background):
 *    A notification from Ayas has its PendingIntent fired immediately, then the same accept logic runs.
 *
 *  "رفض" is never touched. The Ayas package is learned automatically from any screen showing "EYS-".
 */
public class AcceptService extends AccessibilityService {

    static volatile AcceptService instance;

    private static final String ACCEPT = "قبول";
    private static final String[] CONFIRM_WORDS = {"تأكيد القبول", "تأكيد", "نعم", "موافق"};
    // Home-screen cards to watch. Any label whose number rises triggers an open.
    private static final String[] CARD_LABELS = {
            "قضية عاجلة", "جلسة قضائية عاجلة", "جلسة عاجلة",
            "مراجعة عقد", "الأسئلة القانونية المجانية", "الأسئلة القانونية"
    };

    private static final long POLL_MS = 100;        // backup polling while Ayas is on screen
    private static final long MIN_SCAN_GAP_MS = 25;
    private static final long RECLICK_GAP_MS = 350;
    private static final long CONFIRM_WINDOW_MS = 3000;
    private static final long REFRESH_MS = 4000;     // pull-to-refresh the home screen this often
    private static final long REFRESH_IDLE_MS = 2500; // skip refresh if we just acted

    private final Handler h = new Handler(Looper.getMainLooper());
    private String ayasPkg;
    private boolean polling;
    private long lastScan, lastClick, lastLearnTry, lastDrill, lastDiag;
    private String pendingId, lastAcceptedId, lastDrilledId;
    private int verifyTries;

    private final Map<String, Integer> homeCounts = new HashMap<>();
    private boolean homeBaselined;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!Prefs.enabled(AcceptService.this) || !scan(true)) { polling = false; return; }
            h.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable verify = new Runnable() {
        @Override public void run() { verifyAccepted(); }
    };

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            maybeRefresh();
            h.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        ayasPkg = Prefs.pkg(this);
        Prefs.log(this, "🟢 الخدمة اشتغلت" + (ayasPkg == null
                ? " — افتح أي طلب في إياس مرة عشان أتعرّف على التطبيق" : ""));
        h.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        h.removeCallbacks(refreshTick);
        return super.onUnbind(intent);
    }

    void reloadPkg() {
        ayasPkg = Prefs.pkg(this);
        homeBaselined = false;
        homeCounts.clear();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!Prefs.enabled(this)) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;

        if (e.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (ayasPkg != null && ayasPkg.contentEquals(pkg)) onAyasNotification(e);
            return;
        }

        if (ayasPkg == null) { tryLearn(pkg); return; }
        if (!ayasPkg.contentEquals(pkg)) return;

        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) diag();
        scan(false);
        if (!polling) { polling = true; h.postDelayed(poll, POLL_MS); }
    }

    /** First run: find which installed app is Ayas by spotting a request number "EYS-". */
    private void tryLearn(CharSequence pkg) {
        if (getPackageName().contentEquals(pkg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLearnTry < 400) return;
        lastLearnTry = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        List<AccessibilityNodeInfo> ids = root.findAccessibilityNodeInfosByText("EYS-");
        if (ids != null && !ids.isEmpty()) {
            ayasPkg = root.getPackageName().toString();
            Prefs.get(this).edit().putString(Prefs.K_PKG, ayasPkg).apply();
            Prefs.log(this, "✅ اتعرفت على تطبيق إياس: " + ayasPkg);
        }
    }

    /** Temporary diagnostic: can we actually read the Ayas screen? Logs once per screen change. */
    private void diag() {
        if (!Prefs.diag(this)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDiag < 1500) return;
        lastDiag = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { Prefs.log(this, "👁️ شاشة إياس: مش قادر أقرا المحتوى (فاضي)"); return; }
        int n = countText(root);
        boolean acc = hasExact(root, ACCEPT);
        boolean eys = !isEmpty(root.findAccessibilityNodeInfosByText("EYS-"));
        Prefs.log(this, "👁️ إياس: نصوص=" + n + " · قبول=" + (acc ? "✔" : "✘") + " · EYS=" + (eys ? "✔" : "✘"));
    }

    private static boolean isEmpty(List<AccessibilityNodeInfo> l) { return l == null || l.isEmpty(); }

    private int countText(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        int c = label(node).isEmpty() ? 0 : 1;
        for (int i = 0; i < node.getChildCount(); i++) c += countText(node.getChild(i));
        return c;
    }

    private void onAyasNotification(AccessibilityEvent e) {
        Parcelable data = e.getParcelableData();
        if (!(data instanceof Notification)) return;
        Notification n = (Notification) data;
        String title = n.extras != null ? String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)) : "";
        String text = n.extras != null ? String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)) : "";
        if (Prefs.openNotif(this) && n.contentIntent != null) {
            sendPending(n.contentIntent);
            Prefs.log(this, "🔔 فتحت إشعار: " + title + " — " + text);
        } else {
            Prefs.log(this, "🔔 إشعار: " + title + " — " + text);
        }
    }

    private void sendPending(PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, o.toBundle());
            } else {
                pi.send();
            }
        } catch (Exception ex) {
            Prefs.log(this, "⚠️ معرفتش أفتح الإشعار: " + ex.getClass().getSimpleName());
        }
    }

    /** @return false when Ayas is no longer the active window (stop polling). */
    private boolean scan(boolean fromPoll) {
        long now = SystemClock.uptimeMillis();
        if (!fromPoll && now - lastScan < MIN_SCAN_GAP_MS) return true;
        lastScan = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || ayasPkg == null
                || !ayasPkg.contentEquals(root.getPackageName())) {
            return false;
        }

        // 1) Confirmation dialog right after our click.
        if (now - lastClick < CONFIRM_WINDOW_MS) {
            for (String w : CONFIRM_WORDS) {
                AccessibilityNodeInfo c = findExactEnabled(root, w);
                if (c != null) { click(c); break; }
            }
        }

        // 2) Accept button present and enabled -> press it. Highest priority.
        AccessibilityNodeInfo btn = findExactEnabled(root, ACCEPT);
        if (btn != null) {
            if (now - lastClick >= RECLICK_GAP_MS) {
                lastClick = now;
                click(btn);
                String id = findRequestId(root);
                if (pendingId == null || !pendingId.equals(id)) {
                    pendingId = id;
                    verifyTries = 0;
                    Prefs.log(this, "👆 ضغطت قبول " + (id != null ? id : ""));
                }
                h.removeCallbacks(verify);
                h.postDelayed(verify, 800);
            }
            return true;
        }

        // 3) A request list item (EYS-...) with no accept button yet -> drill into it.
        if (isHome(root)) {
            checkHome(root, now);
        } else {
            drillIntoRequest(root, now);
        }
        return true;
    }

    private boolean isHome(AccessibilityNodeInfo root) {
        for (String label : CARD_LABELS) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(label);
            if (l != null) {
                for (AccessibilityNodeInfo n : l) if (label.equals(label(n))) return true;
            }
        }
        return false;
    }

    /** Home screen: tap a card the instant its number rises. */
    private void checkHome(AccessibilityNodeInfo root, long now) {
        boolean first = !homeBaselined;
        for (String label : CARD_LABELS) {
            AccessibilityNodeInfo labelNode = findExactNode(root, label);
            if (labelNode == null) continue;
            int count = countNear(labelNode);
            if (count < 0) continue;
            Integer prev = homeCounts.get(label);
            homeCounts.put(label, count);
            if (first || prev == null) continue;           // only baseline on first sight
            if (count > prev && count > 0 && now - lastClick >= RECLICK_GAP_MS) {
                AccessibilityNodeInfo card = clickable(labelNode);
                lastClick = now;
                click(card);
                Prefs.log(this, "⚡ طلب جديد في «" + label + "» — فتحته (" + prev + "→" + count + ")");
            }
        }
        homeBaselined = true;
    }

    /** Non-home, non-accept screen (a requests list): open the newest request. */
    private void drillIntoRequest(AccessibilityNodeInfo root, long now) {
        if (now - lastDrill < 500) return;
        AccessibilityNodeInfo item = findClickableContaining(root, "EYS-");
        if (item == null) return;
        String id = idText(item);
        if (id != null && id.equals(lastDrilledId) && now - lastDrill < 2500) return;
        lastDrilledId = id;
        lastDrill = now;
        click(item);
    }

    /** Pull-to-refresh the home screen periodically so new requests show without push. */
    private void maybeRefresh() {
        if (!Prefs.enabled(this) || !Prefs.refresh(this)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastClick < REFRESH_IDLE_MS) return;   // mid-action, don't disturb
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || ayasPkg == null
                || !ayasPkg.contentEquals(root.getPackageName())) return;
        if (!isHome(root)) return;                        // only on the dashboard
        if (findExactEnabled(root, ACCEPT) != null) return;
        swipeDown();
    }

    /** A short vertical swipe from upper area downward = pull to refresh. */
    private void swipeDown() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = dm.widthPixels / 2f;
        float y1 = dm.heightPixels * 0.30f;
        float y2 = dm.heightPixels * 0.72f;
        Path p = new Path();
        p.moveTo(cx, y1);
        p.lineTo(cx, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build(), null, null);
    }

    private AccessibilityNodeInfo findExactEnabled(AccessibilityNodeInfo root, String word) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(word);
        if (list == null) return null;
        for (AccessibilityNodeInfo n : list) {
            if (!word.equals(label(n)) || !n.isEnabled()) continue;
            AccessibilityNodeInfo c = clickable(n);
            if (c != null && c.isEnabled()) return c;
        }
        return null;
    }

    private AccessibilityNodeInfo findExactNode(AccessibilityNodeInfo root, String word) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(word);
        if (list == null) return null;
        for (AccessibilityNodeInfo n : list) if (word.equals(label(n))) return n;
        return null;
    }

    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo root, String needle) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(needle);
        if (list == null) return null;
        for (AccessibilityNodeInfo n : list) {
            if (label(n).contains(needle)) {
                AccessibilityNodeInfo c = clickable(n);
                if (c != null) return c;
            }
        }
        return null;
    }

    /** Climbs to the nearest clickable ancestor (or the node itself as a fallback). */
    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        int depth = 0;
        while (c != null && !c.isClickable() && depth++ < 6) c = c.getParent();
        return c != null ? c : n;
    }

    /** Looks for an integer in the card around this label (up to 3 ancestor levels up). */
    private int countNear(AccessibilityNodeInfo labelNode) {
        AccessibilityNodeInfo p = labelNode;
        for (int up = 0; up < 3 && p != null; up++) {
            int v = firstInt(p);
            if (v >= 0) return v;
            p = p.getParent();
        }
        return -1;
    }

    private int firstInt(AccessibilityNodeInfo node) {
        if (node == null) return -1;
        int v = toInt(label(node));
        if (v >= 0) return v;
        for (int i = 0; i < node.getChildCount(); i++) {
            int c = firstInt(node.getChild(i));
            if (c >= 0) return c;
        }
        return -1;
    }

    /** Parses a pure number (Western 0-9 or Arabic-Indic ٠-٩); -1 if the text isn't a number. */
    private static int toInt(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty() || s.length() > 4) return -1;
        int val = 0; boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d;
            if (ch >= '0' && ch <= '9') d = ch - '0';
            else if (ch >= '٠' && ch <= '٩') d = ch - '٠';
            else return -1;
            val = val * 10 + d; any = true;
        }
        return any ? val : -1;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    private String idText(AccessibilityNodeInfo n) {
        String s = label(n);
        int i = s.indexOf("EYS-");
        if (i < 0) return null;
        int end = i;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(i, end);
    }

    private String findRequestId(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo n = findClickableContaining(root, "EYS-");
        if (n != null) { String s = idText(n); if (s != null) return s; }
        List<AccessibilityNodeInfo> ids = root.findAccessibilityNodeInfosByText("EYS-");
        if (ids != null) for (AccessibilityNodeInfo m : ids) { String s = idText(m); if (s != null) return s; }
        return null;
    }

    private void click(AccessibilityNodeInfo n) {
        if (n == null) return;
        if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(n);
    }

    private void tap(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40)).build(), null, null);
    }

    /** Accepted = the قبول button disappeared after our click. */
    private void verifyAccepted() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean onAyas = root != null && root.getPackageName() != null && ayasPkg != null
                && ayasPkg.contentEquals(root.getPackageName());
        if (onAyas && !hasExact(root, ACCEPT)) {
            String id = pendingId;
            pendingId = null;
            if (id == null || !id.equals(lastAcceptedId)) {
                lastAcceptedId = id;
                Prefs.log(this, "✅ اتقبل " + (id != null ? id : ""));
                if (Prefs.sound(this)) alert(this);
            }
        } else if (pendingId != null && ++verifyTries < 5) {
            h.postDelayed(verify, 700);
        }
    }

    private static boolean hasExact(AccessibilityNodeInfo root, String word) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(word);
        if (list == null) return false;
        for (AccessibilityNodeInfo n : list) if (word.equals(label(n))) return true;
        return false;
    }

    /** Loud alarm sound (~4s) + vibration, AFTER the accept — never before it. */
    static void alert(android.content.Context ctx) {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            final Ringtone r = RingtoneManager.getRingtone(ctx, u);
            if (r != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    r.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                }
                r.play();
                new Handler(Looper.getMainLooper()).postDelayed(r::stop, 4000);
            }
            Vibrator v = (Vibrator) ctx.getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 400, 150, 400, 150, 400}, -1));
        } catch (Exception ignored) { }
    }

    @Override
    public void onInterrupt() { }
}
