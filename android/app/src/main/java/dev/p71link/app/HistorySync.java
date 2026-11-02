package dev.p71link.app;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.Health;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * User-requested history sync. Steps run one at a time.
 *
 * Data rules:
 * - Distance and calorie counters keep raw watch values under "...Raw" names; their units are not confirmed.
 * - Each record names where its date came from and whether any value was calculated on this phone.
 * - A step reports "saved" only after the local write succeeded. A failed write stops the sync.
 */
final class HistorySync {
    interface Listener {
        void progress(String step);

        void done(List<String> saved, String failure);
    }

    static final int DAYS = 7;
    static final String UNITS_UNRESOLVED = "distanceRaw and caloriesRaw are raw watch counters";
    /** Pubu display convention: distance counters are metres, calorie counters are thousandths of kcal. */
    static final String CONVENTION = "distanceKm = distance / 1000 and caloriesKcal = calories / 1000 follow the Pubu Wear "
            + "display convention; not checked against physical measurements";
    private final WatchManager w;
    private final HealthLog log;
    private final Listener listener;
    private final List<Runnable> steps = new ArrayList<>();
    private final List<String> saved = new ArrayList<>();
    private int next;
    private boolean stopped;

    HistorySync(WatchManager w, HealthLog log, Listener listener) {
        this.w = w;
        this.log = log;
        this.listener = listener;
        steps.add(this::today);
        for (int d = 1; d < DAYS; d++) {
            final int day = d;
            steps.add(() -> pastDay(day));
        }
        steps.add(this::heartRateToday);
        steps.add(this::manualHeartRate);
        if (w.caps == null || w.caps.newSleepProtocol) steps.add(this::sleep);
    }

    void start() { advance(); }

    private void advance() {
        if (stopped) return;
        if (next >= steps.size()) {
            listener.done(saved, null);
            return;
        }
        steps.get(next++).run();
    }

    private void fail(String what, String m) {
        stopped = true;
        listener.done(saved, what + ": " + m);
    }

    /** Store, then continue; a failed local write ends the sync honestly. */
    private boolean store(String what, String kind, String key, long time, JSONObject values, String source) {
        if (log.upsert(kind, key, time, values, source)) return true;
        fail(what, "Could not save on this phone. Nothing from this step was stored.");
        return false;
    }

    private void markSaved(String label) { if (!saved.contains(label)) saved.add(label); }

    private void today() {
        listener.progress("Today's activity");
        w.readToday(new WatchManager.Result<Health.DayActivity>() {
            @Override public void ok(Health.DayActivity d) {
                LocalDate day = LocalDate.of(d.year, d.month, d.day);
                JSONObject o;
                try {
                    o = new JSONObject().put("date", day.toString())
                            .put("dateSource", "phone date at sync; the watch reply carries no date")
                            .put("steps", d.steps).put("runningSteps", d.runningSteps)
                            .put("caloriesRaw", d.calories).put("distanceRaw", d.distance)
                            .put("activeTimeRaw", d.activeSeconds / 60).put("activeTimeNote", "the vendor app reads this field as minutes")
                            .put("distanceKm", d.distance / 1000.0).put("caloriesKcal", d.calories / 1000.0)
                            .put("derivation", "direct watch counters (48)").put("units", UNITS_UNRESOLVED)
                            .put("scale", CONVENTION);
                } catch (JSONException e) {
                    HistorySync.this.fail("Today's activity", "Could not save on this phone.");
                    return;
                }
                if (!store("Today's activity", "activity", day.toString(), System.currentTimeMillis(), o, "watch today counters")) return;
                markSaved("today's activity");
                advance();
            }

            @Override public void fail(String m) { HistorySync.this.fail("Today's activity", m); }
        });
    }

    /** Past days: quarter-hour records (43). Daily figures are sums calculated on this phone. */
    private void pastDay(int daysAgo) {
        listener.progress("Activity, " + daysAgo + (daysAgo == 1 ? " day" : " days") + " ago");
        w.readHistory("step detail", Health.readStepDetail(daysAgo, 0, 95), new Health.StepDetailReader(),
                new WatchManager.Result<Health.StepDetailReader>() {
                    @Override public void ok(Health.StepDetailReader r) {
                        if (r.empty() || r.slots().isEmpty()) {
                            advance();
                            return;
                        }
                        long steps = 0, calories = 0, distance = 0;
                        JSONArray quarters = new JSONArray();
                        Health.StepSlot first = r.slots().get(0);
                        LocalDate day;
                        try {
                            day = LocalDate.of(first.year, first.month, first.day);
                        } catch (RuntimeException e) {
                            HistorySync.this.fail("Daily activity", "The watch sent an invalid date. Nothing was saved.");
                            return;
                        }
                        JSONObject o;
                        try {
                            for (Health.StepSlot q : r.slots()) {
                                steps += q.steps;
                                calories += q.caloriesRaw();
                                distance += q.distance;
                                quarters.put(new JSONArray().put(q.quarter).put(q.steps).put(q.caloriesRaw()).put(q.distance));
                            }
                            // The quarter-hour calorie field is scaled by 10 once when the transfer header says so.
                            long normalized = first.calorieTimesTenFlag ? calories * 10 : calories;
                            o = new JSONObject().put("date", day.toString()).put("dateSource", "watch record date")
                                    .put("steps", steps).put("caloriesRaw", calories).put("distanceRaw", distance)
                                    .put("caloriesNormalized", normalized)
                                    .put("distanceKm", distance / 1000.0).put("caloriesKcal", normalized / 1000.0)
                                    .put("scale", CONVENTION + "; caloriesNormalized applies the transfer's x10 flag once")
                                    .put("derivation", "sums of watch quarter-hour records, calculated on this phone")
                                    .put("calorieTimesTenFlag", first.calorieTimesTenFlag)
                                    .put("quarterRecords", quarters)
                                    .put("quarterFields", "quarter index, steps, calories raw, distance raw")
                                    .put("units", UNITS_UNRESOLVED);
                        } catch (JSONException e) {
                            HistorySync.this.fail("Daily activity", "Could not save on this phone.");
                            return;
                        }
                        if (!store("Daily activity", "activity", day.toString(), System.currentTimeMillis(), o,
                                "watch quarter-hour detail")) return;
                        markSaved("daily activity");
                        advance();
                    }

                    @Override public void fail(String m) { HistorySync.this.fail("Daily activity", m); }
                });
    }

    private void heartRateToday() {
        listener.progress("Heart-rate history");
        LocalDate requested = LocalDate.now();
        ZonedDateTime midnight = requested.atStartOfDay(ZoneId.systemDefault());
        // Request value: local midnight as the vendor app computes it (Unix seconds plus zone offset).
        long requestValue = midnight.toEpochSecond() + midnight.getOffset().getTotalSeconds();
        w.readHistory("hr history", Health.readHeartRateDay(requestValue), new Health.HeartRateDayReader(),
                new WatchManager.Result<Health.HeartRateDayReader>() {
                    @Override public void ok(Health.HeartRateDayReader r) {
                        if (r.empty()) {
                            advance();
                            return;
                        }
                        JSONObject o;
                        try {
                            JSONArray samples = new JSONArray();
                            for (int v : r.samples()) samples.put(v);
                            o = new JSONObject().put("requestedDate", requested.toString())
                                    .put("requestValue", requestValue)
                                    .put("minutesPerSample", r.minutesPerSample()).put("samples", samples)
                                    .put("sampleNote", "0 means the watch stored no sample in that slot");
                            if (r.localEpochStart() >= 0) o.put("returnedTimeRaw", r.localEpochStart());
                            o.put("dateNote", "The day is the requested day. The watch time value is kept raw and not interpreted.");
                        } catch (JSONException e) {
                            HistorySync.this.fail("Heart-rate history", "Could not save on this phone.");
                            return;
                        }
                        if (!store("Heart-rate history", "hr_day", requested.toString(), System.currentTimeMillis(), o,
                                "watch heart-rate history")) return;
                        markSaved("heart-rate history");
                        advance();
                    }

                    @Override public void fail(String m) { HistorySync.this.fail("Heart-rate history", m); }
                });
    }

    /** Manual heart-rate history (bulk 28). Oxygen history is excluded from this app by the user. */
    private void manualHeartRate() {
        String label = "Manual heart-rate history";
        listener.progress(label);
        w.readBulk(label, Health.manualHistoryRequest(false, true), BulkProtocol.HR_MANUAL,
                new WatchManager.Result<byte[]>() {
                    @Override public void ok(byte[] payload) {
                        Health.ManualHistory h;
                        try {
                            h = Health.manualHistory(payload);
                        } catch (RuntimeException e) {
                            HistorySync.this.fail(label, Errors.message(e));
                            return;
                        }
                        LocalDate day = LocalDate.now().minusDays(h.dayOffset);
                        for (Health.TimedValue t : h.records) {
                            long ms = day.atStartOfDay(ZoneId.systemDefault()).plusMinutes(t.minuteOfDay).toInstant().toEpochMilli();
                            JSONObject o;
                            try {
                                o = new JSONObject().put("value", t.value).put("dayOffset", h.dayOffset)
                                        .put("minuteOfDay", t.minuteOfDay)
                                        .put("timeNote", "time = phone date minus the watch day offset, plus the watch minute");
                            } catch (JSONException e) {
                                HistorySync.this.fail(label, "Could not save on this phone.");
                                return;
                            }
                            if (!store(label, "heart_rate", day + "T" + t.minuteOfDay, ms, o, "watch manual history")) return;
                        }
                        if (!h.records.isEmpty()) markSaved("manual heart-rate history");
                        advance();
                    }

                    @Override public void fail(String m) { HistorySync.this.fail(label, m); }
                });
    }

    private void sleep() {
        listener.progress("Sleep");
        w.readBulk("sleep", Health.sleepRequest(true), BulkProtocol.SLEEP, new WatchManager.Result<byte[]>() {
            @Override public void ok(byte[] payload) {
                List<Health.SleepDay> days;
                try {
                    days = Health.sleep(payload);
                } catch (RuntimeException e) {
                    HistorySync.this.fail("Sleep", Errors.message(e));
                    return;
                }
                for (Health.SleepDay d : days) {
                    LocalDate end = LocalDate.now().minusDays(d.dayOffset);
                    LocalDate start = d.startsPreviousDay() ? end.minusDays(1) : end;
                    JSONObject o;
                    try {
                        JSONArray stages = new JSONArray();
                        for (Health.SleepStage s : d.stages) stages.put(new JSONArray().put(s.code).put(s.minutes));
                        Health.SleepSummary sum = Health.summarize(d);
                        o = new JSONObject().put("date", end.toString()).put("dayOffset", d.dayOffset)
                                .put("startMinuteOfDay", d.startMinute).put("endMinuteOfDay", d.endMinute)
                                .put("start", start + " " + Ui.hhmm(d.startMinute / 60, d.startMinute % 60))
                                .put("end", end + " " + Ui.hhmm(d.endMinute / 60, d.endMinute % 60))
                                .put("rawStages", stages).put("stageMinutesTotal", sum.stageSum)
                                .put("lightMinutes", sum.light).put("deepMinutes", sum.deep).put("awakeMinutes", sum.awake)
                                .put("unlabelledStageMinutes", sum.unlabelled).put("asleepMinutes", sum.asleep())
                                .put("deviceIntervalMinutes", sum.interval >= 0 ? (Object) sum.interval : JSONObject.NULL)
                                .put("stageSumMatchesInterval", sum.consistent())
                                .put("summaryNote", "stage 2 light, 3 deep, 5 awake as classified by the watch (Pubu labels); "
                                        + "asleep = light + deep; all sums calculated on this phone; no sleep score")
                                .put("dateNote", "dates = phone date minus the watch day offset; a start at or after 18:00 is the previous day")
                                .put("stageNote", "durations are minutes; other stage codes stay unlabelled");
                    } catch (JSONException e) {
                        HistorySync.this.fail("Sleep", "Could not save on this phone.");
                        return;
                    }
                    if (!store("Sleep", "sleep", end.toString(), System.currentTimeMillis(), o, "watch sleep history")) return;
                }
                if (!days.isEmpty()) markSaved("sleep");
                advance();
            }

            @Override public void fail(String m) { HistorySync.this.fail("Sleep", m); }
        });
    }
}
