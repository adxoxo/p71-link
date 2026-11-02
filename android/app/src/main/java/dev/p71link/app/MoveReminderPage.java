package dev.p71link.app;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Settings;

/** Move reminder: active hours, repeat days, and interval. Off means no repeat days. */
final class MoveReminderPage implements MainActivity.Page {
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected()) load(a); }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadSedentary(new WatchManager.Result<Settings.Sedentary>() {
            @Override public void ok(Settings.Sedentary v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Move reminder"));
        page.addView(Ui.subtitle(a, "The watch reminds you to move during these hours."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to read this reminder.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading from the watch…", Ui.TEXT2));
        Settings.Sedentary s = w.sedentary;
        if (s != null) {
            boolean editable = !busy && !loading && !w.needsFreshRead;
            Switch on = Ui.toggle(a, s.enabled(), editable, "Move reminder");
            on.setOnCheckedChangeListener((b, v) -> {
                if (v == s.enabled()) return;
                int cycle = valid(s.cycleMinutes) ? s.cycleMinutes : 60;
                save(a, s.with(s.startHour, s.startMinute, s.endHour, s.endMinute, v ? 0x7f : 0, cycle));
            });
            Ui.add(page, Ui.row(a, "Move reminder", s.enabled() ? Days.text(s.weekMask) : "Off", on, null), 0);
            Ui.add(page, Ui.row(a, "Active hours", Ui.hhmm(s.startHour, s.startMinute) + "–" + Ui.hhmm(s.endHour, s.endMinute), null,
                    editable ? v -> hours(a, s) : null), 10);
            Ui.add(page, Ui.row(a, "Repeat", s.enabled() ? Days.text(s.weekMask) : "No days", null,
                    editable ? v -> Days.pick(a, "Repeat", s.weekMask, m -> save(a, s.with(s.startHour, s.startMinute,
                            s.endHour, s.endMinute, m, valid(s.cycleMinutes) ? s.cycleMinutes : 60))) : null), 10);
            String every = valid(s.cycleMinutes) ? "Every " + s.cycleMinutes : "Watch value " + s.cycleMinutes;
            Ui.add(page, Ui.row(a, "Interval", every, null, editable ? v -> interval(a, s) : null), 10);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "Interval choices are 30, 60, or 90. The watch's unit for this value is not confirmed; minutes is likely."));
        return page;
    }

    private static boolean valid(int c) { return c == 30 || c == 60 || c == 90; }

    private void hours(MainActivity a, Settings.Sedentary s) {
        new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (v1, sh, sm) ->
                new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (v2, eh, em) ->
                        save(a, s.with(sh, sm, eh, em, s.weekMask, valid(s.cycleMinutes) ? s.cycleMinutes : 60)),
                        s.endHour, s.endMinute, true).show(), s.startHour, s.startMinute, true).show();
    }

    private void interval(MainActivity a, Settings.Sedentary s) {
        String[] labels = {"30", "60", "90"};
        int[] values = {30, 60, 90};
        int checked = s.cycleMinutes == 30 ? 0 : s.cycleMinutes == 90 ? 2 : s.cycleMinutes == 60 ? 1 : -1;
        a.dialog().setTitle("Interval").setSingleChoiceItems(labels, checked, (d, which) -> {
            d.dismiss();
            save(a, s.with(s.startHour, s.startMinute, s.endHour, s.endMinute, s.weekMask, values[which]));
        }).setNegativeButton("Cancel", null).show();
    }

    private void save(MainActivity a, Settings.Sedentary wanted) {
        busy = true;
        message = "Saving…";
        error = false;
        a.render();
        a.watch.saveSedentary(wanted, new WatchManager.Result<Settings.Sedentary>() {
            @Override public void ok(Settings.Sedentary v) { busy = false; message = "Saved on the watch."; a.render(); }
            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
