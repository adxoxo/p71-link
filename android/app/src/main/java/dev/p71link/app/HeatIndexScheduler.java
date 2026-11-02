package dev.p71link.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import dev.p71link.protocol.HeatIndex;
import dev.p71link.protocol.Notifications;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Davao City heat index: settings, the Open-Meteo request, and the auto send alarm. Every send uses
 * a fresh request made for that send; nothing is cached or reused. Auto send is off until the user
 * turns it on. One attempt per scheduled time, no retries and no catch-up for missed times.
 * Settings and the last auto send result live in their own preferences file, "heat_index".
 */
final class HeatIndexScheduler {
    static final String PREFS = "heat_index";
    static final String ACTION_SEND = "dev.p71link.app.action.HEAT_INDEX_SEND";
    static final String EXTRA_SLOT = "slot";
    /** Inexact delivery window: no exact-alarm permission is needed. */
    static final long WINDOW_MS = 10 * 60_000L;
    /** A time delivered later than this is treated as missed, not sent late. */
    static final long LATE_LIMIT_MS = 30 * 60_000L;
    static final int TIMEOUT_MS = 10_000;
    /** Whole attempt bound, inside the 60 s limit for a background broadcast. */
    static final long DEADLINE_MS = 45_000L;

    private static final String ON = "autoSend", TIMES = "times", TRIED = "triedSlot",
            LAST_SLOT = "lastSlot", LAST_RESULT = "lastResult";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "p71-heat-index");
        t.setDaemon(true);
        return t;
    });

    interface Done { void done(HeatIndex.Reading reading, String error); }

    private HeatIndexScheduler() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean(ON, false); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(ON, on).apply();
        arm(c);
    }

    static List<Integer> times(Context c) { return HeatIndex.parseTimes(prefs(c).getString(TIMES, null)); }

    static void setTimes(Context c, List<Integer> minutes) {
        List<Integer> n = HeatIndex.normalize(minutes);
        if (n.size() < HeatIndex.MIN_TIMES || n.size() > HeatIndex.MAX_TIMES) throw new IllegalArgumentException("times");
        prefs(c).edit().putString(TIMES, HeatIndex.formatTimes(n)).apply();
        arm(c);
    }

    /** Last auto send: scheduled time in ms and its outcome, or null. */
    static long lastSlot(Context c) { return prefs(c).getLong(LAST_SLOT, 0); }

    static String lastResult(Context c) { return prefs(c).getString(LAST_RESULT, null); }

    private static void record(Context c, long slot, String result) {
        prefs(c).edit().putLong(LAST_SLOT, slot).putString(LAST_RESULT, result).apply();
    }

    // ---- Alarm ---------------------------------------------------------------------------------

    private static PendingIntent intent(Context c, long slot, int flags) {
        Intent i = new Intent(c, HeatIndexReceiver.class).setAction(ACTION_SEND).putExtra(EXTRA_SLOT, slot);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | flags);
    }

    /** Set the alarm for the next time after now when auto send is on; otherwise cancel it. */
    static void arm(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        if (!enabled(app)) {
            PendingIntent old = intent(app, 0, PendingIntent.FLAG_NO_CREATE);
            if (old != null) {
                am.cancel(old);
                old.cancel();
            }
            return;
        }
        ZonedDateTime next = HeatIndex.nextSlot(times(app), ZonedDateTime.now(HeatIndex.ZONE));
        if (next == null) return;
        long at = next.toInstant().toEpochMilli();
        am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, intent(app, at, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** Alarm delivery, on the main thread. Always finishes {@code pending}. */
    static void onAlarm(Context c, long slot, BroadcastReceiver.PendingResult pending) {
        Context app = c.getApplicationContext();
        if (!enabled(app)) {
            pending.finish();
            return;
        }
        arm(app); // The next time is set first, so a failure here never stops later sends.
        SharedPreferences p = prefs(app);
        if (slot <= 0 || slot <= p.getLong(TRIED, 0)) { // Duplicate delivery: one attempt per time.
            pending.finish();
            return;
        }
        p.edit().putLong(TRIED, slot).apply();
        if (System.currentTimeMillis() - slot > LATE_LIMIT_MS) {
            record(app, slot, "not sent: the phone delayed the alarm");
            pending.finish();
            return;
        }
        P71App a = P71App.get();
        if (a == null || !a.watch.connected()) {
            record(app, slot, "not sent: watch not connected");
            pending.finish();
            return;
        }
        final boolean[] state = new boolean[2]; // [finished, sending]
        Runnable[] finish = new Runnable[1];
        java.util.function.Consumer<String> complete = result -> {
            if (state[0]) return;
            state[0] = true;
            MAIN.removeCallbacks(finish[0]);
            record(app, slot, result);
            pending.finish();
        };
        finish[0] = () -> complete.accept(state[1] ? "not confirmed: the watch did not answer in time"
                : "failed: Open-Meteo did not answer in time");
        MAIN.postDelayed(finish[0], DEADLINE_MS);
        fetch((reading, error) -> {
            if (state[0]) return; // Too late: nothing is sent after the attempt ended.
            if (error != null) {
                complete.accept("failed: " + error);
                return;
            }
            if (!a.watch.connected()) {
                complete.accept("not sent: watch not connected");
                return;
            }
            state[1] = true;
            a.watch.notifyText(reading.message(), Notifications.SMS, new WatchManager.Result<Void>() {
                @Override public void ok(Void v) { complete.accept("sent"); }

                @Override public void fail(String m) { complete.accept("failed: " + m); }
            });
        });
    }

    // ---- Open-Meteo ----------------------------------------------------------------------------

    /** One fresh request off the main thread; {@code done} runs on the main thread. */
    static void fetch(Done done) {
        IO.execute(() -> {
            HeatIndex.Reading r = null;
            String error;
            try {
                r = HeatIndex.parse(request(), LocalDate.now(HeatIndex.ZONE));
                error = null;
            } catch (Status e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = "could not reach Open-Meteo";
            } catch (IllegalArgumentException e) {
                error = "unusable reply from Open-Meteo (" + e.getMessage() + ")";
            } catch (RuntimeException e) {
                error = "unusable reply from Open-Meteo";
            }
            final HeatIndex.Reading reading = r;
            final String err = error;
            MAIN.post(() -> done.done(reading, err));
        });
    }

    /** A reply that arrived but cannot be used; its message is shown as is. */
    private static final class Status extends IOException {
        Status(String m) { super(m); }
    }

    private static String request() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(HeatIndex.URL).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new Status("Open-Meteo answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > HeatIndex.MAX_REPLY_BYTES) throw new Status("Open-Meteo reply too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** "9:00 AM" for a scheduled time, with the date when it is not today in Davao. */
    static String slotLabel(long slot) {
        ZonedDateTime t = Instant.ofEpochMilli(slot).atZone(HeatIndex.ZONE);
        String clock = HeatIndex.clock12(t.getHour() * 60 + t.getMinute());
        if (t.toLocalDate().equals(LocalDate.now(HeatIndex.ZONE))) return clock;
        return t.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                + " " + t.getDayOfMonth() + ", " + clock;
    }
}
