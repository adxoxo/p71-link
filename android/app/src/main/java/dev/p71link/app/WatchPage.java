package dev.p71link.app;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.p71link.protocol.Settings;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Watch tab: connection, battery, stored settings, find watch, and explicit time sync. */
final class WatchPage implements MainActivity.Page {
    private String message;
    private int messageColor = Ui.TEXT2;
    private boolean busy;

    @Override
    public void onShown(MainActivity a) {
        if (a.watch.connected() && a.watch.dnd == null && !a.watch.settingsLoading) a.watch.loadWatchSettings();
    }

    private void say(MainActivity a, String m, boolean error) {
        message = m;
        messageColor = error ? Ui.ERROR : Ui.TEXT2;
        busy = false;
        a.render();
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Watch"));

        // Hero card.
        LinearLayout hero = Ui.card(a, Ui.HERO);
        String name = w.deviceName != null ? w.deviceName : "No watch yet";
        hero.addView(Ui.text(a, name, 18, Ui.TEXT));
        String state;
        switch (w.state) {
            case READY: state = "Connected"; break;
            case SCANNING: state = "Searching…"; break;
            case CONNECTING: state = "Connecting…"; break;
            default: state = w.hasSavedWatch() ? "Not connected" : "Tap Connect to find your watch";
        }
        hero.addView(Ui.text(a, state, 14, w.connected() ? Ui.ACCENT : Ui.TEXT2));
        LinearLayout batteryRow = new LinearLayout(a);
        batteryRow.setOrientation(LinearLayout.HORIZONTAL);
        batteryRow.setGravity(android.view.Gravity.BOTTOM);
        TextView level = Ui.text(a, w.connected() && w.battery != null ? w.battery + "%" : "—", 40, Ui.TEXT);
        batteryRow.addView(level);
        TextView label = Ui.text(a, "  battery", 14, Ui.TEXT2);
        label.setPadding(0, 0, 0, Ui.dp(a, 10));
        batteryRow.addView(label);
        batteryRow.setContentDescription(w.connected() && w.battery != null ? "Battery " + w.battery + " percent" : "Battery unknown");
        hero.addView(batteryRow);
        if (w.status != null) hero.addView(Ui.text(a, w.status, 13, Ui.TEXT2));
        if (!w.connected()) {
            boolean working = w.state == WatchManager.State.SCANNING || w.state == WatchManager.State.CONNECTING;
            Ui.add(hero, Ui.button(a, working ? "Stop" : "Connect", !working, v -> {
                if (working) w.userDisconnect();
                else a.withBluetooth(w::userConnect);
            }), 12);
        }
        Ui.add(page, hero, 4);

        if (!w.connected()) {
            page.addView(Ui.note(a, "Controls appear after the watch connects. Settings are read from the watch, not assumed."));
            if (message != null) page.addView(Ui.status(a, message, messageColor));
            return page;
        }

        page.addView(Ui.section(a, "Your watch"));
        if (w.settingsLoading) page.addView(Ui.status(a, "Reading settings from the watch…", Ui.TEXT2));
        if (w.settingsError != null) {
            page.addView(Ui.status(a, w.settingsError, Ui.ERROR));
            Ui.add(page, Ui.button(a, "Read again", false, v -> w.loadWatchSettings()), 6);
        }
        if (w.needsFreshRead) {
            page.addView(Ui.status(a, "An earlier change has an unknown result. Read the settings again first.", Ui.WARN));
            Ui.add(page, Ui.button(a, "Read settings", false, v -> w.loadWatchSettings()), 6);
        }
        boolean editable = !busy && !w.settingsLoading && !w.needsFreshRead;

        // Do not disturb.
        if (w.dnd != null) {
            Settings.Dnd d = w.dnd;
            Switch s = Ui.toggle(a, d.enabled, editable, "Do not disturb");
            s.setOnCheckedChangeListener((b, on) -> {
                if (on == d.enabled) return;
                busy = true;
                a.render();
                w.setDnd(d.withEnabled(on), result(a, v -> on ? "Do not disturb schedule is on." : "Do not disturb schedule is off."));
            });
            Ui.add(page, Ui.row(a, "Do not disturb", Ui.hhmm(d.startHour, d.startMinute) + "–" + Ui.hhmm(d.endHour, d.endMinute),
                    s, editable ? v -> editDnd(a, d) : null), 0);
        }
        // Raise wrist.
        if (w.wrist != null) {
            Settings.Wrist wr = w.wrist;
            Switch s = Ui.toggle(a, wr.enabled, editable, "Raise wrist to wake");
            s.setOnCheckedChangeListener((b, on) -> {
                if (on == wr.enabled) return;
                busy = true;
                a.render();
                w.setWrist(wr.withEnabled(on), result(a, v -> "Raise to wake is " + (on ? "on." : "off.")));
            });
            Ui.add(page, Ui.row(a, "Raise wrist to wake", "Watch setting", s, null), 10);
        }
        // Display timeout.
        if (w.display != null) {
            Settings.Display d = w.display;
            Ui.add(page, Ui.row(a, "Display timeout", d.seconds + " seconds", null,
                    editable && d.hasRange() ? v -> chooseTimeout(a, d) : null), 10);
        }
        // Time and units.
        if (w.timeFormat != null) {
            Settings.TimeFormat t = w.timeFormat;
            String sub = (t.is24Hour ? "24-hour" : "12-hour") + " · " + (t.metric ? "Metric" : "Imperial")
                    + (w.units != null ? " · " + (w.units.celsius ? "Celsius" : "Fahrenheit") : "");
            Ui.add(page, Ui.row(a, "Time and units", sub, null, editable ? v -> a.push(new TimeUnitsPage()) : null), 10);
        }

        Ui.add(page, Ui.buttonPair(a,
                Ui.button(a, "Find watch", true, v -> {
                    busy = true;
                    a.render();
                    w.findWatch(result(a, x -> "Sent. The watch should vibrate now."));
                }),
                Ui.button(a, "Sync time", false, v -> {
                    busy = true;
                    a.render();
                    w.syncTime(result(a, x -> "Watch time set from this phone."));
                })), 22);
        if (message != null) page.addView(Ui.status(a, message, messageColor));
        long synced = a.store.lastTimeSync();
        String when = synced == 0 ? "never" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(synced));
        page.addView(Ui.note(a, "Updated when you open this screen. Time last set: " + when + "."));
        return page;
    }

    interface Text<T> { String of(T v); }

    private <T> WatchManager.Result<T> result(MainActivity a, Text<T> ok) {
        return new WatchManager.Result<T>() {
            @Override public void ok(T v) { say(a, ok.of(v), false); }
            @Override public void fail(String m) { say(a, m, true); }
        };
    }

    private void chooseTimeout(MainActivity a, Settings.Display d) {
        List<Integer> values = new ArrayList<>();
        for (int s = d.min; s <= d.max && values.size() < 60; s += d.step) values.add(s);
        String[] labels = new String[values.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = values.get(i) + " seconds";
            if (values.get(i) == d.seconds) checked = i;
        }
        a.dialog().setTitle("Display timeout").setSingleChoiceItems(labels, checked, (dlg, which) -> {
            dlg.dismiss();
            int value = values.get(which);
            if (value == d.seconds) return;
            busy = true;
            a.render();
            a.watch.setDisplay(d.withSeconds(value), result(a, v -> "Display stays on for " + v.seconds + " seconds."));
        }).setNegativeButton("Cancel", null).show();
    }

    private void editDnd(MainActivity a, Settings.Dnd d) {
        new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (v1, sh, sm) ->
                new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (v2, eh, em) -> {
                    busy = true;
                    a.render();
                    a.watch.setDnd(d.withSchedule(sh, sm, eh, em),
                            result(a, x -> "Quiet hours set to " + Ui.hhmm(x.startHour, x.startMinute) + "–" + Ui.hhmm(x.endHour, x.endMinute) + "."));
                }, d.endHour, d.endMinute, true) {
                    { setTitle("Quiet hours end"); }
                }.show(), d.startHour, d.startMinute, true) {
            { setTitle("Quiet hours start"); }
        }.show();
    }
}
