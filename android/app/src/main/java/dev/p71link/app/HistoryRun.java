package dev.p71link.app;

import java.util.List;

/**
 * The one history sync the app runs at a time, shared by Health and Activity and sleep, so leaving a
 * screen neither loses nor repeats it. It uses the existing HistorySync steps unchanged; screens only
 * read its state. Main thread only.
 */
final class HistoryRun {
    private static final dev.p71link.protocol.ExclusiveOp op = new dev.p71link.protocol.ExclusiveOp();
    /** Current step text while running. */
    static String step;
    /** Outcome of the last run, or a reason it could not start. */
    static String message;
    static boolean error;

    private HistoryRun() {}

    static boolean running() { return op.active(); }

    /** Start a sync unless one is running. Returns whether it started. */
    static boolean start(WatchManager w, HealthLog log) {
        if (op.active()) return false;
        if (!w.connected()) {
            message = "Connect the watch first.";
            error = true;
            w.notifyChanged();
            return false;
        }
        final int t = op.begin();
        step = null;
        message = null;
        error = false;
        new HistorySync(w, log, new HistorySync.Listener() {
            @Override public void progress(String s) {
                if (!op.current(t)) return;
                step = s;
                w.notifyChanged();
            }

            @Override public void done(List<String> saved, String failure) {
                if (!op.end(t)) return;
                step = null;
                String got = saved.isEmpty() ? "No new records were saved." : "Saved " + String.join(", ", saved) + ".";
                message = failure == null ? got : failure + " " + got;
                error = failure != null;
                w.notifyChanged();
            }
        }).start();
        w.notifyChanged();
        return true;
    }
}
