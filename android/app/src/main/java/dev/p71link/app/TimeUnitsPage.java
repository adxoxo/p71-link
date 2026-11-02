package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Settings;

/** 24-hour clock, metric units, and temperature unit. Each change is written, then read back. */
final class TimeUnitsPage implements MainActivity.Page {
    private String message;
    private boolean error, busy;

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Time and units"));
        page.addView(Ui.subtitle(a, "Shown on the watch. Your profile values stored with them stay unchanged."));
        Settings.TimeFormat t = w.timeFormat;
        if (!w.connected() || t == null) {
            page.addView(Ui.status(a, "Connect and open the Watch tab to read these settings.", Ui.TEXT2));
            return page;
        }
        boolean editable = !busy && !w.needsFreshRead;
        Switch h24 = Ui.toggle(a, t.is24Hour, editable, "24-hour time");
        h24.setOnCheckedChangeListener((b, on) -> {
            if (on != t.is24Hour) apply(a, t.with(on, t.metric));
        });
        Ui.add(page, Ui.row(a, "24-hour time", t.is24Hour ? "On" : "Off", h24, null), 0);
        Switch metric = Ui.toggle(a, t.metric, editable, "Metric units");
        metric.setOnCheckedChangeListener((b, on) -> {
            if (on != t.metric) apply(a, t.with(t.is24Hour, on));
        });
        Ui.add(page, Ui.row(a, "Metric units", t.metric ? "Kilometres and kilograms" : "Miles and pounds", metric, null), 10);
        if (w.units != null) {
            Settings.Units u = w.units;
            Switch c = Ui.toggle(a, u.celsius, editable, "Celsius");
            c.setOnCheckedChangeListener((b, on) -> {
                if (on == u.celsius) return;
                busy = true;
                a.render();
                w.setUnits(u.withCelsius(on), done(a, "Temperature unit saved."));
            });
            Ui.add(page, Ui.row(a, "Celsius", u.celsius ? "Celsius" : "Fahrenheit", c, null), 10);
        } else {
            page.addView(Ui.note(a, "The watch did not report its temperature unit."));
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        return page;
    }

    private void apply(MainActivity a, Settings.TimeFormat wanted) {
        busy = true;
        a.render();
        a.watch.setTimeFormat(wanted, done(a, "Saved on the watch."));
    }

    private <T> WatchManager.Result<T> done(MainActivity a, String ok) {
        return new WatchManager.Result<T>() {
            @Override public void ok(T v) { message = ok; error = false; busy = false; a.render(); }
            @Override public void fail(String m) { message = m; error = true; busy = false; a.render(); }
        };
    }
}
