package dev.p71link.app;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import dev.p71link.protocol.Settings;

/** Daily step goal. Other stored goals are kept exactly as the watch reported them. */
final class GoalsPage implements MainActivity.Page {
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected()) load(a); }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadGoals(new WatchManager.Result<Settings.Goals>() {
            @Override public void ok(Settings.Goals v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Daily goal"));
        page.addView(Ui.subtitle(a, "The watch tracks your progress toward this goal."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to read your goal.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading from the watch…", Ui.TEXT2));
        Settings.Goals g = w.goals;
        if (g != null) {
            boolean editable = !busy && !loading && !w.needsFreshRead;
            Ui.add(page, Ui.row(a, "Steps", String.format(java.util.Locale.getDefault(), "%,d steps a day", g.steps), null,
                    editable ? v -> editSteps(a, g) : null), 0);
            if (g.activeMinutes > 0) Ui.add(page, Ui.row(a, "Active time", g.activeMinutes + " minutes (kept as stored)", null, null), 10);
            if (g.sleepMinutes > 0) {
                Ui.add(page, Ui.row(a, "Sleep", g.sleepMinutes / 60 + " h " + g.sleepMinutes % 60 + " min (kept as stored)", null, null), 10);
            }
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "Calorie and distance goals are kept unchanged. Their units on this watch are not confirmed."));
        return page;
    }

    private void editSteps(MainActivity a, Settings.Goals g) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(g.steps));
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e);
        a.dialog().setTitle("Steps a day").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            int v;
            try {
                v = Integer.parseInt(e.getText().toString().trim());
            } catch (NumberFormatException ex) {
                v = -1;
            }
            if (v < 100 || v > 100000) {
                message = "Choose a goal from 100 to 100,000 steps.";
                error = true;
                a.render();
                return;
            }
            busy = true;
            message = "Saving…";
            error = false;
            a.render();
            a.watch.setStepGoal(v, new WatchManager.Result<Settings.Goals>() {
                @Override public void ok(Settings.Goals x) { busy = false; message = "Saved on the watch."; a.render(); }
                @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
            });
        }).show();
    }
}
