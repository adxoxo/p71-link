package dev.p71link.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import dev.p71link.protocol.Alarms;
import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.Contacts;
import dev.p71link.protocol.Capabilities;
import dev.p71link.protocol.CommandQueue;
import dev.p71link.protocol.Health;
import dev.p71link.protocol.Hex;
import dev.p71link.protocol.Measurement;
import dev.p71link.protocol.ResourceTransfer;
import dev.p71link.protocol.SessionGate;
import dev.p71link.protocol.Settings;
import dev.p71link.protocol.ShortProtocol;
import dev.p71link.protocol.WatchClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * App-wide watch link. UI state lives on the main thread. GATT sessions and the command queue
 * live on one Bluetooth thread. There is no polling at rest: reads happen when a screen asks.
 */
@SuppressLint("MissingPermission")
final class WatchManager implements GattSession.Owner {
    enum State { IDLE, SCANNING, CONNECTING, READY }

    interface Result<T> {
        void ok(T value);

        void fail(String message);
    }

    interface Progress { void update(int done, int total); }

    static final long SCAN_MS = 15000;

    private final Context context;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler ble;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ---- Main-thread state ----
    State state = State.IDLE;
    String status;
    Integer battery;
    Capabilities caps;
    String firmware;
    String deviceName;
    Settings.Dnd dnd;
    Settings.Wrist wrist;
    Settings.Display display;
    Settings.TimeFormat timeFormat;
    Settings.Units units;
    Settings.HeartRateAuto hrAuto;
    String settingsError;
    List<Alarms.Alarm> alarms;
    Settings.Sedentary sedentary;
    Settings.Goals goals;
    List<BulkProtocol.LayoutEntry> layout;
    Boolean batterySaver;
    /** True when batterySaver came from a reading after a change; such a reading cannot be tied to the change. */
    boolean batterySaverUnconfirmed;
    /** Drink slots 0-7 as read; a null entry means that slot did not answer. */
    Settings.Alarm[] drinks;
    boolean settingsLoading;
    List<BulkProtocol.InstalledFace> faces;
    Integer faceIndex;
    /** Set after an ambiguous result: settings shown before it are not trusted until read again. */
    boolean needsFreshRead;
    /** Main-thread time the current session finished setup, 0 when none. */
    long sessionReadyAt;
    /** True when faceIndex came from a 75 read in the current session. */
    boolean faceIndexFresh;
    /**
     * Face operations owned here, not by a screen, so leaving a page neither loses nor duplicates them.
     * A list read, a selection and an upload exclude each other. A new session releases all three.
     */
    final dev.p71link.protocol.ExclusiveOp faceListOp = new dev.p71link.protocol.ExclusiveOp(),
            faceSelectOp = new dev.p71link.protocol.ExclusiveOp();
    /**
     * The face resource transfer, its progress and last outcome. Starting one forgets the installed list,
     * its order and the current-face index, so rows and the Current badge need fresh 35 and 75 replies.
     */
    final dev.p71link.protocol.FaceTransferOwner faceUpload = new dev.p71link.protocol.FaceTransferOwner(() -> {
        faces = null;
        faceIndex = null;
        faceIndexFresh = false;
    });

    // ---- Bluetooth-thread state ----
    private volatile GattSession session;
    private int generation;
    /** Main-thread epoch gate; see SessionGate. */
    private final SessionGate gate = new SessionGate();
    private ResourceTransfer transfer;
    private Measurement measurement;
    private dev.p71link.protocol.ContactTransfer contactTransfer;
    private WatchClient contactTransferClient;
    private WatchClient measurementClient;

    private boolean userWantsLink;
    private int reconnectBudget;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private final Runnable scanTimeout = this::scanTimedOut;
    private UnsolicitedHandler unsolicited;

    interface UnsolicitedHandler { void handle(int command, byte[] frame); }

    WatchManager(Context context, Store store) {
        this.context = context.getApplicationContext();
        this.store = store;
        HandlerThread t = new HandlerThread("p71-ble");
        t.start();
        ble = new Handler(t.getLooper());
        deviceName = store.name();
        firmware = store.firmware();
        String capsHex = store.capabilitiesHex();
        if (capsHex != null) {
            try {
                caps = Capabilities.parse(Hex.decode(capsHex));
            } catch (RuntimeException ignored) {
                caps = null;
            }
        }
    }

    void setUnsolicitedHandler(UnsolicitedHandler h) { unsolicited = h; }

    void addListener(Runnable r) { listeners.addIfAbsent(r); }

    void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    void notifyChanged() { changed(); }

    private void onMain(Runnable r) { main.post(() -> { r.run(); changed(); }); }

    /** Post to main only while the session's epoch is still current. */
    private void onMainFor(GattSession s, Runnable r) {
        final int token = s.epoch;
        main.post(() -> {
            if (!gate.current(token)) return;
            r.run();
            changed();
        });
    }

    /** The only watch this build binds to on first connection. */
    static final String TARGET_NAME = "P71J_CEC9";

    // ---- User intent: every Connect and Disconnect route goes through these. ----

    /** Explicit user connect. Restarts the optional link service when the user enabled it. */
    void userConnect() {
        connect();
        if (store.stayConnected()) startLinkService();
    }

    /** Explicit user disconnect from any screen. Always stops the link service. */
    void userDisconnect() {
        disconnect();
        LinkService.stop(context);
    }

    /** Turn the stay-connected preference on or off and apply it now. */
    void setStayConnected(boolean on) {
        store.setStayConnected(on);
        if (on && state != State.IDLE) startLinkService();
        if (!on) LinkService.stop(context);
    }

    private void startLinkService() {
        try {
            LinkService.start(context);
        } catch (RuntimeException e) {
            // Android refused the start (for example, from the background). The link still works in the app.
            status = "Android did not allow the background connection to start. Open the app to reconnect.";
            changed();
        }
    }

    boolean connected() { return state == State.READY; }

    boolean hasSavedWatch() { return store.address() != null; }

    // ---- Connection --------------------------------------------------------------

    private BluetoothAdapter adapter() {
        BluetoothManager m = context.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }

    /** Uses the saved watch or a bounded scan for the exact target. Call userConnect from screens. */
    private void connect() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            status = "Turn on Bluetooth to connect.";
            changed();
            return;
        }
        if (state == State.CONNECTING || state == State.READY || state == State.SCANNING) return;
        userWantsLink = true;
        reconnectBudget = 1;
        String address = store.address();
        if (address != null && store.name() != null && !SessionGate.isTarget(store.name(), TARGET_NAME)) {
            // A watch saved by an older build that is not this build's target: do not write to it.
            store.forgetDevice();
            address = null;
        }
        if (address != null) {
            startSession(a.getRemoteDevice(address), false);
        } else {
            startScan(a);
        }
    }

    private void startScan(BluetoothAdapter a) {
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) {
            status = "Bluetooth scanning is not available.";
            changed();
            return;
        }
        state = State.SCANNING;
        status = "Looking for " + TARGET_NAME + ". Keep it close and awake.";
        final int token = gate.begin();
        changed();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                if (scanCallback != this || !gate.current(token)) return;
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                // Bind only the exact target. Another nearby P71 never receives a write.
                if (SessionGate.isTarget(name, TARGET_NAME)) {
                    stopScan();
                    store.saveDevice(d.getAddress(), name);
                    deviceName = name;
                    startSession(d, false);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (scanCallback != this || !gate.current(token)) return;
                stopScan();
                state = State.IDLE;
                status = "Bluetooth scan failed. Try again.";
                changed();
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCallback);
        main.postDelayed(scanTimeout, SCAN_MS);
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Bluetooth turned off.
            }
        }
        scanCallback = null;
    }

    private void scanTimedOut() {
        stopScan();
        if (state == State.SCANNING) {
            state = State.IDLE;
            status = "Could not find " + TARGET_NAME + ". Wake the watch and try again.";
            changed();
        }
    }

    private void startSession(BluetoothDevice d, boolean background) {
        final int token = gate.begin();
        releaseFaceOps();
        state = State.CONNECTING;
        status = background ? "Waiting for the watch to come back in range." : "Connecting…";
        if (deviceName == null) deviceName = store.name();
        changed();
        ble.post(() -> {
            if (session != null) session.close("Replaced");
            GattSession s = new GattSession(context, ble, d, ++generation, token, this, background);
            session = s;
            s.start();
        });
    }

    /** Stop everything, no reconnect. Call userDisconnect from screens. */
    private void disconnect() {
        userWantsLink = false;
        gate.invalidate();
        releaseFaceOps();
        stopScan();
        ble.post(() -> {
            if (transfer != null) transfer.stop();
            if (session != null) session.close("Disconnected");
        });
        state = State.IDLE;
        status = "Disconnected.";
        changed();
    }

    void forget() {
        userDisconnect();
        store.forgetDevice();
        deviceName = null;
        firmware = null;
        caps = null;
        battery = null;
        clearWatchState();
        changed();
    }

    private void clearWatchState() {
        dnd = null;
        wrist = null;
        display = null;
        timeFormat = null;
        units = null;
        hrAuto = null;
        faces = null;
        faceIndex = null;
        faceIndexFresh = false;
        alarms = null;
        sedentary = null;
        goals = null;
        layout = null;
        batterySaver = null;
        batterySaverUnconfirmed = false;
        drinks = null;
        settingsError = null;
    }

    // ---- GattSession.Owner (Bluetooth thread) ----------------------------------------

    @Override
    public void onSessionReady(GattSession s) {
        if (s != session) return;
        String fw = s.firmware();
        WatchClient c = s.client();
        onMainFor(s, () -> {
            state = State.CONNECTING;
            status = "Reading your watch…";
            if (fw != null && !fw.isEmpty()) {
                firmware = fw;
                store.saveFirmware(fw);
            }
            needsFreshRead = false;
            clearWatchState();
        });
        Runnable finish = () -> c.request("battery", ShortProtocol.batteryRequest(), ShortProtocol.NO_ACTION,
                ShortProtocol::battery, new CommandQueue.Callback<Integer>() {
                    @Override public void onResult(Integer v) { ready(s, v); }
                    @Override public void onError(Exception e) { ready(s, null); }
                });
        Runnable bind = () -> c.request("phone", ShortProtocol.phoneOs(Build.MODEL), ShortProtocol.NO_ACTION, f -> null,
                new CommandQueue.Callback<Object>() {
                    @Override public void onResult(Object v) { finish.run(); }
                    @Override public void onError(Exception e) { if (!s.ready()) return; finish.run(); }
                });
        if (store.syncTimeOnConnect()) {
            syncClock(s.epoch, c, new CommandQueue.Callback<Capabilities>() {
                @Override public void onResult(Capabilities v) { bind.run(); }
                @Override public void onError(Exception e) { if (s.ready()) bind.run(); }
            });
        } else {
            bind.run();
        }
    }

    private void ready(GattSession s, Integer level) {
        if (s != session || !s.ready()) return;
        reconnectBudget = 1;
        onMainFor(s, () -> {
            sessionReadyAt = System.currentTimeMillis();
            state = State.READY;
            status = null;
            battery = level;
            // The user just connected; read the Watch screen settings once. No polling follows.
            loadWatchSettings();
        });
    }

    @Override
    public void onSessionClosed(GattSession s, String reason, boolean ambiguous) {
        if (s != session) return;
        session = null;
        transfer = null;
        boolean retry = userWantsLink && (ambiguous || store.stayConnected()) && reconnectBudget > 0;
        if (retry) reconnectBudget--;
        BluetoothDevice d = s.device;
        onMainFor(s, () -> {
            state = State.IDLE;
            status = reason;
            battery = null;
            if (ambiguous) needsFreshRead = true;
            clearWatchState();
            if (retry && userWantsLink) {
                // One bounded reconnect. Stay-connected mode lets the Bluetooth controller wait for the watch.
                startSession(d, !ambiguous && store.stayConnected());
            } else {
                userWantsLink = false;
                LinkService.stop(context);
            }
        });
    }

    @Override
    public void onUnsolicited(GattSession s, CommandQueue.Channel channel, byte[] frame) {
        if (s != session || channel != CommandQueue.Channel.SHORT) return;
        if (!ShortProtocol.checksumValid(frame)) return;
        int command = frame[0] & 0xff;
        WatchClient c = s.client();
        if (command == ShortProtocol.PACKAGE_LENGTH && c != null) {
            c.onPackageLength(ShortProtocol.packageLength(frame), s.attPayload());
            return;
        }
        if (command == ShortProtocol.BATTERY) {
            try {
                int level = ShortProtocol.battery(frame);
                onMainFor(s, () -> battery = level);
            } catch (RuntimeException ignored) {
                // Not a valid battery value.
            }
            return;
        }
        UnsolicitedHandler h = unsolicited;
        final int token = s.epoch;
        if (h != null) main.post(() -> {
            if (gate.current(token)) h.handle(command, frame);
        });
    }

    // ---- Requests ----------------------------------------------------------------------

    private <T> void op(Result<T> r, BiConsumer<WatchClient, CommandQueue.Callback<T>> body) {
        ble.post(() -> {
            GattSession s = session;
            WatchClient c = s == null ? null : s.client();
            if (c == null) {
                main.post(() -> r.fail("The watch is not connected."));
                return;
            }
            final int token = s.epoch;
            body.accept(c, new CommandQueue.Callback<T>() {
                @Override public void onResult(T v) {
                    main.post(() -> {
                        // A result from an older session is not current; report it as not delivered.
                        if (gate.current(token)) r.ok(v);
                        else r.fail("The watch is not connected.");
                        changed();
                    });
                }

                @Override public void onError(Exception e) {
                    main.post(() -> {
                        if (gate.current(token) && Errors.ambiguous(e)) needsFreshRead = true;
                        r.fail(Errors.message(e));
                        changed();
                    });
                }
            });
        });
    }

    private void syncClock(int token, WatchClient c, CommandQueue.Callback<Capabilities> cb) {
        c.request("clock", ShortProtocol.clock(LocalDateTime.now(), 1), ShortProtocol.NO_ACTION, Capabilities::parse,
                new CommandQueue.Callback<Capabilities>() {
                    @Override public void onResult(Capabilities v) {
                        long now = System.currentTimeMillis();
                        main.post(() -> {
                            if (!gate.current(token)) return;
                            changed();
                            caps = v;
                            store.saveCapabilities(Hex.encode(v.raw()));
                            store.saveTimeSync(now);
                        });
                        cb.onResult(v);
                    }

                    @Override public void onError(Exception e) { cb.onError(e); }
                });
    }

    void syncTime(Result<Capabilities> r) {
        op(r, (c, cb) -> {
            GattSession s = session;
            syncClock(s == null ? -1 : s.epoch, c, cb);
        });
    }

    void refreshBattery(Result<Integer> r) {
        op(new Result<Integer>() {
            @Override public void ok(Integer v) { battery = v; r.ok(v); }
            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.request("battery", ShortProtocol.batteryRequest(), ShortProtocol.NO_ACTION, ShortProtocol::battery, cb));
    }

    /** Send forecast days in order. Each frame needs the watch's 1A reply (delivery, not proof of display). */
    void sendWeather(List<Settings.WeatherDay> days, Result<Integer> r) {
        op(r, (c, cb) -> sendWeatherDay(c, days, 0, cb));
    }

    private void sendWeatherDay(WatchClient c, List<Settings.WeatherDay> days, int i, CommandQueue.Callback<Integer> cb) {
        if (i >= days.size()) {
            cb.onResult(days.size());
            return;
        }
        c.request("weather", days.get(i).write(i), ShortProtocol.NO_ACTION, f -> null, new CommandQueue.Callback<Object>() {
            @Override public void onResult(Object v) { sendWeatherDay(c, days, i + 1, cb); }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** Cycle settings have no read-back and no defined reply: success means the write was delivered. */
    void sendCycle(Settings.Cycle c, Result<Void> r) {
        op(r, (cl, cb) -> cl.send("cycle", c.write(), cb));
    }

    void findWatch(Result<Void> r) { op(r, (c, cb) -> c.send("find watch", ShortProtocol.findWatch(), cb)); }

    /** Reads the stored Watch-screen settings once per request. */
    void loadWatchSettings() {
        if (!connected() || settingsLoading) return;
        settingsLoading = true;
        settingsError = null;
        changed();
        op(new Result<Settings.Dnd>() {
            @Override public void ok(Settings.Dnd v) {
                dnd = v;
                readWrist();
            }

            @Override public void fail(String m) { settingsFailed(m); }
        }, (c, cb) -> c.request("dnd", Settings.readDnd(), ShortProtocol.READ, Settings.Dnd::parse, cb));
    }

    private void readWrist() {
        op(new Result<Settings.Wrist>() {
            @Override public void ok(Settings.Wrist v) {
                wrist = v;
                readDisplay();
            }

            @Override public void fail(String m) { settingsFailed(m); }
        }, (c, cb) -> c.request("wrist", Settings.readWrist(), ShortProtocol.READ, Settings.Wrist::parse, cb));
    }

    private void readDisplay() {
        op(new Result<Settings.Display>() {
            @Override public void ok(Settings.Display v) {
                display = v;
                readTimeFormat();
            }

            @Override public void fail(String m) { settingsFailed(m); }
        }, (c, cb) -> c.request("display", Settings.readDisplay(), ShortProtocol.READ, Settings.Display::parse, cb));
    }

    private void readTimeFormat() {
        op(new Result<Settings.TimeFormat>() {
            @Override public void ok(Settings.TimeFormat v) {
                timeFormat = v;
                readUnits();
            }

            @Override public void fail(String m) { settingsFailed(m); }
        }, (c, cb) -> c.request("time format", Settings.readTimeFormat(), ShortProtocol.READ, Settings.TimeFormat::parse, cb));
    }

    private void readUnits() {
        op(new Result<Settings.Units>() {
            @Override public void ok(Settings.Units v) {
                units = v;
                settingsLoading = false;
                needsFreshRead = false;
            }

            @Override public void fail(String m) {
                // Some firmware may not answer the temperature-unit read; other settings stay valid.
                units = null;
                settingsLoading = false;
                needsFreshRead = false;
            }
        }, (c, cb) -> c.request("units", Settings.readUnits(), ShortProtocol.READ, Settings.Units::parse, cb));
    }

    private void settingsFailed(String m) {
        settingsLoading = false;
        settingsError = m;
    }

    /** Write a setting, then read it back. The readback, not the write echo, updates the screen. */
    private <T> void writeVerified(String label, byte[] write, byte[] read, java.util.function.Function<byte[], T> parse,
                                   java.util.function.BiPredicate<T, T> same, T wanted, java.util.function.Consumer<T> apply,
                                   Result<T> r) {
        if (needsFreshRead) {
            r.fail("Read the watch settings again before you change them.");
            return;
        }
        op(new Result<T>() {
            @Override public void ok(T got) {
                apply.accept(got);
                if (same.test(wanted, got)) r.ok(got);
                else r.fail("The watch kept a different value. The screen shows what it stored.");
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.writeThenRead(label, write, ShortProtocol.WRITE_ACTION, read, ShortProtocol.READ, parse, cb));
    }

    void setDnd(Settings.Dnd wanted, Result<Settings.Dnd> r) {
        writeVerified("dnd", wanted.write(), Settings.readDnd(), Settings.Dnd::parse, Settings.Dnd::sameStored, wanted,
                v -> dnd = v, r);
    }

    void setWrist(Settings.Wrist wanted, Result<Settings.Wrist> r) {
        writeVerified("wrist", wanted.write(), Settings.readWrist(), Settings.Wrist::parse, Settings.Wrist::sameStored,
                wanted, v -> wrist = v, r);
    }

    void setDisplay(Settings.Display wanted, Result<Settings.Display> r) {
        writeVerified("display", wanted.write(), Settings.readDisplay(), Settings.Display::parse,
                Settings.Display::sameStored, wanted, v -> display = v, r);
    }

    void setTimeFormat(Settings.TimeFormat wanted, Result<Settings.TimeFormat> r) {
        writeVerified("time format", wanted.write(), Settings.readTimeFormat(), Settings.TimeFormat::parse,
                Settings.TimeFormat::sameStored, wanted, v -> timeFormat = v, r);
    }

    void setUnits(Settings.Units wanted, Result<Settings.Units> r) {
        writeVerified("units", wanted.write(), Settings.readUnits(), Settings.Units::parse,
                (a, b) -> b != null && a.celsius == b.celsius && a.enabled == b.enabled, wanted, v -> units = v, r);
    }

    // ---- Health settings ----

    void loadHealthSettings(Result<Void> r) {
        op(new Result<Settings.HeartRateAuto>() {
            @Override public void ok(Settings.HeartRateAuto v) { hrAuto = v; r.ok(null); }
            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.request("hr auto", Settings.readHeartRateAuto(), ShortProtocol.READ, Settings.HeartRateAuto::parse, cb));
    }

    void setHeartRateAuto(boolean on, Result<Settings.HeartRateAuto> r) {
        if (hrAuto == null) {
            r.fail("Read the setting first.");
            return;
        }
        Settings.HeartRateAuto wanted = hrAuto.withEnabled(on);
        writeVerified("hr auto", wanted.write(), Settings.readHeartRateAuto(), Settings.HeartRateAuto::parse,
                (a, b) -> a.enabled == b.enabled && a.rawInterval == b.rawInterval, wanted, v -> hrAuto = v, r);
    }

    /** Manual measurement: start, wait for a positive result or an error, always stop. */
    /** Heart-rate measurement only. One at a time across the app; see Measurement. */
    void measure(int type, Result<Health.MeasurementUpdate> r) {
        if (type != Health.TYPE_HEART_RATE) {
            r.fail("This measurement is not offered.");
            return;
        }
        op(r, (c, cb) -> {
            if (measurement == null || measurementClient != c) {
                measurementClient = c;
                measurement = new Measurement(c, (task, delay) -> {
                    Runnable run = task::run;
                    ble.postDelayed(run, delay);
                    return () -> ble.removeCallbacks(run);
                });
            }
            measurement.start(type, cb);
        });
    }

    <R extends WatchClient.FrameReader> void readHistory(String label, byte[] frame, R reader, Result<R> r) {
        op(r, (c, cb) -> c.history(label, frame, reader, cb));
    }

    /** One bulk request; the result is the validated payload of the matching reply. */
    void readBulk(String label, byte[] frame, int replyCommand, Result<byte[]> r) {
        op(new Result<byte[]>() {
            @Override public void ok(byte[] f) {
                byte[] p;
                try {
                    p = BulkProtocol.payload(f, replyCommand);
                } catch (RuntimeException e) {
                    r.fail(Errors.message(e));
                    return;
                }
                r.ok(p);
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> {
            if (session == null || !session.hasBulk()) {
                cb.onError(new IllegalStateException("This connection has no history channel."));
                return;
            }
            c.bulk(label, frame, replyCommand, WatchClient.BULK_TIMEOUT_MS, cb);
        });
    }

    void readToday(Result<Health.DayActivity> r) {
        op(r, (c, cb) -> c.request("today", Health.readTodaySteps(), ShortProtocol.NO_ACTION,
                f -> Health.todaySteps(f, java.time.LocalDate.now()), cb));
    }

    // ---- Alarms (bulk 2C, whole list) ----

    void loadAlarms(Result<List<Alarms.Alarm>> r) {
        op(new Result<byte[]>() {
            @Override public void ok(byte[] p) {
                try {
                    alarms = Alarms.parse(p);
                } catch (RuntimeException e) {
                    r.fail(Errors.message(e));
                    return;
                }
                r.ok(alarms);
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> readAlarmList(c, cb));
    }

    private void readAlarmList(WatchClient c, CommandQueue.Callback<byte[]> cb) {
        GattSession s = session;
        if (s == null || !s.hasBulk()) {
            cb.onError(new IllegalStateException("This connection has no alarm channel."));
            return;
        }
        c.bulkWhere("alarm read", Alarms.readRequest(), BulkProtocol.ALARMS, p -> p.length > 0 && (p[0] & 255) == 1, cb);
    }

    /** Write the whole list, then read it back. Success needs every record to match. */
    void saveAlarms(List<Alarms.Alarm> wanted, Result<List<Alarms.Alarm>> r) {
        if (needsFreshRead || alarms == null) {
            r.fail("Read the alarms from the watch again before you change them.");
            return;
        }
        byte[] frame;
        try {
            frame = Alarms.writeRequest(wanted);
        } catch (IllegalArgumentException e) {
            r.fail(Errors.message(e));
            return;
        }
        op(new Result<byte[]>() {
            @Override public void ok(byte[] p) {
                List<Alarms.Alarm> got;
                try {
                    got = Alarms.parse(p);
                } catch (RuntimeException e) {
                    needsFreshRead = true;
                    r.fail(Errors.message(e));
                    return;
                }
                alarms = got;
                if (got.equals(wanted)) r.ok(got);
                else r.fail("The watch kept a different alarm list. The screen shows what it stored.");
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.bulkSend("alarm write", frame, new CommandQueue.Callback<Void>() {
            @Override public void onResult(Void v) { readAlarmList(c, cb); }
            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    // ---- Move reminder (write 25 has no reply; read 26) ----

    void loadSedentary(Result<Settings.Sedentary> r) {
        op(new Result<Settings.Sedentary>() {
            @Override public void ok(Settings.Sedentary v) { sedentary = v; r.ok(v); }
            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.request("move read", Settings.readSedentary(), ShortProtocol.NO_ACTION, Settings.Sedentary::parse, cb));
    }

    void saveSedentary(Settings.Sedentary wanted, Result<Settings.Sedentary> r) {
        if (needsFreshRead || sedentary == null) {
            r.fail("Read the reminder from the watch again before you change it.");
            return;
        }
        byte[] write;
        try {
            write = wanted.write();
        } catch (IllegalArgumentException e) {
            r.fail(Errors.message(e));
            return;
        }
        op(new Result<Settings.Sedentary>() {
            @Override public void ok(Settings.Sedentary got) {
                sedentary = got;
                boolean same = got.startHour == wanted.startHour && got.startMinute == wanted.startMinute
                        && got.endHour == wanted.endHour && got.endMinute == wanted.endMinute
                        && got.weekMask == wanted.weekMask && got.cycleMinutes == wanted.cycleMinutes;
                if (same) r.ok(got);
                else r.fail("The watch kept a different reminder. The screen shows what it stored.");
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.send("move write", write, new CommandQueue.Callback<Void>() {
            @Override public void onResult(Void v) {
                c.request("move readback", Settings.readSedentary(), ShortProtocol.NO_ACTION, Settings.Sedentary::parse, cb);
            }

            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    // ---- Goals (21) ----

    void loadGoals(Result<Settings.Goals> r) {
        op(new Result<Settings.Goals>() {
            @Override public void ok(Settings.Goals v) { goals = v; r.ok(v); }
            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.request("goals", Settings.readGoals(), ShortProtocol.READ, Settings.Goals::parse, cb));
    }

    /** Step goal only. Every other stored goal field is written back unchanged and compared. */
    void setStepGoal(int steps, Result<Settings.Goals> r) {
        if (goals == null) {
            r.fail("Read the goals first.");
            return;
        }
        Settings.Goals wanted;
        try {
            wanted = goals.withSteps(steps);
        } catch (IllegalArgumentException e) {
            r.fail(Errors.message(e));
            return;
        }
        writeVerified("goals", wanted.write(), Settings.readGoals(), Settings.Goals::parse, Settings.Goals::sameStored,
                wanted, v -> goals = v, r);
    }

    // ---- Contacts (bulk 29, full-list replacement) ----

    void uploadContacts(List<Contacts.Contact> list, Progress progress, Result<Void> r) {
        int max = caps == null ? 20 : caps.maximumContacts;
        op(r, (c, cb) -> {
            GattSession s = session;
            if (s == null || !s.hasBulk()) {
                cb.onError(new IllegalStateException("This connection has no contact channel."));
                return;
            }
            // One transfer object per session: a second list is refused until the first one ends.
            if (contactTransfer == null || contactTransferClient != c) {
                contactTransferClient = c;
                contactTransfer = new dev.p71link.protocol.ContactTransfer(c);
            }
            contactTransfer.send(list, max, (d, n) -> main.post(() -> progress.update(d, n)), cb);
        });
    }

    // ---- Drink reminders: read 28 per slot, write 27 (no reply), verified by re-read ----

    void loadDrinks(Result<Settings.Alarm[]> r) {
        Settings.Alarm[] out = new Settings.Alarm[Settings.Alarm.DRINK_SLOTS];
        readDrink(0, out, r);
    }

    private void readDrink(int slot, Settings.Alarm[] out, Result<Settings.Alarm[]> r) {
        if (slot >= out.length) {
            drinks = out;
            r.ok(out);
            return;
        }
        op(new Result<Settings.Alarm>() {
            @Override public void ok(Settings.Alarm v) { out[slot] = v; readDrink(slot + 1, out, r); }

            @Override public void fail(String m) {
                if (!connected()) { r.fail(m); return; }
                out[slot] = null; // A rejected slot stays unavailable, never filled in.
                readDrink(slot + 1, out, r);
            }
        }, (c, cb) -> c.request("drink read", Settings.Alarm.read(true, slot), ShortProtocol.NO_ACTION,
                f -> Settings.Alarm.parse(true, f, slot), cb));
    }

    void saveDrink(Settings.Alarm wanted, Result<Settings.Alarm> r) {
        if (needsFreshRead || drinks == null || drinks[wanted.index] == null) {
            r.fail("Read the reminders from the watch again before you change them.");
            return;
        }
        op(new Result<Settings.Alarm>() {
            @Override public void ok(Settings.Alarm got) {
                drinks[wanted.index] = got;
                if (wanted.sameStored(got)) r.ok(got);
                else r.fail("The watch kept a different reminder. The screen shows what it stored.");
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.send("drink write", wanted.write(true), new CommandQueue.Callback<Void>() {
            @Override public void onResult(Void v) {
                c.request("drink readback", Settings.Alarm.read(true, wanted.index), ShortProtocol.NO_ACTION,
                        f -> Settings.Alarm.parse(true, f, wanted.index), cb);
            }

            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    // ---- Battery saver (76): read [0], write [1, on], state at payload[1] ----

    /** Pause after the write so a reply to it is less likely to meet the read request. It does not prove which reply is which. */
    static final long SAVER_SETTLE_MS = 800;

    void loadBatterySaver(Result<Boolean> r) {
        op(new Result<Boolean>() {
            @Override public void ok(Boolean v) { batterySaver = v; batterySaverUnconfirmed = false; r.ok(v); }
            @Override public void fail(String m) { batterySaver = null; r.fail(m); }
        }, (c, cb) -> c.request("saver read", Settings.readBatterySaver(), ShortProtocol.NO_ACTION, Settings::batterySaver, cb));
    }

    void setBatterySaver(boolean on, Result<Boolean> r) {
        if (needsFreshRead || batterySaver == null) {
            r.fail("Read the setting from the watch again before you change it.");
            return;
        }
        op(new Result<Boolean>() {
            /**
             * The reply after the change has no field tying it to the change, and the pause is not proof.
             * The result is reported as unconfirmed; a read on a new connection confirms it.
             */
            @Override public void ok(Boolean got) {
                batterySaver = got;
                batterySaverUnconfirmed = true;
                r.ok(got);
            }

            @Override public void fail(String m) { batterySaver = null; batterySaverUnconfirmed = false; r.fail(m); }
        }, (c, cb) -> c.send("saver write", Settings.writeBatterySaver(on), new CommandQueue.Callback<Void>() {
            @Override public void onResult(Void v) {
                ble.postDelayed(() -> c.request("saver readback", Settings.readBatterySaver(), ShortProtocol.NO_ACTION,
                        Settings::batterySaver, cb), SAVER_SETTLE_MS);
            }

            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    // ---- Custom face layout (bulk 3A) ----

    private void readLayout(WatchClient c, CommandQueue.Callback<byte[]> cb) {
        GattSession s = session;
        if (s == null || !s.hasBulk()) {
            cb.onError(new IllegalStateException("This connection has no face channel."));
            return;
        }
        c.bulkWhere("layout read", BulkProtocol.readLayoutRequest(), BulkProtocol.LAYOUT, BulkProtocol::isLayoutReadPayload, cb);
    }

    void loadLayout(Result<List<BulkProtocol.LayoutEntry>> r) {
        op(new Result<byte[]>() {
            @Override public void ok(byte[] p) {
                try {
                    layout = BulkProtocol.layoutRead(BulkProtocol.frame(BulkProtocol.LAYOUT, p));
                } catch (RuntimeException e) {
                    layout = null;
                    r.fail(Errors.message(e));
                    return;
                }
                r.ok(layout);
            }

            @Override public void fail(String m) { r.fail(m); }
        }, this::readLayout);
    }

    void saveLayout(List<BulkProtocol.LayoutEntry> wanted, Result<List<BulkProtocol.LayoutEntry>> r) {
        if (needsFreshRead || layout == null) {
            r.fail("Read the layout from the watch again before you change it.");
            return;
        }
        int w = caps != null && caps.displayWidth > 0 ? caps.displayWidth : PhotoPage.DEFAULT_W;
        int h = caps != null && caps.displayHeight > 0 ? caps.displayHeight : PhotoPage.DEFAULT_H;
        byte[] frame;
        try {
            frame = BulkProtocol.layoutWrite(w, h, wanted);
        } catch (IllegalArgumentException e) {
            r.fail(Errors.message(e));
            return;
        }
        op(new Result<byte[]>() {
            @Override public void ok(byte[] p) {
                List<BulkProtocol.LayoutEntry> got;
                try {
                    got = BulkProtocol.layoutRead(BulkProtocol.frame(BulkProtocol.LAYOUT, p));
                } catch (RuntimeException e) {
                    needsFreshRead = true;
                    r.fail(Errors.message(e));
                    return;
                }
                layout = got;
                boolean same = got.size() == wanted.size();
                for (int i = 0; same && i < got.size(); i++) {
                    BulkProtocol.LayoutEntry x = got.get(i), y = wanted.get(i);
                    same = x.type == y.type && x.x == y.x && x.y == y.y && x.rgb == y.rgb;
                }
                if (same) r.ok(got);
                else r.fail("The watch kept a different layout. The screen shows what it stored.");
            }

            @Override public void fail(String m) { r.fail(m); }
        }, (c, cb) -> c.bulkSend("layout write", frame, new CommandQueue.Callback<Void>() {
            @Override public void onResult(Void v) { readLayout(c, cb); }
            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    // ---- Faces -------------------------------------------------------------------------

    boolean bulkAvailable() {
        GattSession s = session;
        return s != null && s.hasBulk();
    }

    /** True while a face list read, selection or upload is running. */
    boolean faceBusy() { return faceListOp.active() || faceSelectOp.active() || faceUpload.active(); }

    /**
     * Identity of the ready session, or 0. Each connection attempt starts a new link epoch, so this is
     * never just the device: a reconnect to the same watch gets a new value.
     */
    int sessionToken() { return state == State.READY ? gate.token() : 0; }

    /** Main thread: a new or ended session cannot complete earlier face operations. */
    private void releaseFaceOps() {
        faceListOp.reset();
        faceSelectOp.reset();
        faceUpload.reset("The link ended during the transfer. The watch may keep part of the file.");
    }

    void loadFaces(Result<List<BulkProtocol.InstalledFace>> r) {
        if (faceSelectOp.active() || faceUpload.active()) {
            r.fail("Wait for the current face change to finish.");
            return;
        }
        final int t = faceListOp.begin();
        if (t == 0) {
            r.fail("The face list is already being read.");
            return;
        }
        op(new Result<byte[]>() {
            @Override public void ok(byte[] frame) {
                List<BulkProtocol.InstalledFace> list;
                try {
                    list = BulkProtocol.faceList(BulkProtocol.payload(frame, BulkProtocol.FACE_LIST));
                } catch (RuntimeException e) {
                    faceListOp.end(t);
                    r.fail(Errors.message(e));
                    return;
                }
                if (!faceListOp.current(t)) {
                    r.fail("The watch is not connected.");
                    return;
                }
                faces = list;
                // The previous index belonged to the previous list; it stays unknown until 75 answers.
                faceIndex = null;
                faceIndexFresh = false;
                op(new Result<Integer>() {
                    @Override public void ok(Integer i) {
                        if (faceListOp.end(t)) {
                            faceIndex = i;
                            faceIndexFresh = true;
                        }
                        r.ok(list);
                    }

                    @Override public void fail(String m) {
                        if (faceListOp.end(t)) {
                            faceIndex = null;
                            faceIndexFresh = false;
                        }
                        r.ok(list);
                    }
                }, (c, cb) -> c.request("face index", ShortProtocol.readFaceIndex(), ShortProtocol.NO_ACTION,
                        ShortProtocol::faceIndex, cb));
            }

            @Override public void fail(String m) {
                faceListOp.end(t);
                r.fail(m);
            }
        }, (c, cb) -> c.bulk("face list", BulkProtocol.faceListRequest(), BulkProtocol.FACE_LIST,
                WatchClient.BULK_TIMEOUT_MS, cb));
    }

    /** Select installed row (zero-based), then read the index back. */
    void selectFace(int row, Result<Integer> r) {
        List<BulkProtocol.InstalledFace> list = faces;
        if (list == null || row < 0 || row >= list.size()) {
            r.fail("Read the installed faces first.");
            return;
        }
        if (faceListOp.active() || faceUpload.active()) {
            r.fail("Wait for the watch to finish reading or receiving faces.");
            return;
        }
        final int t = faceSelectOp.begin();
        if (t == 0) {
            r.fail("A face change is already in progress.");
            return;
        }
        int index = row + 1;
        byte[] write = ShortProtocol.selectFace(index, list.size());
        op(new Result<Integer>() {
            @Override public void ok(Integer got) {
                if (!faceSelectOp.end(t)) {
                    r.fail("The watch is not connected.");
                    return;
                }
                faceIndex = got;
                faceIndexFresh = true;
                if (got == index) r.ok(got);
                else r.fail("The watch reports a different face as selected.");
            }

            /** After a write attempt without a confirmed readback, the current face is unknown. */
            @Override public void fail(String m) {
                if (faceSelectOp.end(t)) {
                    faceIndex = null;
                    faceIndexFresh = false;
                }
                r.fail(m);
            }
        }, (c, cb) -> c.request("face select", write, ShortProtocol.NO_ACTION, f -> null, new CommandQueue.Callback<Object>() {
            @Override public void onResult(Object v) {
                c.request("face index", ShortProtocol.readFaceIndex(), ShortProtocol.NO_ACTION, ShortProtocol::faceIndex, cb);
            }

            @Override public void onError(Exception e) { cb.onError(e); }
        }));
    }

    /**
     * Upload one face resource in the ready session {@code sessionToken} captured when the user acted.
     * A different or ended session is refused before any transfer starts. {@code label} names the
     * resource for screens other than the one that started it.
     */
    void upload(ResourceTransfer.Kind kind, String name, String label, int sessionToken, byte[] data, Progress progress, Result<Void> r) {
        if (sessionToken == 0 || sessionToken != sessionToken()) {
            r.fail("The connection changed. Nothing was sent to the watch.");
            return;
        }
        if (faceListOp.active() || faceSelectOp.active()) {
            r.fail("Wait for the watch to finish the current face action.");
            return;
        }
        final int t = faceUpload.begin(label, (data.length + ResourceTransfer.BLOCK - 1) / ResourceTransfer.BLOCK);
        if (t == 0) {
            r.fail("Another face transfer is running.");
            return;
        }
        Progress tracked = (d, n) -> {
            if (faceUpload.progress(t, d, n)) progress.update(d, n);
        };
        Result<Void> owned = new Result<Void>() {
            @Override public void ok(Void v) {
                if (faceUpload.finish(t, null)) refreshAfterTransfer();
                r.ok(v);
            }

            @Override public void fail(String m) {
                if (faceUpload.finish(t, m)) refreshAfterTransfer();
                r.fail(m);
            }
        };
        transferUpload(kind, name, sessionToken, data, tracked, owned);
    }

    /** One list and index read after a transfer ended, whichever screen is showing. No retry follows. */
    private void refreshAfterTransfer() {
        if (state != State.READY || !bulkAvailable() || faceBusy()) return;
        loadFaces(new Result<List<BulkProtocol.InstalledFace>>() {
            @Override public void ok(List<BulkProtocol.InstalledFace> v) { }
            @Override public void fail(String m) { }
        });
    }

    private void transferUpload(ResourceTransfer.Kind kind, String name, int sessionToken, byte[] data, Progress progress, Result<Void> r) {
        op(r, (c, cb) -> {
            GattSession s = session;
            if (s == null || s.epoch != sessionToken) {
                cb.onError(new IllegalStateException("The connection changed. Nothing was sent to the watch."));
                return;
            }
            if (!s.hasBulk()) {
                cb.onError(new IllegalStateException("This connection has no face transfer channel."));
                return;
            }
            if (transfer != null) {
                cb.onError(new IllegalStateException("Another face transfer is running."));
                return;
            }
            ResourceTransfer t;
            try {
                t = new ResourceTransfer(c, kind, name, data, (d, n) -> main.post(() -> progress.update(d, n)),
                        new CommandQueue.Callback<Void>() {
                            @Override public void onResult(Void v) { transfer = null; cb.onResult(v); }
                            @Override public void onError(Exception e) { transfer = null; cb.onError(e); }
                        });
            } catch (IllegalArgumentException e) {
                cb.onError(e);
                return;
            }
            transfer = t;
            t.start();
        });
    }

    void stopTransfer() {
        ble.post(() -> {
            if (transfer != null) transfer.stop();
        });
    }

    /** Call event: caller text if the phone supplied it, otherwise one padded control chunk. */
    void notifyCall(int type, String textOrNull, Result<Void> r) {
        op(r, (c, cb) -> c.notifyFrames(dev.p71link.protocol.Notifications.callFrames(type, textOrNull), cb));
    }

    /** Raw notification push, used by the notification listener and the test message. */
    void notifyText(String text, int type, Result<Void> r) { op(r, (c, cb) -> c.notify(text, type, cb)); }

    /**
     * Private diagnostics for the user's own export: transport counters for the current connection,
     * negotiated sizes, and the face list. No health values or message text.
     */
    void diagnostics(Result<org.json.JSONObject> r) {
        ble.post(() -> {
            GattSession s = session;
            WatchClient c = s == null ? null : s.client();
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("capturedAt", System.currentTimeMillis());
                if (c == null) {
                    o.put("connection", "unavailable: no connected session");
                } else {
                    CommandQueue.Stats st = c.queue().stats();
                    o.put("connection", "current session");
                    o.put("sessionGeneration", s.generation);
                    o.put("attWritePayloadBytes", s.attPayload());
                    o.put("passiveFragmentReport", c.reportedFragment() < 0 ? "not received this session" : c.reportedFragment());
                    o.put("bulkFragmentBytesInUse", c.bulkChunk());
                    o.put("counters", new org.json.JSONObject()
                            .put("note", "counted by this app at the Bluetooth write and notification callbacks for this session")
                            .put("shortWritesStarted", st.shortWritesStarted).put("shortWritesConfirmed", st.shortWritesConfirmed)
                            .put("shortNotifications", st.shortNotifications)
                            .put("bulkFragmentsStarted", st.bulkFragmentsStarted).put("bulkFragmentsConfirmed", st.bulkFragmentsConfirmed)
                            .put("bulkNotifications", st.bulkNotifications)
                            .put("requestsSubmitted", st.operationsSubmitted).put("requestsFailed", st.operationsFailed));
                }
            } catch (org.json.JSONException ignored) {
                // Partial diagnostics are still returned.
            }
            final int token = s == null ? -1 : s.epoch;
            main.post(() -> {
                try {
                    // Session data from the main thread is added only if it belongs to the same session.
                    if (s == null || !gate.current(token)) {
                        if (s != null) o.put("sessionState", "stale: the connection changed while diagnostics were captured");
                        else o.put("sessionState", "no connected session");
                        r.ok(o);
                        return;
                    }
                    o.put("sessionState", "counters and watch data from the same session");
                    if (sessionReadyAt > 0 && connected()) o.put("sessionReadyAt", sessionReadyAt);
                    if (faceIndexFresh && faceIndex != null) o.put("currentFaceIndexOneBased", faceIndex);
                    else o.put("currentFaceIndexOneBased", "not read in this session");
                    if (faces != null) {
                        org.json.JSONArray list = new org.json.JSONArray();
                        for (BulkProtocol.InstalledFace f : faces) {
                            list.put(new org.json.JSONObject().put("name", f.name).put("deleteEligible", f.deletable));
                        }
                        o.put("installedFacesInWatchOrder", list);
                    } else {
                        o.put("installedFacesInWatchOrder", "not read in this session");
                    }
                } catch (org.json.JSONException ignored) {
                    // Partial diagnostics are still returned.
                }
                r.ok(o);
            });
        });
    }

    int queueDepth() {
        GattSession s = session;
        WatchClient c = s == null ? null : s.client();
        return c == null ? 0 : c.queue().pendingCount();
    }
}
