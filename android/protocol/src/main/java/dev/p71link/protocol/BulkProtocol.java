package dev.p71link.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Large-frame resource channel: BC, opcode, payload length LE16, CRC16 LE, payload.
 * This class has no firmware-update operation. Resource files never reach a firmware handler.
 */
public final class BulkProtocol {
    public static final String SERVICE = "de5bf728-d711-4e47-af26-65e3012a5dc7";
    public static final String WRITE = "de5bf72a-d711-4e47-af26-65e3012a5dc7";
    public static final String NOTIFY = "de5bf729-d711-4e47-af26-65e3012a5dc7";
    public static final int MAGIC = 0xbc, HEADER = 6, DEFAULT_CHUNK = 20;

    public static final int SLEEP = 0x27, HR_MANUAL = 0x28, CONTACTS = 0x29, SPO2_HISTORY = 0x2A,
            ALARMS = 0x2C, DIY_INIT = 0x31, DIY_DATA = 0x32, DIY_FINISH = 0x33, FACE_LIST = 0x35,
            FACE_INIT = 0x36, FACE_DATA = 0x37, FACE_FINISH = 0x38, FACE_DELETE = 0x39, LAYOUT = 0x3A,
            SPO2_MANUAL = 0x49;

    public static final String WALLPAPER_NAME = "time2_bg_img.ui";
    /** Application cap for one face file. It is not a measured watch storage capacity. */
    public static final int MAX_FACE_FILE = 2 * 1024 * 1024;

    private BulkProtocol() {}

    /** CRC-16, reflected polynomial A001, initial FFFF, no final XOR, payload only. */
    public static int crc16(byte[] data) {
        int crc = 0xffff;
        for (byte b : data) {
            crc ^= b & 255;
            for (int n = 0; n < 8; n++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        return crc;
    }

    public static byte[] frame(int command, byte[] payload) {
        ShortProtocol.octet(command);
        if (payload == null || payload.length > 65535) throw new IllegalArgumentException("Invalid bulk length");
        byte[] result = new byte[payload.length + HEADER];
        result[0] = (byte) MAGIC;
        result[1] = (byte) command;
        le16(result, 2, payload.length);
        le16(result, 4, crc16(payload));
        System.arraycopy(payload, 0, result, HEADER, payload.length);
        return result;
    }

    /** Validate a complete frame for one opcode and return its payload. */
    public static byte[] payload(byte[] frame, int expectedCommand) {
        if (frame == null || frame.length < HEADER || (frame[0] & 255) != MAGIC) {
            throw new ProtocolException("Invalid bulk envelope");
        }
        if ((frame[1] & 255) != expectedCommand) throw new ProtocolException("Unrelated bulk reply");
        int length = u16(frame, 2);
        if (frame.length != length + HEADER) throw new ProtocolException("Invalid bulk frame length");
        byte[] data = Arrays.copyOfRange(frame, HEADER, frame.length);
        if (crc16(data) != u16(frame, 4)) throw new ProtocolException("Invalid bulk checksum");
        return data;
    }

    public static int command(byte[] frame) { return frame[1] & 255; }

    /** Split one frame into BLE writes with no additional header. */
    public static List<byte[]> fragments(byte[] frame, int chunk) {
        if (chunk < DEFAULT_CHUNK || chunk > 512) throw new IllegalArgumentException("Invalid chunk size");
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < frame.length; i += chunk) out.add(Arrays.copyOfRange(frame, i, Math.min(frame.length, i + chunk)));
        return out;
    }

    /**
     * Reassembles notifications into complete frames. A notification that does not start with BC
     * while idle is discarded. A CRC failure is reported, never returned as data.
     */
    public static final class Assembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int expected = -1;
        private int discarded;

        /** Returns zero or more complete validated frames. Throws on a corrupt complete frame. */
        public List<byte[]> accept(byte[] part) {
            List<byte[]> out = new ArrayList<>();
            int i = 0;
            while (i < part.length) {
                if (expected < 0 && buffer.size() == 0 && (part[i] & 255) != MAGIC) {
                    discarded += part.length - i;
                    break;
                }
                int take;
                if (expected < 0) {
                    take = Math.min(HEADER - buffer.size(), part.length - i);
                    buffer.write(part, i, take);
                    i += take;
                    if (buffer.size() == HEADER) {
                        byte[] h = buffer.toByteArray();
                        expected = HEADER + u16(h, 2);
                    }
                    if (expected < 0) continue;
                }
                take = Math.min(expected - buffer.size(), part.length - i);
                buffer.write(part, i, take);
                i += take;
                if (buffer.size() == expected) {
                    byte[] f = buffer.toByteArray();
                    buffer.reset();
                    expected = -1;
                    byte[] payload = Arrays.copyOfRange(f, HEADER, f.length);
                    if (crc16(payload) != u16(f, 4)) throw new ProtocolException("Invalid bulk checksum");
                    out.add(f);
                }
            }
            return out;
        }

        public void reset() {
            buffer.reset();
            expected = -1;
        }

        public boolean partial() { return buffer.size() > 0; }

        public int discarded() { return discarded; }
    }

    // ---- Face resources ----

    public static byte[] faceListRequest() { return frame(FACE_LIST, new byte[0]); }

    /**
     * One installed face. The record flag means the watch allows deleting this face. It says nothing
     * about which face is current; only the one-based 75 index does.
     */
    public static final class InstalledFace {
        public final boolean deletable;
        public final String name;

        InstalledFace(boolean deletable, String name) {
            this.deletable = deletable;
            this.name = name;
        }
    }

    /**
     * Zero-based list row of the current face from a one-based 75 index, or -1 when the index is
     * absent or outside the list. Delete flags are never used to infer it.
     */
    public static int currentRow(List<InstalledFace> faces, Integer oneBasedIndex) {
        if (faces == null || oneBasedIndex == null) return -1;
        int row = oneBasedIndex - 1;
        return row >= 0 && row < faces.size() ? row : -1;
    }

    /** Payload: count, then [deleteEligibleFlag, nameLength, nameUTF8] records, in watch order. */
    public static List<InstalledFace> faceList(byte[] payload) {
        if (payload.length == 0) return Collections.emptyList();
        int count = payload[0] & 255;
        List<InstalledFace> out = new ArrayList<>();
        int i = 1;
        while (i < payload.length) {
            if (i + 2 > payload.length) throw new ProtocolException("Truncated face record");
            int flag = payload[i] & 255;
            int len = payload[i + 1] & 255;
            if (i + 2 + len > payload.length) throw new ProtocolException("Truncated face name");
            out.add(new InstalledFace(flag == 1, new String(payload, i + 2, len, StandardCharsets.UTF_8)));
            i += 2 + len;
        }
        if (out.size() != count) throw new ProtocolException("Face count mismatch");
        return Collections.unmodifiableList(out);
    }

    public static byte[] deleteFaceRequest(String name) {
        byte[] n = validName(name);
        byte[] body = new byte[n.length + 1];
        body[0] = 1;
        System.arraycopy(n, 0, body, 1, n.length);
        return frame(FACE_DELETE, body);
    }

    /** Init payload: 01, file length LE32, 00000000, name length, name. */
    public static byte[] resourceInit(boolean vendorFace, String name, int fileLength) {
        if (fileLength <= 0 || fileLength > MAX_FACE_FILE) throw new IllegalArgumentException("Invalid face file size");
        byte[] filename = validName(name);
        byte[] body = new byte[10 + filename.length];
        body[0] = 1;
        for (int i = 0; i < 4; i++) body[i + 1] = (byte) (fileLength >>> (i * 8));
        body[9] = (byte) filename.length;
        System.arraycopy(filename, 0, body, 10, filename.length);
        return frame(vendorFace ? FACE_INIT : DIY_INIT, body);
    }

    private static byte[] validName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid face file name");
        }
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        if (filename.length > 255) throw new IllegalArgumentException("Face file name is too long");
        return filename;
    }

    /** Data payload: one-based block index LE16 plus an independently encoded LZO1X block. */
    public static byte[] resourceBlock(boolean vendorFace, int oneBasedIndex, byte[] raw) {
        ShortProtocol.range(oneBasedIndex, 1, 65535, "block index");
        if (raw == null || raw.length < 1 || raw.length > 1024) throw new IllegalArgumentException("Invalid face block size");
        byte[] encoded = LiteralLzo.encode(raw);
        byte[] body = new byte[encoded.length + 2];
        le16(body, 0, oneBasedIndex);
        System.arraycopy(encoded, 0, body, 2, encoded.length);
        return frame(vendorFace ? FACE_DATA : DIY_DATA, body);
    }

    public static byte[] resourceFinish(boolean vendorFace) { return frame(vendorFace ? FACE_FINISH : DIY_FINISH, new byte[0]); }

    /** Encodes native-pixel opaque ARGB as width LE16, height LE16, then RGB565 LE pixels. */
    public static byte[] rgb565Wallpaper(int width, int height, int[] argb) {
        ShortProtocol.range(width, 1, 1024, "wallpaper width");
        ShortProtocol.range(height, 1, 1024, "wallpaper height");
        if (argb == null || argb.length != width * height) throw new IllegalArgumentException("Pixel count mismatch");
        byte[] out = new byte[4 + argb.length * 2];
        le16(out, 0, width);
        le16(out, 2, height);
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            if ((pixel >>> 24) != 255) throw new IllegalArgumentException("Flatten transparent pixels before encoding");
            int rgb = ((pixel >>> 19) & 31) << 11 | ((pixel >>> 10) & 63) << 5 | ((pixel >>> 3) & 31);
            le16(out, 4 + i * 2, rgb);
        }
        return out;
    }

    // ---- Custom face layout (3A) ----

    public static byte[] readLayoutRequest() { return frame(LAYOUT, new byte[]{1}); }

    public static final class LayoutEntry {
        public static final int CLOCK = 1, BATTERY = 2, DATA = 3;
        public final int type, x, y, rgb;

        public LayoutEntry(int type, int x, int y, int rgb) {
            this.type = ShortProtocol.range(type, 1, 3, "layout type");
            this.x = ShortProtocol.range(x, 0, 65535, "layout x");
            this.y = ShortProtocol.range(y, 0, 65535, "layout y");
            this.rgb = rgb & 0xffffff;
        }
    }

    /** Payload 02 followed by three 8-byte entries [type, xLE16, yLE16, R, G, B]; no count field. */
    public static byte[] layoutWrite(int width, int height, List<LayoutEntry> entries) {
        if (entries == null || entries.size() != 3) throw new IllegalArgumentException("Expected clock, battery, and data");
        byte[] body = new byte[1 + 8 * entries.size()];
        body[0] = 2;
        boolean[] seen = new boolean[4];
        for (int n = 0; n < entries.size(); n++) {
            LayoutEntry e = entries.get(n);
            if (seen[e.type]) throw new IllegalArgumentException("Duplicate layout type");
            seen[e.type] = true;
            ShortProtocol.range(e.x, 0, width - 1, "layout x");
            ShortProtocol.range(e.y, 0, height - 1, "layout y");
            int o = 1 + n * 8;
            body[o] = (byte) e.type;
            le16(body, o + 1, e.x);
            le16(body, o + 3, e.y);
            body[o + 5] = (byte) (e.rgb >> 16);
            body[o + 6] = (byte) (e.rgb >> 8);
            body[o + 7] = (byte) e.rgb;
        }
        return frame(LAYOUT, body);
    }

    /**
     * Reads the layout reply at the vendor editor offsets in full-frame bytes:
     * clock type 7, x 8, y 10, RGB 12; battery type 15, x 16, y 18, RGB 20; data type 23, x 24, y 26, RGB 28.
     * Every entry must carry its own type 1, 2, or 3, once each. A missing type is an error, never filled in.
     */
    public static List<LayoutEntry> layoutRead(byte[] frame) {
        byte[] p = payload(frame, LAYOUT);
        if (p.length < 25) throw new ProtocolException("The watch reported no complete layout");
        int[] offsets = {8, 16, 24};
        boolean[] seen = new boolean[4];
        List<LayoutEntry> out = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            int o = offsets[n];
            int type = frame[o - 1] & 255;
            if (type < 1 || type > 3 || seen[type]) throw new ProtocolException("The watch reported no complete layout");
            seen[type] = true;
            int rgb = (frame[o + 4] & 255) << 16 | (frame[o + 5] & 255) << 8 | (frame[o + 6] & 255);
            out.add(new LayoutEntry(type, u16(frame, o), u16(frame, o + 2), rgb));
        }
        return out;
    }

    /** A write echo would start with action 2; a read reply must not. */
    public static boolean isLayoutReadPayload(byte[] payload) { return payload.length >= 25 && (payload[0] & 255) != 2; }

    /** Init and finish replies for vendor faces: payload[0] above zero is an error. A missing status fails. */
    public static int statusByte(byte[] frame, int expectedCommand) {
        byte[] p = payload(frame, expectedCommand);
        if (p.length == 0) throw new ProtocolException("Reply has no status byte");
        return p[0] & 255;
    }

    static void le16(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
    }

    static int u16(byte[] value, int offset) { return (value[offset] & 255) | (value[offset + 1] & 255) << 8; }
}
