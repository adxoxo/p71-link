package dev.p71link.app;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;

/**
 * Cycle reminder settings. Values are what you enter; they stay on this phone. Day counts are
 * calculated here from your dates. The watch offers no read-back, so a send is not confirmed as stored.
 */
final class CyclePage implements MainActivity.Page {
    private static final String KEY = "cycle";
    private String message;
    private boolean error, busy;

    private static JSONObject load(Store s) {
        String raw = s.get(KEY);
        try {
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void save(Store s, JSONObject o) { s.put(KEY, o.toString()); }

    private static void put(Store s, String k, Object v) {
        JSONObject o = load(s);
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
            return;
        }
        save(s, o);
    }

    @Override
    public View build(MainActivity a) {
        Store st = a.store;
        JSONObject o = load(st);
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Cycle reminders"));
        page.addView(Ui.subtitle(a, "What you enter stays on this phone and is sent to the watch when you choose."));

        String start = o.optString("start", ""), end = o.optString("end", "");
        Ui.add(page, Ui.row(a, "Last period started", start.isEmpty() ? "Not set" : start, null, v -> date(a, "start", start)), 0);
        Ui.add(page, Ui.row(a, "Last period ended", end.isEmpty() ? "Not set" : end, null, v -> date(a, "end", end)), 10);
        Ui.add(page, Ui.row(a, "Period length", HealthLog.hasNumber(o, "period") ? o.optInt("period") + " days" : "Not set", null,
                v -> number(a, "period", "Period length in days", 1, 15)), 10);
        Ui.add(page, Ui.row(a, "Cycle length", HealthLog.hasNumber(o, "cycle") ? o.optInt("cycle") + " days" : "Not set", null,
                v -> number(a, "cycle", "Cycle length in days", 15, 60)), 10);
        boolean remind = o.optBoolean("remind", false);
        Switch sw = Ui.toggle(a, remind, true, "Reminder");
        sw.setOnCheckedChangeListener((b, on) -> { put(st, "remind", on); a.render(); });
        String time = HealthLog.hasNumber(o, "hour") ? Ui.hhmm(o.optInt("hour"), o.optInt("minute")) : "Time not set";
        Ui.add(page, Ui.row(a, "Reminder", remind ? time : "Off", sw, null), 10);
        if (remind) Ui.add(page, Ui.row(a, "Reminder time", time, null, v -> new TimePickerDialog(a,
                android.R.style.Theme_Material_Dialog_Alert, (tp, h, m) -> {
                    put(st, "hour", h);
                    put(st, "minute", m);
                    a.render();
                }, HealthLog.hasNumber(o, "hour") ? o.optInt("hour") : 9, HealthLog.hasNumber(o, "minute") ? o.optInt("minute") : 0, true).show()), 10);

        Ui.add(page, Ui.row(a, "Period reminder", lead(o, "periodLead"), null, v -> chooseLead(a, "periodLead", "Period reminder")), 10);
        Ui.add(page, Ui.row(a, "Fertile window reminder", lead(o, "fertileLead"), null,
                v -> chooseLead(a, "fertileLead", "Fertile window reminder")), 10);
        Ui.add(page, Ui.button(a, busy ? "Sending…" : "Send to watch", true, busy ? null : v -> send(a)), 16);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "This app does not predict periods or show health results. The day counts sent are calculated from your dates."));
        return page;
    }

    private static String lead(JSONObject o, String key) {
        if (!HealthLog.hasNumber(o, key)) return "Not set";
        int d = o.optInt(key);
        return d + (d == 1 ? " day" : " days") + " in advance";
    }

    private void chooseLead(MainActivity a, String key, String title) {
        String[] labels = {"1 day in advance", "2 days in advance", "3 days in advance"};
        a.dialog().setTitle(title).setItems(labels, (d, which) -> {
            put(a.store, key, which + 1);
            a.render();
        }).show();
    }

    private void date(MainActivity a, String key, String current) {
        LocalDate d = current.isEmpty() ? LocalDate.now() : LocalDate.parse(current);
        new DatePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (dp, y, m, day) -> {
            LocalDate picked = LocalDate.of(y, m + 1, day);
            if (picked.isAfter(LocalDate.now())) {
                message = "Choose a date that is not in the future.";
                error = true;
            } else {
                put(a.store, key, picked.toString());
            }
            a.render();
        }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
    }

    private void number(MainActivity a, String key, String title, int min, int max) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e);
        a.dialog().setTitle(title).setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            int v;
            try {
                v = Integer.parseInt(e.getText().toString().trim());
            } catch (NumberFormatException ex) {
                v = Integer.MIN_VALUE;
            }
            if (v < min || v > max) {
                message = "Enter a number from " + min + " to " + max + ".";
                error = true;
            } else {
                put(a.store, key, v);
                message = null;
            }
            a.render();
        }).show();
    }

    private void send(MainActivity a) {
        JSONObject o = load(a.store);
        if (o.optString("start").isEmpty() || o.optString("end").isEmpty() || !HealthLog.hasNumber(o, "period")
                || !HealthLog.hasNumber(o, "cycle") || !HealthLog.hasNumber(o, "periodLead") || !HealthLog.hasNumber(o, "fertileLead")) {
            message = "Set both dates, both lengths, and both reminder lead times first.";
            error = true;
            a.render();
            return;
        }
        boolean remind = o.optBoolean("remind", false);
        if (remind && !HealthLog.hasNumber(o, "hour")) {
            message = "Set the reminder time first.";
            error = true;
            a.render();
            return;
        }
        if (!a.watch.connected()) {
            message = "Connect the watch first.";
            error = true;
            a.render();
            return;
        }
        Settings.Cycle c;
        try {
            LocalDate today = LocalDate.now();
            c = new Settings.Cycle(o.optInt("period"), o.optInt("cycle"),
                    Settings.Cycle.daysBetween(today, LocalDate.parse(o.optString("start"))),
                    Settings.Cycle.daysBetween(today, LocalDate.parse(o.optString("end"))), remind,
                    o.optInt("periodLead"), o.optInt("fertileLead"), remind ? o.optInt("hour") : 0, remind ? o.optInt("minute") : 0);
        } catch (RuntimeException e) {
            message = Errors.message(e);
            error = true;
            a.render();
            return;
        }
        busy = true;
        a.render();
        a.watch.sendCycle(c, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) {
                busy = false;
                message = "Sent. The watch gives no read-back for these settings, so they are not confirmed as stored.";
                error = false;
                a.render();
            }

            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
