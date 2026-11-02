package dev.p71link.app;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Alarms;

import java.util.ArrayList;
import java.util.List;

/**
 * Alarms stored on the watch. The watch keeps them as one list, so every change writes the whole
 * list read from the watch, with only the edited alarm changed, then reads it back.
 */
final class AlarmsPage implements MainActivity.Page {
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected() && a.watch.alarms == null) load(a); }

    private void load(MainActivity a) {
        loading = true;
        message = null;
        a.watch.loadAlarms(new WatchManager.Result<List<Alarms.Alarm>>() {
            @Override public void ok(List<Alarms.Alarm> v) { loading = false; a.watch.needsFreshRead = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Alarms"));
        page.addView(Ui.subtitle(a, "Alarms stored on your watch."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to see its alarms.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading alarms…", Ui.TEXT2));
        List<Alarms.Alarm> list = w.alarms;
        boolean editable = list != null && !busy && !loading && !w.needsFreshRead;
        if (list != null) {
            if (list.isEmpty()) page.addView(Ui.status(a, "No alarms on the watch.", Ui.TEXT2));
            for (int i = 0; i < list.size(); i++) {
                final int index = i;
                Alarms.Alarm al = list.get(i);
                Switch s = Ui.toggle(a, al.enabled, editable, "Alarm " + Ui.hhmm(al.hour(), al.minute()));
                s.setOnCheckedChangeListener((b, on) -> {
                    if (on != al.enabled) save(a, replace(list, index, al.withEnabled(on)), on ? "Alarm on." : "Alarm off.");
                });
                String sub = Days.text(al.repeatMask) + (al.title.isEmpty() ? "" : " · " + al.title);
                Ui.add(page, Ui.row(a, Ui.hhmm(al.hour(), al.minute()), sub, s, editable ? v -> edit(a, list, index) : null),
                        i == 0 ? 0 : 10);
            }
            if (editable && list.size() < Alarms.MAX_ALARMS) {
                Ui.add(page, Ui.button(a, "Add alarm", true, v -> edit(a, list, -1)), 16);
            }
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (!loading) Ui.add(page, Ui.button(a, "Read again", false, v -> load(a)), 10);
        page.addView(Ui.note(a, "Tap an alarm to change or delete it. The watch rings; the phone does not."));
        return page;
    }

    private static List<Alarms.Alarm> replace(List<Alarms.Alarm> list, int index, Alarms.Alarm a) {
        List<Alarms.Alarm> out = new ArrayList<>(list);
        if (index < 0) out.add(a);
        else out.set(index, a);
        return out;
    }

    private void edit(MainActivity a, List<Alarms.Alarm> list, int index) {
        Alarms.Alarm cur = index >= 0 ? list.get(index) : new Alarms.Alarm(true, 0x3e, 7 * 60, "");
        new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (tp, h, m) ->
                Days.pick(a, "Repeat", cur.repeatMask, mask -> {
                    EditText title = new EditText(a);
                    title.setText(cur.title);
                    title.setHint("Label (optional)");
                    int p = Ui.dp(a, 20);
                    LinearLayout box = Ui.column(a);
                    box.setPadding(p, p / 2, p, 0);
                    box.addView(title);
                    android.app.AlertDialog.Builder d = a.dialog().setTitle("Alarm label").setView(box)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Save", (dl, wh) -> {
                                // An unchanged label keeps its stored bytes; a new label is limited to the app's title size.
                                Alarms.Alarm next = cur.edited(index < 0 || cur.enabled, mask, h * 60 + m, title.getText().toString());
                                save(a, replace(list, index, next), "Alarm saved on the watch.");
                            });
                    if (index >= 0) {
                        d.setNeutralButton("Delete", (dl, wh) -> a.confirm("Delete this alarm?", "It is removed from the watch.", "Delete", () -> {
                            List<Alarms.Alarm> out = new ArrayList<>(list);
                            out.remove(index);
                            save(a, out, "Alarm deleted.");
                        }));
                    }
                    d.show();
                }), cur.hour(), cur.minute(), true).show();
    }

    private void save(MainActivity a, List<Alarms.Alarm> wanted, String ok) {
        busy = true;
        message = "Saving…";
        error = false;
        a.render();
        a.watch.saveAlarms(wanted, new WatchManager.Result<List<Alarms.Alarm>>() {
            @Override public void ok(List<Alarms.Alarm> v) { busy = false; message = ok; error = false; a.render(); }
            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
