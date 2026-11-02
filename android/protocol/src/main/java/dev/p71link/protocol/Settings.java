package dev.p71link.protocol;

import static dev.p71link.protocol.ShortProtocol.READ;
import static dev.p71link.protocol.ShortProtocol.WRITE_ACTION;
import static dev.p71link.protocol.ShortProtocol.frame;
import static dev.p71link.protocol.ShortProtocol.range;

/**
 * Watch and health settings with read-modify-write semantics.
 * Each value object is built from a read reply and produces writes that keep unrelated fields.
 */
public final class Settings {
    private Settings() {}

    static ShortProtocol.Reply read(byte[] data, int command) {
        ShortProtocol.Reply r = ShortProtocol.match(data, command, READ);
        if (r == null) throw new ProtocolException("Unrelated reply");
        return r;
    }

    static boolean oneTwo(int value, String label) {
        if (value != 1 && value != 2) throw new ProtocolException("Invalid " + label + " flag");
        return value == 1;
    }

    static boolean zeroOne(int value, String label) {
        if (value != 0 && value != 1) throw new ProtocolException("Invalid " + label + " flag");
        return value == 1;
    }

    static int checked(int value, int min, int max, String label) {
        if (value < min || value > max) throw new ProtocolException("Invalid " + label);
        return value;
    }

    // ---- Do not disturb (06) ----

    public static byte[] readDnd() { return frame(ShortProtocol.DND, READ); }

    public static final class Dnd {
        public final boolean enabled;
        public final int startHour, startMinute, endHour, endMinute;
        /** Payload[6]: the manual quick-DND flag reported with the schedule. Never written by this app. */
        public final int manualFlag;

        public Dnd(boolean enabled, int sh, int sm, int eh, int em, int manualFlag) {
            this.enabled = enabled;
            startHour = range(sh, 0, 23, "start hour");
            startMinute = range(sm, 0, 59, "start minute");
            endHour = range(eh, 0, 23, "end hour");
            endMinute = range(em, 0, 59, "end minute");
            this.manualFlag = manualFlag;
        }

        public static Dnd parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.DND);
            return new Dnd(oneTwo(r.payload(1), "DND"), checked(r.payload(2), 0, 23, "start hour"),
                    checked(r.payload(3), 0, 59, "start minute"), checked(r.payload(4), 0, 23, "end hour"),
                    checked(r.payload(5), 0, 59, "end minute"), r.payload(6));
        }

        public byte[] write() {
            return frame(ShortProtocol.DND, WRITE_ACTION, enabled ? 1 : 2, startHour, startMinute, endHour, endMinute);
        }

        public Dnd withEnabled(boolean value) { return new Dnd(value, startHour, startMinute, endHour, endMinute, manualFlag); }

        public Dnd withSchedule(int sh, int sm, int eh, int em) { return new Dnd(enabled, sh, sm, eh, em, manualFlag); }

        public boolean sameStored(Dnd o) {
            return o != null && enabled == o.enabled && startHour == o.startHour && startMinute == o.startMinute
                    && endHour == o.endHour && endMinute == o.endMinute;
        }
    }

    // ---- Raise wrist to wake (05) ----

    public static byte[] readWrist() { return frame(ShortProtocol.WRIST, READ); }

    public static final class Wrist {
        public final boolean enabled;
        /** Payload[2] bit 0 means left hand; bit 2 is the palm-cover flag. Both are preserved. */
        public final boolean leftHand, palmFlag;

        public Wrist(boolean enabled, boolean leftHand, boolean palmFlag) {
            this.enabled = enabled;
            this.leftHand = leftHand;
            this.palmFlag = palmFlag;
        }

        public static Wrist parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.WRIST);
            int options = r.payload(2);
            return new Wrist(oneTwo(r.payload(1), "wrist"), (options & 1) != 0, (options & 4) != 0);
        }

        public byte[] write() {
            int options = (leftHand ? 1 : 2) | (palmFlag ? 4 : 0);
            return frame(ShortProtocol.WRIST, WRITE_ACTION, enabled ? 1 : 2, options);
        }

        public Wrist withEnabled(boolean value) { return new Wrist(value, leftHand, palmFlag); }

        public boolean sameStored(Wrist o) {
            return o != null && enabled == o.enabled && leftHand == o.leftHand && palmFlag == o.palmFlag;
        }
    }

    // ---- Display: timeout and DIY face display fields (1F) ----

    public static byte[] readDisplay() { return frame(ShortProtocol.DISPLAY, READ); }

    public static final class Display {
        /** Screen-on time in seconds, with the watch-reported min, max, and step. */
        public final int seconds, min, max, step;
        /** DIY face fields: data style, image dimming (100 - brightness), needle total and current. */
        public final int displayType, alpha, needleTotal, needleCurrent;
        public final int customFlag, alwaysOn, supportsAlwaysOn;

        Display(int seconds, int displayType, int alpha, int customFlag, int needleTotal, int needleCurrent,
                int min, int max, int step, int alwaysOn, int supportsAlwaysOn) {
            this.seconds = seconds;
            this.displayType = displayType;
            this.alpha = alpha;
            this.customFlag = customFlag;
            this.needleTotal = needleTotal;
            this.needleCurrent = needleCurrent;
            this.min = min;
            this.max = max;
            this.step = step;
            this.alwaysOn = alwaysOn;
            this.supportsAlwaysOn = supportsAlwaysOn;
        }

        public static Display parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.DISPLAY);
            return new Display(r.payload(1), r.payload(2), r.payload(3), r.payload(4), r.payload(5), r.payload(6),
                    r.payload(7), r.payload(8), r.payload(9), r.payload(10), r.payload(11));
        }

        /** True when min, max, and step form a usable range. */
        public boolean hasRange() { return min > 0 && max >= min && step > 0; }

        /** Seven-byte write: [2, seconds, displayType, alpha, 0, needleTotal, needleCurrent]. */
        public byte[] write() {
            return frame(ShortProtocol.DISPLAY, WRITE_ACTION, seconds, displayType, alpha, 0, needleTotal, needleCurrent);
        }

        public Display withSeconds(int value) {
            if (!hasRange()) throw new IllegalStateException("The watch did not report a timeout range");
            if (value < min || value > max || (value - min) % step != 0) {
                throw new IllegalArgumentException("Timeout outside the watch range");
            }
            return new Display(value, displayType, alpha, customFlag, needleTotal, needleCurrent, min, max, step,
                    alwaysOn, supportsAlwaysOn);
        }

        /** DIY face display fields; screen time stays at the read value. */
        public Display withFaceStyle(int dataType, int imageBrightnessPercent) {
            range(dataType, 0, 255, "data type");
            range(imageBrightnessPercent, 0, 100, "image brightness");
            return new Display(seconds, dataType, 100 - imageBrightnessPercent, customFlag, needleTotal, needleCurrent,
                    min, max, step, alwaysOn, supportsAlwaysOn);
        }

        public boolean sameStored(Display o) {
            return o != null && seconds == o.seconds && displayType == o.displayType && alpha == o.alpha
                    && needleTotal == o.needleTotal && needleCurrent == o.needleCurrent;
        }
    }

    // ---- Time format, metric units, and stored profile (0A) ----

    public static byte[] readTimeFormat() { return frame(ShortProtocol.TIME_FORMAT, READ); }

    public static final class TimeFormat {
        public final boolean is24Hour, metric;
        /** Profile fields stored with the format: gender, age, height, weight, SBP, DBP, HR warning. */
        public final int gender, age, height, weight, sbp, dbp, heartWarning, extra;

        TimeFormat(boolean is24Hour, boolean metric, int gender, int age, int height, int weight, int sbp, int dbp,
                   int heartWarning, int extra) {
            this.is24Hour = is24Hour;
            this.metric = metric;
            this.gender = gender;
            this.age = age;
            this.height = height;
            this.weight = weight;
            this.sbp = sbp;
            this.dbp = dbp;
            this.heartWarning = heartWarning;
            this.extra = extra;
        }

        public static TimeFormat parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.TIME_FORMAT);
            return new TimeFormat(r.payload(1) == 0, r.payload(2) == 0, r.payload(3), r.payload(4), r.payload(5),
                    r.payload(6), r.payload(7), r.payload(8), r.payload(9), r.payload(10));
        }

        /** Eleven-byte write that returns every read field, including payload[10]. */
        public byte[] write() {
            return frame(ShortProtocol.TIME_FORMAT, WRITE_ACTION, is24Hour ? 0 : 1, metric ? 0 : 1, gender, age, height,
                    weight, sbp, dbp, heartWarning, extra);
        }

        public TimeFormat with(boolean twentyFour, boolean metricUnits) {
            return new TimeFormat(twentyFour, metricUnits, gender, age, height, weight, sbp, dbp, heartWarning, extra);
        }

        public TimeFormat withProfile(int gender, int age, int heightCm, int weightKg) {
            return new TimeFormat(is24Hour, metric, range(gender, 0, 2, "gender"), range(age, 0, 120, "age"),
                    range(heightCm, 0, 250, "height"), range(weightKg, 0, 250, "weight"), sbp, dbp, heartWarning, extra);
        }

        public boolean sameStored(TimeFormat o) {
            return o != null && is24Hour == o.is24Hour && metric == o.metric && gender == o.gender && age == o.age
                    && height == o.height && weight == o.weight && sbp == o.sbp && dbp == o.dbp
                    && heartWarning == o.heartWarning && extra == o.extra;
        }
    }

    // ---- Temperature unit (19) ----

    public static byte[] readUnits() { return frame(ShortProtocol.UNITS, READ); }

    public static final class Units {
        public final boolean enabled, celsius;

        public Units(boolean enabled, boolean celsius) {
            this.enabled = enabled;
            this.celsius = celsius;
        }

        public static Units parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.UNITS);
            return new Units(r.payload(1) == 1, r.payload(2) == 1);
        }

        public byte[] write() { return frame(ShortProtocol.UNITS, WRITE_ACTION, enabled ? 1 : 2, celsius ? 1 : 2); }

        public Units withCelsius(boolean value) { return new Units(enabled, value); }
    }

    // ---- Battery saver (76), shown only with the YaWei capability flag ----

    public static byte[] readBatterySaver() { return frame(ShortProtocol.BATTERY_SAVER, 0); }

    public static byte[] writeBatterySaver(boolean on) { return frame(ShortProtocol.BATTERY_SAVER, 1, on ? 1 : 0); }

    /** Reply payload[1] equals 1 when enabled. The vendor parser checks no action byte. */
    public static boolean batterySaver(byte[] data) {
        return ShortProtocol.reply(data, ShortProtocol.BATTERY_SAVER).payload(1) == 1;
    }

    // ---- Automatic heart rate (16) ----

    public static byte[] readHeartRateAuto() { return frame(ShortProtocol.HR_AUTO, READ); }

    public static final class HeartRateAuto {
        public final boolean enabled;
        /** Raw stored interval byte. Units and limits are unresolved; the app never edits it. */
        public final int rawInterval;

        public HeartRateAuto(boolean enabled, int rawInterval) {
            this.enabled = enabled;
            this.rawInterval = ShortProtocol.octet(rawInterval);
        }

        public static HeartRateAuto parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.HR_AUTO);
            return new HeartRateAuto(oneTwo(r.payload(1), "heart-rate"), r.payload(2));
        }

        public byte[] write() { return frame(ShortProtocol.HR_AUTO, WRITE_ACTION, enabled ? 1 : 2, rawInterval); }

        public HeartRateAuto withEnabled(boolean v) { return new HeartRateAuto(v, rawInterval); }
    }

    // ---- Automatic blood oxygen (2C) ----

    public static byte[] readOxygenAuto() { return frame(ShortProtocol.SPO2_AUTO, READ); }

    public static boolean oxygenAuto(byte[] data) {
        return zeroOne(read(data, ShortProtocol.SPO2_AUTO).payload(1), "oxygen");
    }

    public static byte[] writeOxygenAuto(boolean enabled) {
        return frame(ShortProtocol.SPO2_AUTO, WRITE_ACTION, enabled ? 1 : 0);
    }

    // ---- Automatic blood pressure schedule (0C) ----

    public static byte[] readBpAuto() { return frame(ShortProtocol.BP_AUTO, READ); }

    public static final class BpAuto {
        public final boolean enabled;
        public final int startHour, startMinute, endHour, endMinute, multiple;

        public BpAuto(boolean enabled, int sh, int sm, int eh, int em, int multiple) {
            this.enabled = enabled;
            startHour = sh;
            startMinute = sm;
            endHour = eh;
            endMinute = em;
            this.multiple = ShortProtocol.octet(multiple);
        }

        public static BpAuto parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.BP_AUTO);
            return new BpAuto(r.payload(1) == 1, r.payload(2), r.payload(3), r.payload(4), r.payload(5), r.payload(6));
        }

        public byte[] write() {
            return frame(ShortProtocol.BP_AUTO, WRITE_ACTION, enabled ? 1 : 0, startHour, startMinute, endHour, endMinute,
                    multiple);
        }

        public BpAuto withEnabled(boolean v) { return new BpAuto(v, startHour, startMinute, endHour, endMinute, multiple); }
    }

    // ---- Goals (21) ----

    public static byte[] readGoals() { return frame(ShortProtocol.GOALS, READ); }

    public static final class Goals {
        public final int steps, calories, distance, activeMinutes, sleepMinutes;

        public Goals(int steps, int calories, int distance, int activeMinutes, int sleepMinutes) {
            this.steps = range(steps, 0, 0xffffff, "step goal");
            this.calories = range(calories, 0, 0xffffff, "calorie goal");
            this.distance = range(distance, 0, 0xffffff, "distance goal");
            this.activeMinutes = range(activeMinutes, 0, 0xffff, "activity goal");
            this.sleepMinutes = range(sleepMinutes, 0, 0xffff, "sleep goal");
        }

        public static Goals parse(byte[] data) {
            ShortProtocol.Reply r = read(data, ShortProtocol.GOALS);
            return new Goals(r.le24(1), r.le24(4), r.le24(7), r.le16(10), r.le16(12));
        }

        public byte[] write() {
            return frame(ShortProtocol.GOALS, WRITE_ACTION, steps & 255, steps >> 8 & 255, steps >> 16 & 255,
                    calories & 255, calories >> 8 & 255, calories >> 16 & 255, distance & 255, distance >> 8 & 255,
                    distance >> 16 & 255, activeMinutes & 255, activeMinutes >> 8 & 255, sleepMinutes & 255,
                    sleepMinutes >> 8 & 255);
        }

        public Goals withSteps(int v) { return new Goals(v, calories, distance, activeMinutes, sleepMinutes); }

        public boolean sameStored(Goals o) {
            return o != null && steps == o.steps && calories == o.calories && distance == o.distance
                    && activeMinutes == o.activeMinutes && sleepMinutes == o.sleepMinutes;
        }
    }

    // ---- Sedentary reminder (25 write, 26 read; BCD times; no action byte) ----

    public static byte[] readSedentary() { return frame(ShortProtocol.SEDENTARY_READ); }

    public static final class Sedentary {
        public final int startHour, startMinute, endHour, endMinute, weekMask, cycleMinutes;

        public Sedentary(int sh, int sm, int eh, int em, int weekMask, int cycleMinutes) {
            startHour = range(sh, 0, 23, "start hour");
            startMinute = range(sm, 0, 59, "start minute");
            endHour = range(eh, 0, 23, "end hour");
            endMinute = range(em, 0, 59, "end minute");
            this.weekMask = range(weekMask, 0, 0x7f, "weekday mask");
            this.cycleMinutes = cycleMinutes;
        }

        public static Sedentary parse(byte[] data) {
            ShortProtocol.Reply r = ShortProtocol.reply(data, ShortProtocol.SEDENTARY_READ);
            return new Sedentary(ShortProtocol.fromBcd(r.payload(0)), ShortProtocol.fromBcd(r.payload(1)),
                    ShortProtocol.fromBcd(r.payload(2)), ShortProtocol.fromBcd(r.payload(3)), r.payload(4) & 0x7f,
                    r.payload(5));
        }

        public boolean enabled() { return weekMask != 0; }

        /** The vendor SDK accepts cycles 30, 60, and 90 only. */
        public byte[] write() {
            if (cycleMinutes != 30 && cycleMinutes != 60 && cycleMinutes != 90) {
                throw new IllegalArgumentException("Reminder interval must be 30, 60, or 90");
            }
            return frame(ShortProtocol.SEDENTARY_WRITE, ShortProtocol.bcd(startHour), ShortProtocol.bcd(startMinute),
                    ShortProtocol.bcd(endHour), ShortProtocol.bcd(endMinute), weekMask, cycleMinutes);
        }

        public Sedentary with(int sh, int sm, int eh, int em, int mask, int cycle) {
            return new Sedentary(sh, sm, eh, em, mask, cycle);
        }
    }

    // ---- Alarms (23/24) and drink reminders (27/28): [index, enable, hBCD, mBCD, 7 weekday flags] ----

    public static final class Alarm {
        public static final int ALARM_SLOTS = 5, DRINK_SLOTS = 8;
        public final int index;
        public final boolean enabled;
        /** Enable byte as stored. Nonzero means on; a value such as 2 is kept unless the switch is changed. */
        public final int rawEnable;
        public final int hour, minute;
        /** Bit n holds weekday byte n (vendor editor order: bit 0 Sunday ... bit 6 Saturday). */
        public final int weekMask;

        public Alarm(int index, boolean enabled, int hour, int minute, int weekMask) {
            this(index, enabled ? 1 : 0, hour, minute, weekMask);
        }

        Alarm(int index, int rawEnable, int hour, int minute, int weekMask) {
            this.index = index;
            this.rawEnable = range(rawEnable, 0, 2, "enable value");
            this.enabled = rawEnable != 0;
            this.hour = range(hour, 0, 23, "hour");
            this.minute = range(minute, 0, 59, "minute");
            this.weekMask = range(weekMask, 0, 0x7f, "weekday mask");
        }

        /** Change only what the user edited; an unchanged switch keeps its raw stored value. */
        public Alarm edited(boolean on, int h, int m, int mask) {
            return new Alarm(index, on == enabled ? rawEnable : (on ? 1 : 0), h, m, mask);
        }

        public static byte[] read(boolean drink, int index) {
            range(index, 0, (drink ? DRINK_SLOTS : ALARM_SLOTS) - 1, "slot");
            return frame(drink ? ShortProtocol.DRINK_READ : ShortProtocol.ALARM_READ, index);
        }

        public static Alarm parse(boolean drink, byte[] data, int expectedIndex) {
            ShortProtocol.Reply r = ShortProtocol.reply(data, drink ? ShortProtocol.DRINK_READ : ShortProtocol.ALARM_READ);
            if (r.payload(0) != expectedIndex) throw new ProtocolException("Reminder slot mismatch");
            int mask = 0;
            for (int i = 0; i < 7; i++) {
                int v = r.payload(4 + i);
                if (v > 1) throw new ProtocolException("Invalid weekday flag");
                mask |= v << i;
            }
            int en = r.payload(1);
            if (en > 2) throw new ProtocolException("Invalid enable value");
            return new Alarm(r.payload(0), en, ShortProtocol.fromBcd(r.payload(2)), ShortProtocol.fromBcd(r.payload(3)), mask);
        }

        public byte[] write(boolean drink) {
            range(index, 0, (drink ? DRINK_SLOTS : ALARM_SLOTS) - 1, "slot");
            int[] body = new int[11];
            body[0] = index;
            body[1] = rawEnable;
            body[2] = ShortProtocol.bcd(hour);
            body[3] = ShortProtocol.bcd(minute);
            for (int i = 0; i < 7; i++) body[4 + i] = weekMask >> i & 1;
            return frame(drink ? ShortProtocol.DRINK_WRITE : ShortProtocol.ALARM_WRITE, body);
        }

        public boolean sameStored(Alarm o) {
            return o != null && index == o.index && rawEnable == o.rawEnable && hour == o.hour && minute == o.minute
                    && weekMask == o.weekMask;
        }
    }

    // ---- Weather forecast (1A): no action prefix; fields forwarded unchanged ----

    public static final class WeatherDay {
        /** Server timestamp kept raw; only its low 32 bits are sent, as the vendor app does. Meaning not established. */
        public final long rawTimestamp;
        public final int weatherType, low, high, humidity;
        public final boolean umbrella;

        public WeatherDay(long rawTimestamp, int weatherType, int low, int high, int humidity, boolean umbrella) {
            this.rawTimestamp = rawTimestamp;
            this.weatherType = range(weatherType, 1, 255, "weather type");
            this.low = range(low, -128, 127, "low temperature");
            this.high = range(high, -128, 127, "high temperature");
            this.humidity = range(humidity, 0, 255, "humidity");
            this.umbrella = umbrella;
        }

        /**
         * Build from server numbers without narrowing first: each value must be finite and integral and
         * within its field range before conversion. The timestamp must be an exact long.
         */
        public static WeatherDay fromNumbers(Number timestamp, Number type, Number low, Number high, Number humidity,
                                             boolean umbrella) {
            long ts = exactLong(timestamp, "timestamp");
            long t = exactLong(type, "weather type"), lo = exactLong(low, "low temperature"),
                    hi = exactLong(high, "high temperature"), hu = exactLong(humidity, "humidity");
            if (t < 1 || t > 255) throw new IllegalArgumentException("Invalid weather type");
            if (lo < -128 || lo > 127 || hi < -128 || hi > 127) throw new IllegalArgumentException("Invalid temperature");
            if (hu < 0 || hu > 255) throw new IllegalArgumentException("Invalid humidity");
            return new WeatherDay(ts, (int) t, (int) lo, (int) hi, (int) hu, umbrella);
        }

        /** The exact integral value of a JSON number, or an error for fractions, non-finite, or out-of-range values. */
        public static long exactLong(Number n, String label) {
            if (n == null) throw new IllegalArgumentException("Missing " + label);
            if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) return n.longValue();
            if (n instanceof java.math.BigInteger) {
                java.math.BigInteger b = (java.math.BigInteger) n;
                if (b.bitLength() > 63) throw new IllegalArgumentException("Out-of-range " + label);
                return b.longValue();
            }
            if (n instanceof java.math.BigDecimal) {
                try {
                    return ((java.math.BigDecimal) n).longValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("Inexact " + label);
                }
            }
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                // Above 2^53 a double cannot be trusted to hold the server's exact integer.
                if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 9007199254740992.0) {
                    throw new IllegalArgumentException("Inexact " + label);
                }
                return (long) d;
            }
            throw new IllegalArgumentException("Unsupported " + label);
        }

        public byte[] write(int index) {
            range(index, 0, 255, "forecast index");
            int t = (int) rawTimestamp;
            return frame(ShortProtocol.WEATHER, index, t & 255, t >> 8 & 255, t >> 16 & 255, t >>> 24, weatherType,
                    low & 255, high & 255, humidity, umbrella ? 1 : 2);
        }
    }

    // ---- Cycle reminder (2B): send only; no watch read-back is established ----

    public static final class Cycle {
        public final int periodDays, cycleDays, daysSinceStart, daysSinceEnd;
        public final boolean reminder;
        /** Days in advance for the period reminder and the fertile-window reminder; the vendor offers 1 to 3. */
        public final int periodLeadDays, fertileLeadDays;
        public final int reminderHour, reminderMinute;

        public Cycle(int periodDays, int cycleDays, int daysSinceStart, int daysSinceEnd, boolean reminder,
                     int periodLeadDays, int fertileLeadDays, int reminderHour, int reminderMinute) {
            this.periodDays = range(periodDays, 1, 15, "period length");
            this.cycleDays = range(cycleDays, 15, 60, "cycle length");
            this.daysSinceStart = range(daysSinceStart, 0, 255, "days since the period started");
            this.daysSinceEnd = range(daysSinceEnd, 0, 255, "days since the period ended");
            this.reminder = reminder;
            this.periodLeadDays = range(periodLeadDays, 1, 3, "period reminder lead days");
            this.fertileLeadDays = range(fertileLeadDays, 1, 3, "fertile window reminder lead days");
            this.reminderHour = range(reminderHour, 0, 23, "reminder hour");
            this.reminderMinute = range(reminderMinute, 0, 59, "reminder minute");
        }

        /** Binary bytes: [2, 1, period, cycle, |days to start|, |days to end|, reminder, periodLead, fertileLead, hour, minute]. */
        public byte[] write() {
            return frame(ShortProtocol.CYCLE, WRITE_ACTION, 1, periodDays, cycleDays, daysSinceStart, daysSinceEnd,
                    reminder ? 1 : 0, periodLeadDays, fertileLeadDays, reminderHour, reminderMinute);
        }

        /** The vendor rule: absolute whole days between two local midnights. */
        public static int daysBetween(java.time.LocalDate a, java.time.LocalDate b) {
            return (int) Math.abs(java.time.temporal.ChronoUnit.DAYS.between(a, b));
        }
    }
}
