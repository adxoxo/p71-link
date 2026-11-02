package dev.p71link.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Private local preferences. Nothing here leaves the phone except through the user's own export. */
final class Store {
    private final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("p71link", Context.MODE_PRIVATE); }

    String address() { return p.getString("address", null); }

    String name() { return p.getString("name", null); }

    void saveDevice(String address, String name) { p.edit().putString("address", address).putString("name", name).apply(); }

    void forgetDevice() {
        p.edit().remove("address").remove("name").remove("caps").remove("firmware").remove("synced").apply();
    }

    String capabilitiesHex() { return p.getString("caps", null); }

    void saveCapabilities(String hex) { p.edit().putString("caps", hex).apply(); }

    String firmware() { return p.getString("firmware", null); }

    void saveFirmware(String fw) { p.edit().putString("firmware", fw).apply(); }

    long lastTimeSync() { return p.getLong("synced", 0); }

    void saveTimeSync(long ms) { p.edit().putLong("synced", ms).apply(); }

    boolean syncTimeOnConnect() { return p.getBoolean("syncOnConnect", true); }

    void setSyncTimeOnConnect(boolean v) { p.edit().putBoolean("syncOnConnect", v).apply(); }

    boolean stayConnected() { return p.getBoolean("stayConnected", false); }

    void setStayConnected(boolean v) { p.edit().putBoolean("stayConnected", v).apply(); }

    boolean forwardNotifications() { return p.getBoolean("forward", false); }

    void setForwardNotifications(boolean v) { p.edit().putBoolean("forward", v).apply(); }

    /** Apps the user chose to forward. Empty by default. */
    Set<String> allowedApps() { return Collections.unmodifiableSet(new HashSet<>(p.getStringSet("apps", Collections.emptySet()))); }

    void setAllowed(String pkg, boolean allowed) {
        Set<String> s = new HashSet<>(p.getStringSet("apps", Collections.emptySet()));
        if (allowed) s.add(pkg);
        else s.remove(pkg);
        p.edit().putStringSet("apps", s).apply();
    }

    /** Watch music buttons control the phone's media player. Off until the user turns it on. */
    boolean musicControl() { return p.getBoolean("music", false); }

    void setMusicControl(boolean v) { p.edit().putBoolean("music", v).apply(); }

    /** The watch may reject or end calls. Off until the user turns it on. */
    boolean rejectCalls() { return p.getBoolean("rejectCalls", false); }

    void setRejectCalls(boolean v) { p.edit().putBoolean("rejectCalls", v).apply(); }

    boolean forwardCalls() { return p.getBoolean("calls", false); }

    void setForwardCalls(boolean v) { p.edit().putBoolean("calls", v).apply(); }

    String get(String key) { return p.getString(key, null); }

    void put(String key, String value) { p.edit().putString(key, value).apply(); }
}
