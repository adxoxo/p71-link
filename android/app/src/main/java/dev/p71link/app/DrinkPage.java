package dev.p71link.app;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Settings;

/** Drink reminder slots as stored on the watch. Each change is written, then that slot is read back. */
final class DrinkPage implements MainActivity.Page {
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected() && a.watch.drinks == null) load(a); }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadDrinks(new WatchManager.Result<Settings.Alarm[]>() {
            @Override public void ok(Settings.Alarm[] v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Drink reminders"));
        page.addView(Ui.subtitle(a, "Reminder times stored on the watch."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to read its reminders.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading eight reminder slots…", Ui.TEXT2));
        Settings.Alarm[] d = w.drinks;
        if (d != null) {
            boolean editable = !busy && !loading && !w.needsFreshRead;
            for (int i = 0; i < d.length; i++) {
                Settings.Alarm s = d[i];
                if (s == null) {
                    Ui.add(page, Ui.row(a, "Reminder " + (i + 1), "Not available from the watch", null, null), i == 0 ? 0 : 8);
                    continue;
                }
                Switch sw = Ui.toggle(a, s.enabled, editable, "Reminder " + (i + 1));
                sw.setOnCheckedChangeListener((b, on) -> {
                    if (on != s.enabled) save(a, s.edited(on, s.hour, s.minute, s.weekMask));
                });
                Ui.add(page, Ui.row(a, Ui.hhmm(s.hour, s.minute), "Reminder " + (i + 1) + " · " + Days.text(s.weekMask), sw,
                        editable ? v -> edit(a, s) : null), i == 0 ? 0 : 8);
            }
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (!loading) Ui.add(page, Ui.button(a, "Read again", false, v -> load(a)), 16);
        page.addView(Ui.note(a, "Each slot keeps its own days. Tap a reminder to change its time or days."));
        return page;
    }

    private void edit(MainActivity a, Settings.Alarm s) {
        new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (tp, h, m) ->
                Days.pick(a, "Repeat", s.weekMask, mask -> save(a, s.edited(s.enabled, h, m, mask))),
                s.hour, s.minute, true).show();
    }

    private void save(MainActivity a, Settings.Alarm wanted) {
        busy = true;
        message = "Saving…";
        error = false;
        a.render();
        a.watch.saveDrink(wanted, new WatchManager.Result<Settings.Alarm>() {
            @Override public void ok(Settings.Alarm v) { busy = false; message = "Saved on the watch."; a.render(); }
            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
