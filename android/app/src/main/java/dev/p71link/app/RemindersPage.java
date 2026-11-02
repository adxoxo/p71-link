package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

/** Alarms and reminders hub. */
final class RemindersPage implements MainActivity.Page {
    @Override
    public View build(MainActivity a) {
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Alarms and reminders"));
        page.addView(Ui.subtitle(a, "Stored on the watch."));
        Ui.add(page, Ui.row(a, "Alarms", "Times, repeat days, labels", null, v -> a.push(new AlarmsPage())), 0);
        Ui.add(page, Ui.row(a, "Move reminder", "Active hours and interval", null, v -> a.push(new MoveReminderPage())), 10);
        Ui.add(page, Ui.row(a, "Drink reminders", "Up to eight reminder times", null, v -> a.push(new DrinkPage())), 10);
        return page;
    }
}
