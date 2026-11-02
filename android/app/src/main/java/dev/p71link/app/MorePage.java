package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

/** More tab: every other feature, grouped by where it acts. */
final class MorePage implements MainActivity.Page {
    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.header(a, "More", null));

        page.addView(Ui.section(a, "On the watch"));
        LinearLayout watch = Ui.group(a);
        Ui.addToGroup(watch, Ui.item(a, R.drawable.ic_bell, "Notifications and calls", "Choose apps · Calls · Test message", null,
                v -> a.push(new NotificationsPage())));
        Ui.addToGroup(watch, Ui.item(a, R.drawable.ic_alarm, "Alarms and reminders", "Alarms · Move · Drink", null,
                v -> a.push(new RemindersPage())));
        if (w.caps == null || w.caps.contacts) {
            Ui.addToGroup(watch, Ui.item(a, R.drawable.ic_person, "Contacts", "Manage watch contacts", null, v -> a.push(new ContactsPage())));
        }
        if (w.caps == null || w.caps.weather) {
            Ui.addToGroup(watch, Ui.item(a, R.drawable.ic_cloud, "Weather", "Send a forecast to the watch", null, v -> a.push(new WeatherPage())));
        }
        page.addView(watch);

        page.addView(Ui.section(a, "On this phone"));
        LinearLayout phone = Ui.group(a);
        Ui.addToGroup(phone, Ui.item(a, R.drawable.ic_phone, "Phone tools", "Find phone · Music controls", null,
                v -> a.push(new PhoneToolsPage())));
        page.addView(phone);

        page.addView(Ui.section(a, "Link and data"));
        LinearLayout link = Ui.group(a);
        Ui.addToGroup(link, Ui.item(a, R.drawable.ic_link, "Connection and battery", "Stay connected · Sync preferences", null,
                v -> a.push(new ConnectionPage())));
        Ui.addToGroup(link, Ui.item(a, R.drawable.ic_data, "Data and device", "Export · Device information", null,
                v -> a.push(new DevicePage())));
        page.addView(link);
        page.addView(Ui.note(a, "Only features your watch supports appear."));
        return page;
    }
}
