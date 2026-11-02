package dev.p71link.protocol;

import static dev.p71link.protocol.ShortProtocol.frame;
import static dev.p71link.protocol.ShortProtocol.range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manual measurement and history codecs. Every decoded value comes from a watch reply.
 * Nothing here estimates, smooths, or substitutes a health value.
 */
public final class Health {
    private Health() {}

    // ---- Manual measurement (69 start, 6A stop) ----

    public static final int TYPE_HEART_RATE = 1, TYPE_BLOOD_PRESSURE = 2, TYPE_SPO2 = 3;

    /** HR [1,0]; BP [2,0]; SpO2 [3,0x25], as in the active vendor callers. */
    public static byte[] startMeasurement(int type) {
        range(type, 1, 3, "measurement type");
        return frame(ShortProtocol.MEASURE_START, type, type < 3 ? 0 : 0x25);
    }

    /**
     * Stop and cancel use [type,0,0]. This app never echoes a received or substitute value in the stop
     * frame; the vendor app's stop path can carry a generated oxygen number, which is not reproduced here.
     */
    public static byte[] stopMeasurement(int type) {
        range(type, 1, 3, "measurement type");
        return frame(ShortProtocol.MEASURE_STOP, type, 0, 0);
    }

    /** How a 69 reply may be used. */
    public enum Outcome {
        /** No value yet; keep waiting. An acknowledgment is not a result. */
        PENDING,
        /** The watch reported a measurement error. */
        FAILED,
        /** A value whose field meaning is established. Heart rate only. */
        MEASURED,
        /**
         * A positive raw byte whose unit is not established (oxygen and pressure replies).
         * It must not be shown, saved, or exported as a measured percentage or pressure.
         */
        RAW_UNVERIFIED,
        /** A value outside any possible range for its type. Not a result. */
        IMPOSSIBLE
    }

    public static final class MeasurementUpdate {
        public final int type, errorCode, value, sbp, dbp;

        MeasurementUpdate(int type, int errorCode, int value, int sbp, int dbp) {
            this.type = type;
            this.errorCode = errorCode;
            this.value = value;
            this.sbp = sbp;
            this.dbp = dbp;
        }

        public Outcome outcome() {
            if (errorCode != 0) return Outcome.FAILED;
            if (type == TYPE_HEART_RATE) {
                if (value == 0) return Outcome.PENDING;
                return value >= 25 && value <= 250 ? Outcome.MEASURED : Outcome.IMPOSSIBLE;
            }
            if (type == TYPE_BLOOD_PRESSURE) return sbp > 0 || dbp > 0 || value > 0 ? Outcome.RAW_UNVERIFIED : Outcome.PENDING;
            return value > 0 ? Outcome.RAW_UNVERIFIED : Outcome.PENDING;
        }

        /** True when the measurement stream has ended for this request. */
        public boolean finished() { return outcome() != Outcome.PENDING; }

        public boolean failed() { return errorCode != 0; }

        /** Only an established-unit heart-rate value counts as a completed measurement. */
        public boolean hasResult() { return outcome() == Outcome.MEASURED; }
    }

    /** Raw unsigned bytes: d0 type, d1 error, d2 value, d3 SBP, d4 DBP. */
    public static MeasurementUpdate measurement(byte[] data, int expectedType) {
        ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.MEASURE_START);
        if (r.payload(0) != expectedType) throw new ProtocolException("Measurement type mismatch");
        return new MeasurementUpdate(r.payload(0), r.payload(1), r.payload(2), r.payload(3), r.payload(4));
    }

    // ---- Today's activity (48): empty request ----

    public static byte[] readTodaySteps() { return frame(ShortProtocol.TODAY_STEPS); }

    public static final class DayActivity {
        public final int year, month, day, daysAgo;
        public final int steps, runningSteps, calories, distance, activeSeconds, sleepSeconds;
        public final boolean hasSecondPart;

        DayActivity(int year, int month, int day, int daysAgo, int steps, int runningSteps, int calories, int distance,
                    int activeSeconds, int sleepSeconds, boolean hasSecondPart) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.daysAgo = daysAgo;
            this.steps = steps;
            this.runningSteps = runningSteps;
            this.calories = calories;
            this.distance = distance;
            this.activeSeconds = activeSeconds;
            this.sleepSeconds = sleepSeconds;
            this.hasSecondPart = hasSecondPart;
        }
    }

    /** Big-endian fields: steps 0-2, running 3-5, calories 6-8, distance 9-11, active minutes 12-13. */
    public static DayActivity todaySteps(byte[] data, java.time.LocalDate today) {
        ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.TODAY_STEPS);
        return new DayActivity(today.getYear(), today.getMonthValue(), today.getDayOfMonth(), 0, r.be24(0), r.be24(3),
                r.be24(6), r.be24(9), r.be16(12) * 60, -1, true);
    }

    // ---- Daily totals (07): two packets per day ----

    public static byte[] readDayTotals(int daysAgo) { return frame(ShortProtocol.DAY_TOTALS, range(daysAgo, 0, 29, "day offset")); }

    /** Collects the two 07 packets. Packet 0 has steps; packet 1 has distance and durations. */
    public static final class DayTotalsReader implements WatchClient.FrameReader {
        private int index;
        private int daysAgo = -1, year, month, day, steps, running, calories;
        private DayActivity result;
        private boolean empty;

        /** Returns true when the read is complete. */
        public boolean accept(byte[] data) {
            ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.DAY_TOTALS);
            int packet = r.payload(0);
            if (packet != index) throw new ProtocolException("Unexpected activity packet");
            if (packet == 0 && r.payload(2) == 0 && r.payload(3) == 0 && r.payload(4) == 0) {
                empty = true;
                return true;
            }
            int ago = ShortProtocol.fromBcd(r.payload(1));
            int y = 2000 + ShortProtocol.fromBcd(r.payload(2));
            int m = ShortProtocol.fromBcd(r.payload(3));
            int d = ShortProtocol.fromBcd(r.payload(4));
            if (packet == 0) {
                daysAgo = ago;
                year = y;
                month = m;
                day = d;
                steps = r.be24(5);
                running = r.be24(8);
                calories = r.be24(11);
                index = 1;
                return false;
            }
            if (ago != daysAgo || y != year || m != month || d != day) throw new ProtocolException("Activity date mismatch");
            int distance = r.be24(5);
            int active = r.be16(8) * 60;
            int sleep = r.be16(10) * 60;
            result = new DayActivity(year, month, day, daysAgo, steps, running, calories, distance, active, sleep, true);
            index = 2;
            return true;
        }

        public boolean empty() { return empty; }

        public DayActivity result() { return result; }
    }

    // ---- Quarter-hour step detail (43) ----

    /** [dayOffset 0-29, 0x0F, firstQuarter, lastQuarter 0-95, 1]. */
    public static byte[] readStepDetail(int daysAgo, int firstQuarter, int lastQuarter) {
        range(daysAgo, 0, 29, "day offset");
        range(lastQuarter, 0, 95, "quarter");
        range(firstQuarter, 0, lastQuarter, "quarter");
        return frame(ShortProtocol.STEP_DETAIL, daysAgo, 0x0f, firstQuarter, lastQuarter, 1);
    }

    public static final class StepSlot {
        public final int year, month, day, quarter, calories, steps, distance;
        /** The calorie field exactly as received, and the header flag the vendor uses to multiply it by 10. */
        public final int rawCalories;
        public final boolean calorieTimesTenFlag;

        StepSlot(int year, int month, int day, int quarter, int rawCalories, boolean timesTen, int steps, int distance) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.quarter = quarter;
            this.rawCalories = rawCalories;
            this.calorieTimesTenFlag = timesTen;
            this.calories = timesTen ? rawCalories * 10 : rawCalories;
            this.steps = steps;
            this.distance = distance;
        }

        public int caloriesRaw() { return rawCalories; }
    }

    /** Multi-frame reader: F0 header, then data packets [date BCD x3, quarter, packet, total, LE16 values]. */
    public static final class StepDetailReader implements WatchClient.FrameReader {
        private final List<StepSlot> slots = new ArrayList<>();
        private boolean header, caloriesTimesTen, done, empty;
        private int nextPacket, total = -1;

        public boolean accept(byte[] data) {
            if (done) throw new ProtocolException("Extra step packet");
            ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.STEP_DETAIL);
            int first = r.payload(0);
            if (!header && first == 0xff) {
                empty = true;
                done = true;
                return true;
            }
            if (!header) {
                if (first != 0xf0) throw new ProtocolException("Step data before header");
                header = true;
                caloriesTimesTen = r.payload(2) == 1;
                return false;
            }
            int packet = r.payload(4), count = r.payload(5);
            if (count == 0) throw new ProtocolException("Invalid step packet total");
            if (total < 0) total = count;
            if (count != total) throw new ProtocolException("Step packet total changed");
            if (packet != nextPacket) throw new ProtocolException("Step packet out of order");
            int quarter = r.payload(3);
            if (quarter > 95) throw new ProtocolException("Invalid quarter index");
            slots.add(new StepSlot(2000 + ShortProtocol.fromBcd(r.payload(0)), ShortProtocol.fromBcd(r.payload(1)),
                    ShortProtocol.fromBcd(r.payload(2)), quarter, r.le16(6), caloriesTimesTen, r.le16(8), r.le16(10)));
            nextPacket++;
            if (packet == total - 1) done = true;
            return done;
        }

        public boolean empty() { return empty; }

        public List<StepSlot> slots() { return Collections.unmodifiableList(slots); }
    }

    // ---- Heart-rate day history (15) ----

    /** Request uses a 32-bit little-endian timestamp in the watch's local-time epoch. */
    public static byte[] readHeartRateDay(long localEpochSeconds) {
        int t = (int) localEpochSeconds;
        return frame(ShortProtocol.HR_HISTORY, t & 255, t >> 8 & 255, t >> 16 & 255, t >>> 24);
    }

    /**
     * Packet 0: [0, packetCount, minutesPerSample]. Packet 1: [1, timeLE32, 9 values].
     * Packets 2..n: [n, 13 values]. Payload FF means no data. Zero means no sample.
     */
    public static final class HeartRateDayReader implements WatchClient.FrameReader {
        private int size = -1, minutesPerSample, next;
        private long localEpoch = -1;
        private final java.io.ByteArrayOutputStream values = new java.io.ByteArrayOutputStream();
        private boolean done, empty;

        public boolean accept(byte[] data) {
            if (done) throw new ProtocolException("Extra heart-rate packet");
            ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.HR_HISTORY);
            int packet = r.payload(0);
            if (packet == 0xff) {
                // Terminator. Samples received before it remain valid history.
                empty = values.size() == 0;
                done = true;
                return true;
            }
            if (packet != next) throw new ProtocolException("Heart-rate packet out of order");
            if (packet == 0) {
                size = r.payload(1);
                minutesPerSample = r.payload(2);
                if (size == 0) {
                    empty = true;
                    done = true;
                    return true;
                }
                if (minutesPerSample == 0) throw new ProtocolException("Invalid heart-rate interval");
            } else if (packet == 1) {
                localEpoch = r.le32(1);
                for (int i = 5; i < 14; i++) values.write(r.payload(i));
            } else {
                for (int i = 1; i < 14; i++) values.write(r.payload(i));
            }
            next++;
            if (packet == size - 1) done = true;
            return done;
        }

        public boolean empty() { return empty; }

        public int minutesPerSample() { return minutesPerSample; }

        public long localEpochStart() { return localEpoch; }

        public int[] samples() {
            byte[] b = values.toByteArray();
            int[] out = new int[b.length];
            for (int i = 0; i < b.length; i++) out[i] = b[i] & 255;
            return out;
        }
    }

    // ---- Large-frame manual history: HR 28, SpO2 49; records [minutesLE16, value] ----

    public static final class TimedValue {
        public final int minuteOfDay, value;

        TimedValue(int minuteOfDay, int value) {
            this.minuteOfDay = minuteOfDay;
            this.value = value;
        }
    }

    public static final class ManualHistory {
        public final int dayOffset;
        public final List<TimedValue> records;

        ManualHistory(int dayOffset, List<TimedValue> records) {
            this.dayOffset = dayOffset;
            this.records = records;
        }
    }

    public static byte[] manualHistoryRequest(boolean oxygen, boolean today) {
        return BulkProtocol.frame(oxygen ? BulkProtocol.SPO2_MANUAL : BulkProtocol.HR_MANUAL,
                new byte[]{(byte) (today ? 0 : 0xff)});
    }

    /** Payload: dayOffset, then 3-byte records. Minute values above a day are rejected. */
    public static ManualHistory manualHistory(byte[] payload) {
        if (payload.length < 1 || (payload.length - 1) % 3 != 0) throw new ProtocolException("Invalid manual history length");
        List<TimedValue> out = new ArrayList<>();
        for (int i = 1; i < payload.length; i += 3) {
            int minute = (payload[i] & 255) | (payload[i + 1] & 255) << 8;
            int value = payload[i + 2] & 255;
            if (minute >= 24 * 60) throw new ProtocolException("Invalid record minute");
            if (value != 0) out.add(new TimedValue(minute, value));
        }
        return new ManualHistory(payload[0] & 255, Collections.unmodifiableList(out));
    }

    // ---- Large-frame sleep (27) ----

    public static final class SleepStage {
        public final int code, minutes;

        SleepStage(int code, int minutes) {
            this.code = code;
            this.minutes = minutes;
        }
    }

    public static final class SleepDay {
        public final int dayOffset, startMinute, endMinute;
        public final List<SleepStage> stages;

        SleepDay(int dayOffset, int startMinute, int endMinute, List<SleepStage> stages) {
            this.dayOffset = dayOffset;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.stages = stages;
        }

        /** Vendor rule: a start at or after 18:00 belongs to the previous date. */
        public boolean startsPreviousDay() { return startMinute >= 1080; }
    }

    /**
     * Phone-calculated summary of one sleep record. Watch classifications: 2 light, 3 deep, 5 awake.
     * Other codes stay unlabelled. The stage sum and the recorded clock interval are kept separate.
     */
    public static final class SleepSummary {
        public final int light, deep, awake, unlabelled, stageSum;
        /** Minutes from recorded start to end, or -1 when the clock range cannot give one. */
        public final int interval;

        SleepSummary(int light, int deep, int awake, int unlabelled, int stageSum, int interval) {
            this.light = light;
            this.deep = deep;
            this.awake = awake;
            this.unlabelled = unlabelled;
            this.stageSum = stageSum;
            this.interval = interval;
        }

        public int asleep() { return light + deep; }

        /** True when the stage durations add up to the recorded interval. */
        public boolean consistent() { return interval >= 0 && stageSum == interval; }

        /** Light + deep alone describes the night only when nothing is unlabelled and the sum matches. */
        public boolean complete() { return unlabelled == 0 && consistent(); }
    }

    public static SleepSummary summarize(SleepDay d) {
        int light = 0, deep = 0, awake = 0, other = 0, sum = 0;
        for (SleepStage s : d.stages) {
            sum += s.minutes;
            if (s.code == 2) light += s.minutes;
            else if (s.code == 3) deep += s.minutes;
            else if (s.code == 5) awake += s.minutes;
            else other += s.minutes;
        }
        int interval;
        if (d.startsPreviousDay()) interval = 1440 - d.startMinute + d.endMinute;
        else interval = d.endMinute >= d.startMinute ? d.endMinute - d.startMinute : -1;
        return new SleepSummary(light, deep, awake, other, sum, interval);
    }

    public static byte[] sleepRequest(boolean today) {
        return BulkProtocol.frame(BulkProtocol.SLEEP, new byte[]{(byte) (today ? 0 : 0xff)});
    }

    /** Payload: count, then [dayOffset, followingLength, startLE16, endLE16, (stage, duration)...]. */
    public static List<SleepDay> sleep(byte[] payload) {
        if (payload.length < 1) throw new ProtocolException("Empty sleep payload");
        int count = payload[0] & 255;
        List<SleepDay> days = new ArrayList<>();
        int i = 1;
        while (i < payload.length) {
            if (i + 2 > payload.length) throw new ProtocolException("Truncated sleep record");
            int offset = payload[i] & 255;
            int following = payload[i + 1] & 255;
            int end = i + 2 + following;
            if (following < 4 || end > payload.length || (following - 4) % 2 != 0) {
                throw new ProtocolException("Invalid sleep record length");
            }
            int start = (payload[i + 2] & 255) | (payload[i + 3] & 255) << 8;
            int stop = (payload[i + 4] & 255) | (payload[i + 5] & 255) << 8;
            if (start >= 1440 || stop >= 1440) throw new ProtocolException("Invalid sleep minute");
            List<SleepStage> stages = new ArrayList<>();
            for (int j = i + 6; j < end; j += 2) stages.add(new SleepStage(payload[j] & 255, payload[j + 1] & 255));
            days.add(new SleepDay(offset, start, stop, Collections.unmodifiableList(stages)));
            i = end;
        }
        if (count == 0 && !days.isEmpty()) throw new ProtocolException("Sleep count mismatch");
        return days;
    }

    // ---- Blood-pressure history (14): request [timeLE32, 0, 50] ----

    public static byte[] readBpHistory(long localEpochSeconds) {
        int t = (int) localEpochSeconds;
        return frame(ShortProtocol.BP_HISTORY, t & 255, t >> 8 & 255, t >> 16 & 255, t >>> 24, 0, 50);
    }
}
