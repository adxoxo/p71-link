package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

/** More tab: everything else, one place. */
final class MorePage implements MainActivity.Page {
    @Override
    public View build(MainActivity a) {
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "More"));
        page.addView(Ui.subtitle(a, "Everything else, one place."));
        Ui.add(page, Ui.row(a, "Notifications and calls", "Choose apps · Calls · Test message", null,
                v -> a.push(new NotificationsPage())), 0);
        Ui.add(page, Ui.row(a, "Alarms and reminders", "Alarms · Move · Drink", null, v -> a.push(new RemindersPage())), 10);
        if (a.watch.caps == null || a.watch.caps.contacts) {
            Ui.add(page, Ui.row(a, "Contacts", "Manage watch contacts", null, v -> a.push(new ContactsPage())), 10);
        }
        if (a.watch.caps == null || a.watch.caps.weather) {
            Ui.add(page, Ui.row(a, "Weather", "Send a forecast to the watch", null, v -> a.push(new WeatherPage())), 10);
        }
        Ui.add(page, Ui.row(a, "Phone tools", "Find phone · Music controls", null, v -> a.push(new PhoneToolsPage())), 10);
        Ui.add(page, Ui.row(a, "Connection and battery", "Stay connected · Sync preferences", null,
                v -> a.push(new ConnectionPage())), 10);
        Ui.add(page, Ui.row(a, "Data and device", "Export · Device information", null, v -> a.push(new DevicePage())), 10);
        page.addView(Ui.note(a, "Only features your watch supports appear."));
        return page;
    }
}
