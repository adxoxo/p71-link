package dev.p71link.protocol;

import java.time.LocalDateTime;

/**
 * Original codec for the 16-byte command channel: opcode, 14 payload bytes, additive checksum.
 * Payload index 0 is full-frame byte 1. Settings use action 1 for reads and 2 for writes,
 * except where a codec states otherwise.
 */
public final class ShortProtocol {
    public static final String SERVICE = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb";
    public static final String FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb";
    public static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";

    public static final int FRAME = 16;
    public static final int PAYLOAD = 14;
    public static final int NO_ACTION = -1;
    public static final int READ = 1;
    public static final int WRITE_ACTION = 2;

    /** Opcodes with a decoded schema. Names describe the watch function, not vendor class names. */
    public static final int CLOCK = 0x01, BATTERY = 0x03, PHONE_OS = 0x04, WRIST = 0x05,
            DND = 0x06, DAY_TOTALS = 0x07, TIME_FORMAT = 0x0A, BP_AUTO = 0x0C, PHONE_EVENT = 0x11,
            BP_HISTORY = 0x14, HR_HISTORY = 0x15, HR_AUTO = 0x16, UNITS = 0x19, WEATHER = 0x1A,
            BRIGHTNESS = 0x1B, MUSIC_STATE = 0x1C, MUSIC_KEY = 0x1D, DISPLAY = 0x1F, GOALS = 0x21,
            FIND_PHONE = 0x22, ALARM_WRITE = 0x23, ALARM_READ = 0x24, SEDENTARY_WRITE = 0x25,
            SEDENTARY_READ = 0x26, DRINK_WRITE = 0x27, DRINK_READ = 0x28, CYCLE = 0x2B, SPO2_AUTO = 0x2C,
            PACKAGE_LENGTH = 0x2F, STEP_DETAIL = 0x43, TODAY_STEPS = 0x48, FIND_WATCH = 0x50,
            MEASURE_START = 0x69, MEASURE_STOP = 0x6A, NOTIFY_TEXT = 0x72, DATA_CHANGED = 0x73,
            FACE_INDEX = 0x75, BATTERY_SAVER = 0x76;

    private ShortProtocol() {}

    public static byte[] frame(int command, int... payload) {
        if (command < 0 || command > 0x7f) throw new IllegalArgumentException("Invalid short command");
        if (payload.length > PAYLOAD) throw new IllegalArgumentException("Short payload exceeds 14 bytes");
        byte[] result = new byte[FRAME];
        result[0] = (byte) command;
        for (int i = 0; i < payload.length; i++) result[i + 1] = (byte) octet(payload[i]);
        result[15] = (byte) sum(result, 15);
        return result;
    }

    public static byte[] frameBytes(int command, byte[] payload) {
        int[] values = new int[payload.length];
        for (int i = 0; i < payload.length; i++) values[i] = payload[i] & 255;
        return frame(command, values);
    }

    public static boolean checksumValid(byte[] data) {
        return data != null && data.length == FRAME && sum(data, 15) == u8(data[15]);
    }

    /** Strict parse for one expected command. Throws on any rule failure. */
    public static Reply reply(byte[] data, int expectedCommand) {
        Reply r = match(data, expectedCommand, NO_ACTION);
        if (r == null) throw new ProtocolException("Unrelated reply");
        return r;
    }

    /**
     * Match a notification against an outstanding request.
     * Returns null when the frame belongs to another command; the caller can route it elsewhere.
     * Throws when the frame names this command but is corrupt, rejected, or has the wrong action.
     */
    public static Reply match(byte[] data, int expectedCommand, int expectedAction) {
        if (expectedCommand < 0 || expectedCommand > 0x7f) throw new IllegalArgumentException("Invalid expected command");
        if (data == null || data.length != FRAME) return null;
        int command = u8(data[0]);
        if ((command & 0x7f) != expectedCommand) return null;
        if (!checksumValid(data)) throw new ProtocolException("Reply checksum mismatch");
        if ((command & 0x80) != 0) throw new ProtocolException.Rejected(expectedCommand, u8(data[1]));
        Reply r = new Reply(data);
        if (expectedAction != NO_ACTION && r.payload(0) != expectedAction) {
            throw new ProtocolException("Reply action mismatch");
        }
        return r;
    }

    public static final class Reply {
        private final byte[] bytes;

        Reply(byte[] bytes) { this.bytes = bytes.clone(); }

        public int command() { return u8(bytes[0]); }

        public int payload(int index) {
            if (index < 0 || index >= PAYLOAD) throw new IndexOutOfBoundsException("payload " + index);
            return u8(bytes[index + 1]);
        }

        public int signed(int index) { return (byte) payload(index); }

        public int le16(int index) { return payload(index) | payload(index + 1) << 8; }

        public int le24(int index) { return le16(index) | payload(index + 2) << 16; }

        public long le32(int index) { return (le24(index) | (long) payload(index + 3) << 24) & 0xffffffffL; }

        public int be24(int index) { return payload(index) << 16 | payload(index + 1) << 8 | payload(index + 2); }

        public int be16(int index) { return payload(index) << 8 | payload(index + 1); }

        public byte[] bytes() { return bytes.clone(); }

        public byte[] payloadBytes() {
            byte[] out = new byte[PAYLOAD];
            System.arraycopy(bytes, 1, out, 0, PAYLOAD);
            return out;
        }
    }

    // ---- Simple commands -------------------------------------------------------------

    public static byte[] batteryRequest() { return frame(BATTERY); }

    public static int battery(byte[] data) {
        int value = reply(data, BATTERY).payload(0);
        if (value > 100) throw new ProtocolException("Invalid battery percentage");
        return value;
    }

    /** Clock write. The watch replies with its capability report. Language 1 is English. */
    public static byte[] clock(LocalDateTime time, int language) {
        range(time.getYear(), 2000, 2099, "year");
        octet(language);
        return frame(CLOCK, bcd(time.getYear() - 2000), bcd(time.getMonthValue()), bcd(time.getDayOfMonth()),
                bcd(time.getHour()), bcd(time.getMinute()), bcd(time.getSecond()), language);
    }

    /** Static request with no reply parser in the inspected app. A write success is not a vibration result. */
    public static byte[] findWatch() { return frame(FIND_WATCH, 0x55, 0xaa); }

    /**
     * Phone identity sent by the vendor app on connection: [2, platformCode, model UTF-8].
     * The model is cut to 12 bytes so the payload always fits; platform code 10 matches the capture.
     */
    public static byte[] phoneOs(String model) {
        byte[] name = model == null ? new byte[0] : model.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int length = Math.min(name.length, 12);
        // Avoid a split UTF-8 sequence at the cut.
        while (length > 0 && length < name.length && (name[length] & 0xc0) == 0x80) length--;
        int[] body = new int[2 + length];
        body[0] = 2;
        body[1] = 10;
        for (int i = 0; i < length; i++) body[2 + i] = u8(name[i]);
        return frame(PHONE_OS, body);
    }

    /**
     * Passive 2F report: bytes per outgoing bulk fragment, payload[0], unsigned. It is not an ATT MTU.
     * The vendor client uses max(value, 20). The caller must still cap it by the ATT write capacity.
     */
    public static int packageLength(byte[] frame) {
        return Math.max(reply(frame, PACKAGE_LENGTH).payload(0), 20);
    }

    // ---- Face selection (action bytes 0 read / 1 write; reply index at payload[1]) ----

    public static byte[] readFaceIndex() { return frame(FACE_INDEX, 0); }

    /** Installed-list row r selects index r+1 in the vendor flow. Verify the list before writing. */
    public static byte[] selectFace(int index, int installedCount) {
        range(installedCount, 1, 127, "installed face count");
        return frame(FACE_INDEX, 1, range(index, 1, installedCount, "face index"));
    }

    /** The vendor parser reads payload[1] and checks no action byte; this codec does the same. */
    public static int faceIndex(byte[] response) { return reply(response, FACE_INDEX).payload(1); }

    // ---- Helpers -----------------------------------------------------------------------

    static int sum(byte[] value, int length) {
        int s = 0;
        for (int i = 0; i < length; i++) s += u8(value[i]);
        return s & 255;
    }

    public static int bcd(int value) {
        range(value, 0, 99, "BCD value");
        return (value / 10) << 4 | value % 10;
    }

    public static int fromBcd(int value) {
        int hi = value >> 4 & 15, lo = value & 15;
        if (hi > 9 || lo > 9) throw new ProtocolException("Invalid BCD byte");
        return hi * 10 + lo;
    }

    static int u8(byte value) { return value & 255; }

    static int range(int value, int min, int max, String label) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value;
    }

    static int octet(int value) { return range(value, 0, 255, "byte"); }
}
