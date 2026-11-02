package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

/** Automatic heart-rate switch, read from and verified on the watch. */
final class AutoHealthPage implements MainActivity.Page {
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected()) load(a); }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadHealthSettings(new WatchManager.Result<Void>() {
            @Override public void ok(Void v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Automatic measurements"));
        page.addView(Ui.subtitle(a, "The watch measures on its own schedule while these are on."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to read these settings.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading from the watch…", Ui.TEXT2));
        boolean editable = !busy && !loading && !w.needsFreshRead;
        if (w.hrAuto != null) {
            boolean on = w.hrAuto.enabled;
            Switch s = Ui.toggle(a, on, editable, "Automatic heart rate");
            s.setOnCheckedChangeListener((b, v) -> {
                if (v == on) return;
                busy = true;
                a.render();
                w.setHeartRateAuto(v, done(a, v ? "Automatic heart rate is on." : "Automatic heart rate is off."));
            });
            Ui.add(page, Ui.row(a, "Heart rate", "Interval kept as stored on the watch", s, null), 0);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (!loading) Ui.add(page, Ui.button(a, "Read again", false, v -> load(a)), 16);
        page.addView(Ui.note(a, "A saved switch shows the stored setting. It does not prove that readings were taken."));
        return page;
    }

    private <T> WatchManager.Result<T> done(MainActivity a, String ok) {
        return new WatchManager.Result<T>() {
            @Override public void ok(T v) { busy = false; message = ok; error = false; a.render(); }
            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        };
    }
}
