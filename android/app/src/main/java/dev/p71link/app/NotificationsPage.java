package dev.p71link.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

import dev.p71link.protocol.Notifications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Forwarding stays off and no app is selected until the user chooses. */
final class NotificationsPage implements MainActivity.Page {
    private String message;
    private boolean error;

    static boolean accessGranted(MainActivity a) {
        String flat = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(a, NotifyListener.class).flattenToString());
    }

    @Override
    public View build(MainActivity a) {
        Store s = a.store;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Notifications and calls"));
        page.addView(Ui.subtitle(a, "Send chosen phone alerts to the watch. Text is not stored or logged."));
        boolean access = accessGranted(a);
        if (!access) {
            page.addView(Ui.status(a, "Android needs you to allow notification access for P71 Link.", Ui.WARN));
            Ui.add(page, Ui.button(a, "Open notification access", true,
                    v -> a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))), 6);
        }
        Switch fwd = Ui.toggle(a, s.forwardNotifications(), access, "Forward notifications");
        fwd.setOnCheckedChangeListener((b, on) -> { s.setForwardNotifications(on); a.render(); });
        Ui.add(page, Ui.row(a, "Forward notifications", s.allowedApps().size() + " apps chosen", fwd, null), 14);
        Switch calls = Ui.toggle(a, s.forwardCalls(), access, "Incoming calls");
        calls.setOnCheckedChangeListener((b, on) -> { s.setForwardCalls(on); a.render(); });
        Ui.add(page, Ui.row(a, "Incoming calls", "For dialers that mark incoming calls; the watch is told when the call is answered or ends", calls, null), 10);

        Switch reject = Ui.toggle(a, s.rejectCalls(), true, "Reject calls from the watch");
        reject.setOnCheckedChangeListener((b, on) -> {
            if (on) a.withCallPermission(() -> { s.setRejectCalls(true); a.render(); });
            else { s.setRejectCalls(false); a.render(); }
        });
        PhoneTools pt = P71App.get().tools;
        String rsub = pt.lastReject == null ? "The watch's reject button ends the call on this phone"
                : "Last: " + pt.lastReject + " · " + HealthPage.when(pt.lastRejectAt);
        Ui.add(page, Ui.row(a, "Reject from the watch", rsub, reject, null), 10);
        Ui.add(page, Ui.button(a, "Send a test message", false, v -> {
            if (!a.watch.connected()) { message = "Connect the watch first."; error = true; a.render(); return; }
            a.watch.notifyText("Hello from P71 Link", Notifications.SMS, new WatchManager.Result<Void>() {
                @Override public void ok(Void x) { message = "The watch received it. Check that the text appears."; error = false; a.render(); }
                @Override public void fail(String m) { message = m; error = true; a.render(); }
            });
        }), 14);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        PhoneTools t = P71App.get().tools;
        if (t.forwarded + t.forwardFailed > 0) {
            page.addView(Ui.note(a, "Since the app started: " + t.forwarded + " sent, " + t.forwardFailed + " not delivered."));
        }

        page.addView(Ui.section(a, "Apps"));
        PackageManager pm = a.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        Set<String> seen = new HashSet<>();
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(a.getPackageName()) || !seen.add(pkg)) continue;
            rows.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(rows, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        Set<String> allowed = s.allowedApps();
        for (String[] row : rows) {
            Switch sw = Ui.toggle(a, allowed.contains(row[1]), access && s.forwardNotifications(), row[0]);
            sw.setOnCheckedChangeListener((b, on) -> s.setAllowed(row[1], on));
            Ui.add(page, Ui.row(a, row[0], null, sw, null), 8);
        }
        page.addView(Ui.note(a, "Ongoing, grouped, and repeated alerts are skipped. The watch shows them with a message icon and the app name."));
        return page;
    }
}
