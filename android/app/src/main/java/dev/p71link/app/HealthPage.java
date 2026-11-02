package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

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
        page.addView(Ui.title(a, "Health"));
        page.addView(Ui.subtitle(a, "Your data stays on your phone."));

        LinearLayout card = Ui.card(a, Ui.CARD);
        card.addView(Ui.text(a, "ACTIVITY & SLEEP", 12, Ui.MUTED));
        JSONObject steps = ActivityPage.newest(log, "activity");
        if (steps == null) {
            card.addView(Ui.text(a, "No data yet", 26, Ui.TEXT));
            card.addView(Ui.text(a, "Sync from your watch to view history.", 14, Ui.TEXT2));
        } else {
            String n = HealthLog.number(steps.optJSONObject("values"), "steps");
            card.addView(Ui.text(a, n == null ? "Steps unavailable" : n + " steps", 26, Ui.TEXT));
            card.addView(Ui.text(a, ActivityPage.dateOf(steps) + " · saved " + when(steps.optLong("time")), 14, Ui.TEXT2));
        }
        Ui.add(page, card, 0);

        Ui.add(page, Ui.row(a, "Activity and sleep", "Steps · Distance · Calories · Sleep", null,
                v -> a.push(new ActivityPage())), 16);
        boolean hr = w.caps == null || w.caps.manualHeartRate;
        if (hr) Ui.add(page, Ui.row(a, "Heart rate", latest(log, "heart_rate", " bpm", "Measure now · History"), null,
                v -> a.push(new MeasurePage(dev.p71link.protocol.Health.TYPE_HEART_RATE))), 10);
        if (w.caps == null || w.caps.menstruation) {
            Ui.add(page, Ui.row(a, "Cycle reminders", "Period dates and reminder", null, v -> a.push(new CyclePage())), 10);
        }
        Ui.add(page, Ui.row(a, "Daily goal", "Steps a day", null, v -> a.push(new GoalsPage())), 10);
        Ui.add(page, Ui.row(a, "Automatic measurements", "Heart rate schedule", null,
                v -> a.push(new AutoHealthPage())), 10);
        page.addView(Ui.note(a, "History sync runs when you request it."));
        return page;
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
