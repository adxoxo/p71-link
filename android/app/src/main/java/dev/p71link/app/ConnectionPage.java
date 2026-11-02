package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

/** Connection preferences. No polling and no scans run while the phone is idle. */
final class ConnectionPage implements MainActivity.Page {
    private String saverMessage;
    private boolean saverBusy;
    @Override
    public View build(MainActivity a) {
        Store s = a.store;
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Connection and battery"));
        page.addView(Ui.subtitle(a, "P71 Link connects when you ask and reads only what a screen needs."));
        Switch stay = Ui.toggle(a, s.stayConnected(), true, "Stay connected");
        stay.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                a.withBluetooth(() -> a.withNotificationPermission(() -> {
                    w.setStayConnected(true);
                    a.render();
                }));
            } else {
                w.setStayConnected(false);
                a.render();
            }
        });
        Ui.add(page, Ui.row(a, "Stay connected", "Keeps the link for alerts while the app is closed. Shows a status notification.", stay, null), 0);
        Switch sync = Ui.toggle(a, s.syncTimeOnConnect(), true, "Set watch time on connect");
        sync.setOnCheckedChangeListener((b, on) -> s.setSyncTimeOnConnect(on));
        Ui.add(page, Ui.row(a, "Set watch time on connect", "One time sync per connection", sync, null), 10);
        if (w.connected() && w.caps != null && w.caps.yawei) {
            if (w.batterySaver == null) {
                Ui.add(page, Ui.row(a, "Battery saver", saverMessage == null ? "Read from the watch" : saverMessage, null,
                        v -> w.loadBatterySaver(new WatchManager.Result<Boolean>() {
                            @Override public void ok(Boolean x) { saverMessage = null; a.render(); }
                            @Override public void fail(String m) { saverMessage = m; a.render(); }
                        })), 10);
            } else {
                boolean on = w.batterySaver;
                Switch saver = Ui.toggle(a, on, !saverBusy && !w.needsFreshRead, "Battery saver");
                saver.setOnCheckedChangeListener((b, v) -> {
                    if (v == on) return;
                    saverBusy = true;
                    a.render();
                    w.setBatterySaver(v, new WatchManager.Result<Boolean>() {
                        @Override public void ok(Boolean x) {
                            saverBusy = false;
                            saverMessage = "After the change the watch reported " + (x ? "on" : "off")
                                    + ". Not confirmed as stored; reconnect and read to confirm.";
                            a.render();
                        }
                        @Override public void fail(String m) { saverBusy = false; saverMessage = m; a.render(); }
                    });
                });
                String sub = saverMessage != null ? saverMessage
                        : w.batterySaverUnconfirmed ? "Reported after a change, not confirmed" : "Read from the watch";
                Ui.add(page, Ui.row(a, "Battery saver", sub, saver, null), 10);
            }
        }
        if (w.connected() || w.state == WatchManager.State.CONNECTING) {
            Ui.add(page, Ui.button(a, "Disconnect", false, v -> w.userDisconnect()), 16);
        } else if (w.hasSavedWatch()) {
            Ui.add(page, Ui.button(a, "Connect", true, v -> a.withBluetooth(w::userConnect)), 16);
        }
        if (w.hasSavedWatch()) {
            Ui.add(page, Ui.button(a, "Forget this watch", false, v -> a.confirm("Forget this watch?",
                    "P71 Link disconnects and forgets the watch. Health data saved on this phone stays.", "Forget", () -> {
                        w.setStayConnected(false);
                        w.forget();
                    })), 10);
        }
        page.addView(Ui.note(a, "A lost link retries once. With Stay connected on, Android waits for the watch without app scans."));
        return page;
    }
}
