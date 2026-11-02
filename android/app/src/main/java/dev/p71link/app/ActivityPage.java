package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity, heart-rate history, and sleep, synced on request and kept on this phone.
 * Distance and calorie counters show raw watch values because their units are not confirmed.
 * Missing fields show as unavailable; a zero the watch sent shows as zero.
 */
final class ActivityPage implements MainActivity.Page {
    static final String NA = "unavailable";

    static String dateOf(JSONObject record) {
        JSONObject v = record == null ? null : record.optJSONObject("values");
        if (v == null) return "";
        String d = v.optString("date", "");
        return d.isEmpty() ? v.optString("requestedDate", "") : d;
    }

    static JSONObject newest(HealthLog log, String kind) {
        JSONObject best = null;
        for (JSONObject o : log.ofKind(kind)) if (best == null || dateOf(o).compareTo(dateOf(best)) >= 0) best = o;
        return best;
    }

    /** Distance and calories with the Pubu convention when stored; raw counters otherwise. */
    static String counters(JSONObject v, boolean metric) {
        String dist, cal;
        if (v != null && v.opt("distanceKm") instanceof Number) {
            double km = v.optDouble("distanceKm");
            dist = metric ? String.format(java.util.Locale.getDefault(), "%.2f km", km)
                    : String.format(java.util.Locale.getDefault(), "%.2f mi (converted)", km / 1.609344);
        } else {
            String n = HealthLog.number(v, "distanceRaw");
            dist = n == null ? "distance " + NA : "distance " + n + " (raw)";
        }
        if (v != null && v.opt("caloriesKcal") instanceof Number) {
            cal = String.format(java.util.Locale.getDefault(), "%.0f kcal", v.optDouble("caloriesKcal"));
        } else {
            String n = HealthLog.number(v, "caloriesRaw");
            cal = n == null ? "calories " + NA : "calories " + n + " (raw)";
        }
        return dist + " · " + cal;
    }

    /**
     * Watch-classified durations and their phone-calculated sums. Unlabelled time and a stage sum that
     * differs from the recorded interval are shown, so light + deep is never presented as the whole night.
     */
    static String sleepText(JSONObject v) {
        if (!HealthLog.hasNumber(v, "asleepMinutes")) return "Stage summary unavailable";
        long asleep = v.optLong("asleepMinutes");
        long unlabelled = HealthLog.hasNumber(v, "unlabelledStageMinutes") ? v.optLong("unlabelledStageMinutes") : -1;
        StringBuilder b = new StringBuilder();
        if (asleep == 0 && unlabelled > 0) b.append("Light and deep: none classified");
        else b.append("Asleep ").append(hm(asleep)).append(" (light + deep)");
        if (HealthLog.hasNumber(v, "awakeMinutes")) b.append(" · awake ").append(hm(v.optLong("awakeMinutes")));
        if (unlabelled > 0) b.append(" · unlabelled stages ").append(hm(unlabelled));
        else if (unlabelled < 0) b.append(" · unlabelled time unavailable");
        if (HealthLog.hasNumber(v, "stageMinutesTotal")) {
            long sum = v.optLong("stageMinutesTotal");
            if (HealthLog.hasNumber(v, "deviceIntervalMinutes")) {
                long interval = v.optLong("deviceIntervalMinutes");
                if (sum != interval) b.append(" · stages add up to ").append(hm(sum)).append(" of a ").append(hm(interval))
                        .append(" recorded span (partial or inconsistent record)");
            } else {
                b.append(" · stages add up to ").append(hm(sum)).append("; recorded span not determinable");
            }
        }
        return b.toString();
    }

    static String hm(long minutes) { return minutes / 60 + " h " + minutes % 60 + " min"; }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        HealthLog log = P71App.get().health;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Activity and sleep"));
        page.addView(Ui.subtitle(a, "History comes from the watch when you sync. Nothing syncs on its own."));

        // The same app-wide sync as Health > Read history; one run at a time.
        boolean syncing = HistoryRun.running();
        Ui.add(page, Ui.button(a, syncing ? "Syncing… " + (HistoryRun.step == null ? "" : HistoryRun.step) : "Sync history", !syncing,
                syncing ? null : v -> HistoryRun.start(w, log)), 0);
        if (!syncing && HistoryRun.message != null) {
            page.addView(Ui.status(a, HistoryRun.message, HistoryRun.error ? Ui.ERROR : Ui.TEXT2));
        }

        page.addView(Ui.section(a, "Steps by day"));
        List<JSONObject> days = new ArrayList<>(log.ofKind("activity"));
        days.sort((x, y) -> dateOf(y).compareTo(dateOf(x)));
        if (days.isEmpty()) page.addView(Ui.status(a, "No activity saved yet.", Ui.TEXT2));
        int shown = 0;
        for (JSONObject o : days) {
            if (shown++ >= 14) break;
            JSONObject v = o.optJSONObject("values");
            String steps = HealthLog.number(v, "steps");
            String title = dateOf(o) + "   " + (steps == null ? "steps " + NA : steps + " steps");
            String sub = counters(v, w.timeFormat == null || w.timeFormat.metric);
            if (v != null && v.optString("derivation").startsWith("sums")) sub += " · daily sums calculated on this phone";
            Ui.add(page, Ui.row(a, title, sub, null, null), shown == 1 ? 0 : 8);
        }

        page.addView(Ui.section(a, "Heart rate"));
        JSONObject hr = newest(log, "hr_day");
        if (hr == null) {
            page.addView(Ui.status(a, "No heart-rate history saved yet.", Ui.TEXT2));
        } else {
            JSONObject v = hr.optJSONObject("values");
            JSONArray s = v == null ? null : v.optJSONArray("samples");
            int min = 0, max = 0, last = 0, n = 0;
            for (int i = 0; s != null && i < s.length(); i++) {
                Object x = s.opt(i);
                if (!(x instanceof Number)) continue;
                int b = ((Number) x).intValue();
                if (b <= 0) continue; // 0 marks a slot without a sample.
                min = n == 0 ? b : Math.min(min, b);
                max = Math.max(max, b);
                last = b;
                n++;
            }
            String text = n == 0 ? "No samples in the watch reply"
                    : "Calculated from " + n + " samples: lowest " + min + ", highest " + max + ", latest " + last + " bpm";
            String every = HealthLog.number(v, "minutesPerSample");
            Ui.add(page, Ui.row(a, "Requested day " + dateOf(hr), text + (every == null ? "" : " · one slot every " + every + " min"),
                    null, null), 0);
        }
        List<JSONObject> manual = new ArrayList<>(log.ofKind("heart_rate"));
        if (!manual.isEmpty()) {
            JSONObject m = manual.get(manual.size() - 1);
            String value = HealthLog.number(m.optJSONObject("values"), "value");
            Ui.add(page, Ui.row(a, "Latest manual reading", (value == null ? NA : value + " bpm") + " · " + HealthPage.when(m.optLong("time")),
                    null, null), 8);
        }

        page.addView(Ui.section(a, "Sleep"));
        JSONObject sl = newest(log, "sleep");
        if (sl == null) {
            page.addView(Ui.status(a, "No sleep saved yet.", Ui.TEXT2));
        } else {
            JSONObject v = sl.optJSONObject("values");
            String range = v == null ? NA : v.optString("start", NA) + " to " + v.optString("end", NA);
            String text = sleepText(v) + " · recorded " + range;
            Ui.add(page, Ui.row(a, "Night ending " + dateOf(sl), text, null, null), 0);
        }
        page.addView(Ui.note(a, "Kilometres and kilocalories follow the Pubu Wear display convention for these watch counters. "
                + "Sleep stages are the watch's own classification. Totals are added up on this phone. These are not medical measurements."));
        return page;
    }
}
