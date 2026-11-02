package dev.p71link.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.CommandQueue;
import dev.p71link.protocol.ShortProtocol;
import dev.p71link.protocol.WatchClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One GATT connection generation. Every Android callback is posted to the Bluetooth thread and
 * dropped unless this session is still the live one. The session owns its queue; when the queue
 * invalidates itself (timeout or failed write), the session closes this GATT. A later connection
 * uses a new session and a new queue, so old callbacks and replies cannot reach new requests.
 */
@SuppressLint("MissingPermission")
final class GattSession implements CommandQueue.Transport, CommandQueue.SessionOwner {
    interface Owner {
        /** Setup finished. Runs on the Bluetooth thread. */
        void onSessionReady(GattSession s);

        /** The session ended. Runs on the Bluetooth thread exactly once. */
        void onSessionClosed(GattSession s, String reason, boolean ambiguous);

        void onUnsolicited(GattSession s, CommandQueue.Channel channel, byte[] frame);
    }

    static final long SETUP_TIMEOUT_MS = 25000;
    private static final UUID SHORT_SERVICE = UUID.fromString(ShortProtocol.SERVICE);
    private static final UUID SHORT_WRITE = UUID.fromString(ShortProtocol.WRITE);
    private static final UUID SHORT_NOTIFY = UUID.fromString(ShortProtocol.NOTIFY);
    private static final UUID BULK_SERVICE = UUID.fromString(BulkProtocol.SERVICE);
    private static final UUID BULK_WRITE = UUID.fromString(BulkProtocol.WRITE);
    private static final UUID BULK_NOTIFY = UUID.fromString(BulkProtocol.NOTIFY);
    private static final UUID INFO_SERVICE = UUID.fromString(ShortProtocol.DEVICE_INFO_SERVICE);
    private static final UUID FIRMWARE = UUID.fromString(ShortProtocol.FIRMWARE_REVISION);
    private static final UUID CCCD = UUID.fromString(ShortProtocol.CCCD);

    final int generation;
    final BluetoothDevice device;
    private final Context context;
    private final Handler ble;
    private final Owner owner;
    private final boolean background;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic shortWrite, shortNotify, bulkWrite, bulkNotify, firmwareChar;
    private CommandQueue queue;
    private WatchClient client;
    private boolean ready, closed;
    private int attPayload = 20;
    private String firmware;
    private final Runnable setupTimeout = () -> fail("Connection setup timed out", false);

    private enum Phase { CONNECTING, DISCOVERING, MTU, SHORT_CCCD, BULK_CCCD, FIRMWARE, READY }

    private Phase phase = Phase.CONNECTING;

    /** Main-thread epoch token assigned when the user intent created this session. */
    final int epoch;

    GattSession(Context context, Handler ble, BluetoothDevice device, int generation, int epoch, Owner owner, boolean background) {
        this.epoch = epoch;
        this.background = background;
        this.context = context.getApplicationContext();
        this.ble = ble;
        this.device = device;
        this.generation = generation;
        this.owner = owner;
    }

    /**
     * Foreground connection: direct connect with a setup deadline.
     * Background reconnect: the Bluetooth controller waits for the watch without app polling;
     * the setup deadline starts once the link is up.
     */
    void start() {
        if (!background) ble.postDelayed(setupTimeout, SETUP_TIMEOUT_MS);
        gatt = device.connectGatt(context, background, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) fail("Bluetooth could not start a connection", false);
    }

    WatchClient client() { return ready ? client : null; }

    boolean ready() { return ready && !closed; }

    boolean hasBulk() { return bulkWrite != null && bulkNotify != null; }

    int attPayload() { return attPayload; }

    String firmware() { return firmware; }

    /** User or app requested disconnect. */
    void close(String reason) { fail(reason, false); }

    // ---- CommandQueue.Transport --------------------------------------------------

    @Override
    public boolean write(CommandQueue.Channel channel, byte[] value) {
        if (closed || gatt == null) return false;
        BluetoothGattCharacteristic c = channel == CommandQueue.Channel.SHORT ? shortWrite : bulkWrite;
        if (c == null) return false;
        int type;
        if (channel == CommandQueue.Channel.BULK) {
            type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        } else {
            type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        }
        if (value.length > attPayload) return false; // Never exceed the ATT write capacity.
        return gatt.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS;
    }

    // ---- CommandQueue.SessionOwner ----------------------------------------------

    @Override
    public void onSessionInvalid(Exception reason) {
        // The queue already failed its work. Close this GATT; a new generation must start fresh.
        fail(Errors.message(reason), true);
    }

    // ---- Setup chain ---------------------------------------------------------------

    private void onConnected() {
        if (background) ble.postDelayed(setupTimeout, SETUP_TIMEOUT_MS);
        phase = Phase.DISCOVERING;
        if (!gatt.discoverServices()) fail("Service discovery could not start", false);
    }

    private void onDiscovered() {
        BluetoothGattService s = gatt.getService(SHORT_SERVICE);
        if (s == null) {
            fail("This device does not offer the P71 command service", false);
            return;
        }
        shortWrite = s.getCharacteristic(SHORT_WRITE);
        shortNotify = s.getCharacteristic(SHORT_NOTIFY);
        if (shortWrite == null || shortNotify == null) {
            fail("The P71 command service is incomplete", false);
            return;
        }
        BluetoothGattService b = gatt.getService(BULK_SERVICE);
        if (b != null) {
            bulkWrite = b.getCharacteristic(BULK_WRITE);
            bulkNotify = b.getCharacteristic(BULK_NOTIFY);
        }
        BluetoothGattService info = gatt.getService(INFO_SERVICE);
        if (info != null) firmwareChar = info.getCharacteristic(FIRMWARE);
        phase = Phase.MTU;
        // The MTU only bounds write size. The watch's 2F report sets the fragment size within it.
        if (!gatt.requestMtu(247)) enableShortNotify();
    }

    private void enableShortNotify() {
        phase = Phase.SHORT_CCCD;
        if (!enableNotify(shortNotify)) fail("Could not subscribe to watch replies", false);
    }

    private void afterShortNotify() {
        if (hasBulk()) {
            phase = Phase.BULK_CCCD;
            if (!enableNotify(bulkNotify)) {
                // Faces need this channel, but basic controls do not.
                bulkWrite = null;
                bulkNotify = null;
                readFirmware();
            }
        } else {
            readFirmware();
        }
    }

    private void readFirmware() {
        phase = Phase.FIRMWARE;
        if (firmwareChar == null || !gatt.readCharacteristic(firmwareChar)) finishSetup();
    }

    private void finishSetup() {
        phase = Phase.READY;
        ble.removeCallbacks(setupTimeout);
        queue = new CommandQueue(this, (task, delay) -> {
            Runnable r = () -> {
                if (!closed) task.run();
            };
            ble.postDelayed(r, delay);
            return () -> ble.removeCallbacks(r);
        }, (channel, frame) -> owner.onUnsolicited(this, channel, frame), this);
        client = new WatchClient(queue);
        client.setBulkChunk(BulkProtocol.DEFAULT_CHUNK, attPayload); // Every session starts at 20.
        ready = true;
        owner.onSessionReady(this);
    }

    private boolean enableNotify(BluetoothGattCharacteristic c) {
        if (!gatt.setCharacteristicNotification(c, true)) return false;
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) return false;
        return gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
    }

    private void fail(String reason, boolean ambiguous) {
        if (closed) return;
        closed = true;
        ready = false;
        ble.removeCallbacks(setupTimeout);
        if (queue != null) queue.onDisconnected();
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            gatt.close();
        }
        owner.onSessionClosed(this, reason, ambiguous);
    }

    // ---- Android callbacks: posted to the Bluetooth thread and scoped to this GATT ----

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        private boolean mine(BluetoothGatt g) { return !closed && g == gatt; }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            ble.post(() -> {
                if (!mine(g)) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (phase == Phase.CONNECTING) onConnected();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(phase == Phase.CONNECTING ? "The watch did not accept the connection" : "Watch disconnected", false);
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Bluetooth connection error " + status, false);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            ble.post(() -> {
                if (!mine(g) || phase != Phase.DISCOVERING) return;
                if (status != BluetoothGatt.GATT_SUCCESS) fail("Service discovery failed", false);
                else onDiscovered();
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            ble.post(() -> {
                if (!mine(g)) return;
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) attPayload = mtu - 3;
                if (client != null) client.setBulkChunk(client.bulkChunk(), attPayload);
                if (phase == Phase.MTU) enableShortNotify();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            ble.post(() -> {
                if (!mine(g)) return;
                if (phase == Phase.SHORT_CCCD) {
                    if (status != BluetoothGatt.GATT_SUCCESS) fail("Could not subscribe to watch replies", false);
                    else afterShortNotify();
                } else if (phase == Phase.BULK_CCCD) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        bulkWrite = null;
                        bulkNotify = null;
                    }
                    readFirmware();
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            ble.post(() -> {
                if (!mine(g) || phase != Phase.FIRMWARE) return;
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    firmware = new String(value, StandardCharsets.UTF_8).trim();
                }
                finishSetup();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ble.post(() -> {
                if (!mine(g) || queue == null) return;
                queue.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            final byte[] copy = value == null ? null : value.clone();
            final UUID id = c.getUuid();
            ble.post(() -> {
                if (!mine(g) || queue == null || copy == null) return;
                if (SHORT_NOTIFY.equals(id)) queue.onNotification(CommandQueue.Channel.SHORT, copy);
                else if (BULK_NOTIFY.equals(id)) queue.onNotification(CommandQueue.Channel.BULK, copy);
            });
        }
    };
}
