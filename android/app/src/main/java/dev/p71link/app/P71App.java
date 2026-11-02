package dev.p71link.app;

import android.app.Application;

/** Holds the single watch link and local store for the app process. */
public final class P71App extends Application {
    private static P71App instance;
    Store store;
    WatchManager watch;
    PhoneTools tools;
    HealthLog health;
    /** Created only when the user opens the optional catalog. */
    FaceCatalog catalog;
    /** Call notification state for ringing and ended events. */
    final dev.p71link.protocol.CallTracker calls = new dev.p71link.protocol.CallTracker();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        store = new Store(this);
        watch = new WatchManager(this, store);
        health = new HealthLog(this);
        tools = new PhoneTools(this, watch, store);
        watch.setUnsolicitedHandler(tools::handle);
    }

    static P71App get() { return instance; }
}
