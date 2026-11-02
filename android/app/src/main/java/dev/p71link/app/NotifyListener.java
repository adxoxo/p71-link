package dev.p71link.app;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import dev.p71link.protocol.CallTracker;
import dev.p71link.protocol.Notifications;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Forwards notifications from apps the user selected. Nothing is forwarded until the user turns
 * forwarding on and selects apps. Ongoing, group-summary, and own notifications are skipped.
 * Text is never logged or stored.
 */
public final class NotifyListener extends NotificationListenerService {
    private static final long DUPLICATE_WINDOW_MS = 15000;
    private static final int MAX_RECENT = 32;
    private static final int MAX_IN_FLIGHT = 6;
    private final ArrayDeque<long[]> recent = new ArrayDeque<>(); // [hash, time]
    private int inFlight;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        P71App app = P71App.get();
        if (app == null || sbn == null) return;
        Store store = app.store;
        Notification n = sbn.getNotification();
        if (n == null || getPackageName().equals(sbn.getPackageName())) return;
        Bundle extras = n.extras; // May be null.
        // Calls are recognised by the CallStyle call type. An update of the ringing call that lost its
        // call type still goes through the call path, where it clears the ringing.
        String key = sbn.getKey();
        Integer callType = extras != null && extras.containsKey(Notification.EXTRA_CALL_TYPE)
                ? extras.getInt(Notification.EXTRA_CALL_TYPE, CallTracker.UNKNOWN) : null;
        if (callType != null && !store.forwardCalls() && !app.calls.tracking(key)) return; // Calls not opted in.
        CharSequence caller = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        CallTracker.Update u = app.calls.onPosted(key, callType, caller == null ? null : caller.toString());
        if (u.callPath) {
            if (u.event != null && store.forwardCalls()) sendCall(app, u.event);
            return;
        }
        if (extras == null) return;
        if (!store.forwardNotifications() || !store.allowedApps().contains(sbn.getPackageName())) return;
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_LOCAL_ONLY) != 0) return;
        if (!app.watch.connected()) return;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null) body = extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = Notifications.compose(title == null ? null : title.toString(), body == null ? null : body.toString());
        int type = Notifications.SMS;
        // Only the SMS icon type is confirmed on this watch. Name the app in the text instead.
        String label = appLabel(sbn.getPackageName());
        if (Notifications.typeForPackage(sbn.getPackageName()) != Notifications.SMS && label != null) {
            text = label + ": " + text;
        }
        if (text.trim().isEmpty()) return;
        long hash = (sbn.getPackageName() + "\u0000" + text).hashCode();
        long now = System.currentTimeMillis();
        for (Iterator<long[]> it = recent.iterator(); it.hasNext(); ) {
            long[] r = it.next();
            if (now - r[1] > DUPLICATE_WINDOW_MS) it.remove();
            else if (r[0] == hash) return;
        }
        recent.add(new long[]{hash, now});
        while (recent.size() > MAX_RECENT) recent.poll();
        if (inFlight >= MAX_IN_FLIGHT) return; // Bounded: drop rather than build a backlog.
        inFlight++;
        app.watch.notifyText(text, type, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) { inFlight--; app.tools.recordForward(true); }
            @Override public void fail(String m) { inFlight--; app.tools.recordForward(false); }
        });
    }

    /** A call notification went away: the ringing call was answered, rejected, or ended. */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        P71App app = P71App.get();
        if (app == null || sbn == null) return;
        CallTracker.Event e = app.calls.removed(sbn.getKey());
        if (e != null && app.store.forwardCalls()) sendCall(app, e);
    }

    /** Ringing (type 0) or ended (type 4); a padded control chunk when no caller text was supplied. */
    private static void sendCall(P71App app, CallTracker.Event e) {
        if (!app.watch.connected()) return; // Nothing is replayed later.
        app.watch.notifyCall(e.type, e.text, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) { app.tools.recordForward(true); }
            @Override public void fail(String m) { app.tools.recordForward(false); }
        });
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
