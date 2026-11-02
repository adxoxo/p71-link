package dev.p71link.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Optional connected-device foreground service. It runs only while the user keeps
 * "Stay connected" on, and it stops when the user turns that off or disconnects.
 * It does no polling; it only keeps the process alive for the existing Bluetooth link.
 */
public final class LinkService extends Service {
    static final String CHANNEL = "link";
    private static final int ID = 71;
    private final Runnable listener = this::refresh;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, LinkService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LinkService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (RuntimeException e) {
            // Missing nearby-device permission or a background start restriction. Do not run.
            stopSelf();
            return START_NOT_STICKY;
        }
        P71App.get().watch.addListener(listener);
        return START_NOT_STICKY;
    }

    private void refresh() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID, build());
    }

    private Notification build() {
        WatchManager w = P71App.get().watch;
        String text = w.connected() ? "Connected to " + (w.deviceName == null ? "your watch" : w.deviceName)
                : w.state == WatchManager.State.CONNECTING ? "Waiting for the watch" : "Not connected";
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("P71 Link")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        P71App.get().watch.removeListener(listener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
