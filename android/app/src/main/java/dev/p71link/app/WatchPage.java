package dev.p71link.app;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.ImageView;
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
        String state;
        switch (w.state) {
            case READY: state = "Connected"; break;
            case SCANNING: state = "Searching…"; break;
            case CONNECTING: state = "Connecting…"; break;
            default: state = "Not connected";
        }
        page.addView(Ui.header(a, "Watch", Ui.chip(a, state, w.connected())));
        Ui.add(page, deviceCard(a, w), 4);

        if (!w.connected()) {
            page.addView(Ui.note(a, "Settings are read from the watch after it connects."));
            if (message != null) page.addView(Ui.status(a, message, messageColor));
            return page;
        }

        // Quick actions sit next to the device they act on. Find watch is the one primary action.
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
                })), 12);
        if (message != null) page.addView(Ui.status(a, message, messageColor));

        page.addView(Ui.section(a, "Settings on the watch"));
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
        // A value the watch has not reported is shown as unknown and cannot be changed; it is never shown as off.
        String unread = w.settingsLoading ? "Reading…" : "Not read";
        LinearLayout g = Ui.group(a);

        Settings.Dnd dnd = w.dnd;
        View dndTrail;
        if (dnd != null) {
            Switch s = Ui.toggle(a, dnd.enabled, editable, "Do not disturb");
            s.setOnCheckedChangeListener((b, on) -> {
                if (on == dnd.enabled) return;
                busy = true;
                a.render();
                w.setDnd(dnd.withEnabled(on), result(a, v -> on ? "Do not disturb schedule is on." : "Do not disturb schedule is off."));
            });
            dndTrail = s;
        } else {
            dndTrail = Ui.unknownToggle(a);
        }
        Ui.addToGroup(g, Ui.item(a, R.drawable.ic_moon, "Do not disturb",
                dnd != null ? Ui.hhmm(dnd.startHour, dnd.startMinute) + "–" + Ui.hhmm(dnd.endHour, dnd.endMinute) : unread,
                dndTrail, editable && dnd != null ? v -> editDnd(a, dnd) : null));

        Settings.Wrist wr = w.wrist;
        View wristTrail;
        if (wr != null) {
            Switch s = Ui.toggle(a, wr.enabled, editable, "Raise wrist to wake");
            s.setOnCheckedChangeListener((b, on) -> {
                if (on == wr.enabled) return;
                busy = true;
                a.render();
                w.setWrist(wr.withEnabled(on), result(a, v -> "Raise to wake is " + (on ? "on." : "off.")));
            });
            wristTrail = s;
        } else {
            wristTrail = Ui.unknownToggle(a);
        }
        Ui.addToGroup(g, Ui.item(a, R.drawable.ic_wrist, "Raise wrist to wake", wr != null ? "Watch setting" : unread, wristTrail, null));

        Settings.Display d = w.display;
        Ui.addToGroup(g, Ui.item(a, R.drawable.ic_timer, "Display timeout", d != null ? d.seconds + " seconds" : unread, null,
                editable && d != null && d.hasRange() ? v -> chooseTimeout(a, d) : null));

        Settings.TimeFormat tf = w.timeFormat;
        String units = tf == null ? unread : (tf.is24Hour ? "24-hour" : "12-hour") + " · " + (tf.metric ? "Metric" : "Imperial")
                + (w.units != null ? " · " + (w.units.celsius ? "Celsius" : "Fahrenheit") : "");
        Ui.addToGroup(g, Ui.item(a, R.drawable.ic_globe, "Time and units", units, null,
                editable && tf != null ? v -> a.push(new TimeUnitsPage()) : null));
        page.addView(g);

        long synced = a.store.lastTimeSync();
        String when = synced == 0 ? "never" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(synced));
        page.addView(Ui.note(a, "Read when this screen opens. Time last set: " + when + "."));
        return page;
    }

    /** Device name, the bundled Orbit artwork, battery or an unread dash, link state, and Details. */
    private static View deviceCard(MainActivity a, WatchManager w) {
        LinearLayout card = Ui.group(a);
        int p = Ui.dp(a, 16);
        card.setPadding(p, p, p, p);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.graphics.Bitmap orbit = LocalFaces.orbit(a);
        if (orbit != null) {
            ImageView art = new ImageView(a);
            art.setImageBitmap(orbit);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setBackground(Ui.rounded(Ui.BG, Ui.dp(a, 15)));
            art.setClipToOutline(true);
            // Bundled artwork, not a picture read from the watch.
            art.setContentDescription("Orbit artwork bundled with P71 Link");
            row.addView(art, new LinearLayout.LayoutParams(Ui.dp(a, 72), Math.round(Ui.dp(a, 72) * 284f / 240f)));
        }

        LinearLayout info = Ui.column(a);
        info.addView(Ui.text(a, w.deviceName != null ? w.deviceName : "No watch yet", 18, Ui.TEXT));
        info.addView(Ui.text(a, "Artwork: Orbit", 13, Ui.MUTED));
        TextView label = Ui.text(a, "Battery", 13, Ui.TEXT2);
        label.setPadding(0, Ui.dp(a, 10), 0, 0);
        info.addView(label);
        boolean known = w.connected() && w.battery != null;
        LinearLayout battery = new LinearLayout(a);
        battery.setOrientation(LinearLayout.HORIZONTAL);
        battery.setGravity(android.view.Gravity.BOTTOM);
        TextView level = Ui.text(a, known ? w.battery + "%" : "–", 26, Ui.TEXT);
        level.setFontFeatureSettings("tnum"); // Digits keep their width when the value changes.
        battery.addView(level);
        if (!known) {
            TextView nr = Ui.text(a, "  not read", 13, Ui.MUTED);
            nr.setPadding(0, 0, 0, Ui.dp(a, 4));
            battery.addView(nr);
        }
        battery.setContentDescription(known ? "Battery " + w.battery + " percent" : "Battery not read");
        info.addView(battery);
        if (w.hasSavedWatch() || w.connected()) info.addView(Ui.link(a, "Details", v -> a.push(new DevicePage())));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        il.setMarginStart(Ui.dp(a, orbit != null ? 16 : 0));
        row.addView(info, il);
        card.addView(row);

        if (w.status != null) {
            TextView st = Ui.text(a, w.status, 13, Ui.TEXT2);
            st.setPadding(0, Ui.dp(a, 10), 0, 0);
            st.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(st);
        }
        if (!w.connected()) {
            boolean working = w.state == WatchManager.State.SCANNING || w.state == WatchManager.State.CONNECTING;
            Ui.add(card, Ui.button(a, working ? "Stop" : "Connect", !working, v -> {
                if (working) w.userDisconnect();
                else a.withBluetooth(w::userConnect);
            }), 12);
        }
        return card;
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
