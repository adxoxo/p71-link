package dev.p71link.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Active alarm list on bulk opcode 2C.
 * Read payload [1]; reply [1, total, records...]. Write [2, total, records...] replaces the whole list.
 * Record: [length = 4 + titleBytes, repeatAndEnable, minuteOfDayLE16, titleUTF8].
 * Bit 7 enables; bits 0-6 repeat days. Vendor editor order: bit 0 Sunday, bit 1 Monday ... bit 6 Saturday.
 * The write has no reply in the vendor code, so success needs a re-read that matches every record.
 */
public final class Alarms {
    /** App limit for one title. The watch's own title limit is not established. */
    public static final int TITLE_BYTES = 24;
    /** App limit for list size. The watch's native capacity is not established. */
    public static final int MAX_ALARMS = 10;
    public static final String[] DAY_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    public static final class Alarm {
        public final boolean enabled;
        public final int repeatMask, minuteOfDay;
        public final String title;
        /** Title bytes exactly as stored, so an untouched record is written back unchanged. */
        final byte[] titleBytes;

        public Alarm(boolean enabled, int repeatMask, int minuteOfDay, String title) {
            this(enabled, repeatMask, minuteOfDay, Contacts.clip(title == null ? "" : title.trim(), TITLE_BYTES));
        }

        Alarm(boolean enabled, int repeatMask, int minuteOfDay, byte[] titleBytes) {
            this.enabled = enabled;
            this.repeatMask = ShortProtocol.range(repeatMask, 0, 0x7f, "repeat days");
            this.minuteOfDay = ShortProtocol.range(minuteOfDay, 0, 1439, "alarm time");
            if (titleBytes.length > 251) throw new IllegalArgumentException("Alarm title too long");
            this.titleBytes = titleBytes.clone();
            this.title = new String(titleBytes, StandardCharsets.UTF_8);
        }

        public int hour() { return minuteOfDay / 60; }

        public int minute() { return minuteOfDay % 60; }

        public Alarm withEnabled(boolean on) { return new Alarm(on, repeatMask, minuteOfDay, titleBytes); }

        /**
         * An edit of time, days, or state. When the title text is unchanged, the stored title bytes are
         * kept exactly, even if longer than the app's limit for new titles.
         */
        public Alarm edited(boolean on, int mask, int minute, String newTitle) {
            // Exact unchanged input first: the stored bytes stay identical, spaces and length included.
            if (newTitle != null && newTitle.equals(title)) return new Alarm(on, mask, minute, titleBytes);
            String t = newTitle == null ? "" : newTitle.trim();
            if (t.equals(title)) return new Alarm(on, mask, minute, titleBytes);
            return new Alarm(on, mask, minute, t);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Alarm)) return false;
            Alarm a = (Alarm) o;
            return enabled == a.enabled && repeatMask == a.repeatMask && minuteOfDay == a.minuteOfDay
                    && java.util.Arrays.equals(titleBytes, a.titleBytes);
        }

        @Override public int hashCode() { return Objects.hash(enabled, repeatMask, minuteOfDay, title); }
    }

    private Alarms() {}

    public static byte[] readRequest() { return BulkProtocol.frame(BulkProtocol.ALARMS, new byte[]{1}); }

    /** Parse a read reply payload. Every record must fit exactly; the count must match. */
    public static List<Alarm> parse(byte[] payload) {
        if (payload.length < 2 || (payload[0] & 255) != 1) throw new ProtocolException("Not an alarm list reply");
        int total = payload[1] & 255;
        List<Alarm> out = new ArrayList<>();
        int i = 2;
        while (i < payload.length) {
            int len = payload[i] & 255;
            if (len < 4 || i + len > payload.length) throw new ProtocolException("Invalid alarm record length");
            int flags = payload[i + 1] & 255;
            int minute = (payload[i + 2] & 255) | (payload[i + 3] & 255) << 8;
            if (minute > 1439) throw new ProtocolException("Invalid alarm time");
            byte[] title = java.util.Arrays.copyOfRange(payload, i + 4, i + len);
            out.add(new Alarm((flags & 0x80) != 0, flags & 0x7f, minute, title));
            i += len;
        }
        if (out.size() != total) throw new ProtocolException("Alarm count mismatch");
        return Collections.unmodifiableList(out);
    }

    public static byte[] writeRequest(List<Alarm> alarms) {
        if (alarms.size() > MAX_ALARMS) throw new IllegalArgumentException("Too many alarms");
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(2);
        b.write(alarms.size());
        for (Alarm a : alarms) {
            b.write(4 + a.titleBytes.length);
            b.write((a.enabled ? 0x80 : 0) | a.repeatMask);
            b.write(a.minuteOfDay & 255);
            b.write(a.minuteOfDay >> 8);
            b.write(a.titleBytes, 0, a.titleBytes.length);
        }
        return BulkProtocol.frame(BulkProtocol.ALARMS, b.toByteArray());
    }

    /** Plain-language repeat summary using the vendor editor's day order. */
    public static String repeatText(int mask) {
        if (mask == 0) return "Once";
        if (mask == 0x7f) return "Every day";
        if (mask == 0x3e) return "Weekdays";
        if (mask == 0x41) return "Weekends";
        StringBuilder s = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            int bit = d % 7; // Monday first in the summary.
            if ((mask >> bit & 1) != 0) s.append(s.length() == 0 ? "" : " ").append(DAY_SHORT[bit]);
        }
        return s.toString();
    }
}
