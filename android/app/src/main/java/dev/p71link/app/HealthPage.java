package dev.p71link.app;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** Health tab. Shows only values that the watch sent and that this phone stored. */
final class HealthPage implements MainActivity.Page {
    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        HealthLog log = P71App.get().health;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.header(a, "Health", null));
        page.addView(Ui.subtitle(a, "Records stay on this phone."));

        Ui.add(page, summary(a, w, log), 0);

        page.addView(Ui.section(a, "Records", "About this data", v -> a.push(new AboutDataPage())));
        LinearLayout records = Ui.group(a);
        Ui.addToGroup(records, Ui.item(a, R.drawable.ic_pulse, "Activity and sleep", "Steps · Distance · Calories · Sleep", null,
                v -> a.push(new ActivityPage())));
        boolean hr = w.caps == null || w.caps.manualHeartRate;
        if (hr) {
            Ui.addToGroup(records, Ui.item(a, R.drawable.ic_heart, "Heart rate", latest(log, "heart_rate", " bpm", "Measure now · History"), null,
                    v -> a.push(new MeasurePage(dev.p71link.protocol.Health.TYPE_HEART_RATE))));
        }
        if (w.caps == null || w.caps.menstruation) {
            Ui.addToGroup(records, Ui.item(a, R.drawable.ic_cycle, "Cycle reminders", "Period dates and reminder", null, v -> a.push(new CyclePage())));
        }
        page.addView(records);

        page.addView(Ui.section(a, "Settings"));
        LinearLayout settings = Ui.group(a);
        Ui.addToGroup(settings, Ui.item(a, R.drawable.ic_target, "Daily goal", "Steps a day", null, v -> a.push(new GoalsPage())));
        Ui.addToGroup(settings, Ui.item(a, R.drawable.ic_auto, "Automatic measurements", "Heart rate schedule", null,
                v -> a.push(new AutoHealthPage())));
        page.addView(settings);
        return page;
    }

    /** Empty, loading, error or latest-saved state, with one primary Read history action. */
    private static View summary(MainActivity a, WatchManager w, HealthLog log) {
        LinearLayout card = Ui.group(a);
        int p = Ui.dp(a, 16);
        card.setPadding(p, p, p, p);
        JSONObject steps = ActivityPage.newest(log, "activity");
        boolean anySaved = steps != null || ActivityPage.newest(log, "sleep") != null || ActivityPage.newest(log, "hr_day") != null
                || log.latest("heart_rate") != null;
        boolean running = HistoryRun.running();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tile(a, R.drawable.ic_pulse, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)));
        LinearLayout texts = Ui.column(a);
        String title, sub;
        if (running) {
            title = "Reading history…";
            sub = HistoryRun.step == null ? "Keep the watch nearby." : HistoryRun.step;
        } else if (steps != null) {
            String n = HealthLog.number(steps.optJSONObject("values"), "steps");
            title = n == null ? "Steps unavailable" : n + " steps";
            sub = ActivityPage.dateOf(steps) + " · from the watch, saved " + when(steps.optLong("time"));
        } else if (anySaved) {
            title = "No activity saved yet";
            sub = "Sleep or heart rate records are saved. Open them below.";
        } else {
            title = "No history yet";
            sub = "Nothing saved on this phone.";
        }
        TextView t = Ui.text(a, title, 20, Ui.TEXT);
        texts.addView(t);
        TextView s = Ui.text(a, sub, 13, Ui.TEXT2);
        s.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        texts.addView(s);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tl.setMarginStart(Ui.dp(a, 14));
        head.addView(texts, tl);
        card.addView(head);

        if (!running && !anySaved) {
            TextView body = Ui.text(a, "Read history from the watch to see activity, sleep and heart rate records.", 14, Ui.TEXT2);
            body.setPadding(0, Ui.dp(a, 12), 0, 0);
            card.addView(body);
        }
        if (!running && HistoryRun.message != null) {
            TextView m = Ui.text(a, HistoryRun.message, 14, HistoryRun.error ? Ui.ERROR : Ui.TEXT2);
            m.setPadding(0, Ui.dp(a, 12), 0, 0);
            m.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(m);
        }
        boolean canRead = w.connected() && !running;
        String label = running ? "Reading history…" : HistoryRun.error ? "Try again" : "Read history";
        Ui.add(card, Ui.button(a, label, true, canRead ? v -> HistoryRun.start(w, log) : null), 14);
        if (!w.connected() && !running) {
            TextView hint = Ui.text(a, "Connect the watch to read history.", 13, Ui.MUTED);
            hint.setPadding(0, Ui.dp(a, 8), 0, 0);
            card.addView(hint);
        }
        return card;
    }

    static String when(long ms) {
        if (ms <= 0) return "time unavailable";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms));
    }

    private static String latest(HealthLog log, String kind, String unit, String fallback) {
        JSONObject o = log.latest(kind);
        if (o == null) return fallback;
        String v = HealthLog.number(o.optJSONObject("values"), "value");
        return "Last " + (v == null ? "unavailable" : v + unit) + " · " + when(o.optLong("time"));
    }
}
