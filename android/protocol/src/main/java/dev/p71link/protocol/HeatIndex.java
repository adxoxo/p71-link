package dev.p71link.protocol;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Davao City heat index from Open-Meteo temperature and humidity. Pure Java: calculation, strict
 * parsing, message text and alarm times. Values are worked out from the forecast service's
 * temperature and relative humidity; they are not PAGASA readings. Any missing, mismatched or
 * out-of-range value rejects the whole reply. Nothing is filled in or reused.
 */
public final class HeatIndex {
    /** One fixed place. Only these coordinates are sent to Open-Meteo. PAGASA Davao station. */
    public static final String PLACE = "Davao City";
    public static final double LAT = 7.0657, LON = 125.611;
    public static final String ZONE_ID = "Asia/Manila";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);
    public static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=7.0657&longitude=125.611"
            + "&current=temperature_2m,relative_humidity_2m&hourly=temperature_2m,relative_humidity_2m"
            + "&forecast_days=1&timezone=Asia%2FManila";
    /** Largest reply accepted; a one-day reply is about 1.5 KB. */
    public static final int MAX_REPLY_BYTES = 64 * 1024;

    public static final int MIN_TIMES = 1, MAX_TIMES = 8;
    /** Default auto send times, minutes after midnight in Asia/Manila. */
    public static final List<Integer> DEFAULT_TIMES =
            Collections.unmodifiableList(java.util.Arrays.asList(360, 540, 720, 900, 1080, 1260));

    private HeatIndex() {}

    // ---- Calculation (US NWS, as used by PAGASA) ------------------------------------------------

    /** Heat index in °F from air temperature in °F and relative humidity in percent. */
    public static double heatIndexF(double t, double rh) {
        double simple = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094);
        if ((simple + t) / 2.0 < 80.0) return simple;
        double hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t
                - 0.05481717 * rh * rh + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh
                - 0.00000199 * t * t * rh * rh;
        if (rh < 13 && t >= 80 && t <= 112) {
            hi -= ((13 - rh) / 4.0) * Math.sqrt((17 - Math.abs(t - 95.0)) / 17.0);
        } else if (rh > 85 && t >= 80 && t <= 87) {
            hi += ((rh - 85) / 10.0) * ((87 - t) / 5.0);
        }
        return hi;
    }

    /** Heat index in °C, unrounded. Inputs must be finite and humidity within 0..100. */
    public static double heatIndexC(double tempC, double rh) {
        if (!Double.isFinite(tempC) || !Double.isFinite(rh)) throw new IllegalArgumentException("value is not a number");
        if (rh < 0 || rh > 100) throw new IllegalArgumentException("humidity outside 0 to 100");
        double f = tempC * 9.0 / 5.0 + 32.0;
        return (heatIndexF(f, rh) - 32.0) * 5.0 / 9.0;
    }

    /** Whole degrees, half up: 26.5 becomes 27. */
    public static int roundHalfUp(double c) { return (int) Math.floor(c + 0.5); }

    /** PAGASA level for a rounded heat index in °C, or null below Caution (under 27). */
    public static String level(int c) {
        if (c >= 52) return "Extreme Danger";
        if (c >= 42) return "Danger";
        if (c >= 33) return "Extreme Caution";
        if (c >= 27) return "Caution";
        return null;
    }

    /** Level text for display and the watch message. */
    public static String levelText(int c) {
        String l = level(c);
        return l == null ? "below Caution" : l;
    }

    // ---- Result -------------------------------------------------------------------------------

    /** One checked reply: the current value and today's peak, both rounded to whole °C. */
    public static final class Reading {
        public final int nowC, peakC, peakHour;
        public final LocalDate day;

        Reading(int nowC, int peakC, int peakHour, LocalDate day) {
            this.nowC = nowC;
            this.peakC = peakC;
            this.peakHour = peakHour;
            this.day = day;
        }

        /** The watch line. The level is the peak's level. */
        public String message() {
            return "Davao heat index: " + nowC + "°C now, peak " + peakC + "°C at " + hourLabel(peakHour, false)
                    + " (" + levelText(peakC) + ")";
        }
    }

    /**
     * Peak of a full day of hourly values: the highest rounded heat index and its hour; ties go to the
     * earliest hour. Exactly 24 values are required. Returns {peakC, hour}.
     */
    public static int[] peak(double[] tempC, double[] rh) {
        if (tempC == null || rh == null) throw new IllegalArgumentException("hourly values missing");
        if (tempC.length != rh.length) throw new IllegalArgumentException("hourly series differ in length");
        if (tempC.length != 24) throw new IllegalArgumentException("hourly series is not a full day");
        int best = Integer.MIN_VALUE, hour = -1;
        for (int i = 0; i < 24; i++) {
            int v = roundHalfUp(heatIndexC(tempC[i], rh[i]));
            if (v > best) {
                best = v;
                hour = i;
            }
        }
        return new int[]{best, hour};
    }

    // ---- Open-Meteo reply ------------------------------------------------------------------------

    /**
     * Parse and check one Open-Meteo reply for {@code today} in Asia/Manila. Throws
     * IllegalArgumentException with a short reason for anything missing, mismatched or out of range.
     */
    public static Reading parse(String json, LocalDate today) {
        Object root = Json.parse(json);
        Map<?, ?> o = obj(root, "reply");
        if (!ZONE_ID.equals(o.get("timezone"))) throw new IllegalArgumentException("reply is not for Asia/Manila");
        Map<?, ?> cu = obj(o.get("current_units"), "current units");
        Map<?, ?> hu = obj(o.get("hourly_units"), "hourly units");
        if (!"°C".equals(cu.get("temperature_2m")) || !"°C".equals(hu.get("temperature_2m")))
            throw new IllegalArgumentException("temperature is not in °C");
        if (!"%".equals(cu.get("relative_humidity_2m")) || !"%".equals(hu.get("relative_humidity_2m")))
            throw new IllegalArgumentException("humidity is not in percent");

        Map<?, ?> cur = obj(o.get("current"), "current values");
        LocalDateTime curTime = time(cur.get("time"), "current time");
        if (!curTime.toLocalDate().equals(today)) throw new IllegalArgumentException("current values are not for today");
        double nowHi = heatIndexC(num(cur.get("temperature_2m"), "current temperature"),
                num(cur.get("relative_humidity_2m"), "current humidity"));

        Map<?, ?> h = obj(o.get("hourly"), "hourly values");
        List<?> times = arr(h.get("time"), "hourly times");
        List<?> temps = arr(h.get("temperature_2m"), "hourly temperatures");
        List<?> hums = arr(h.get("relative_humidity_2m"), "hourly humidity");
        if (times.size() != temps.size() || times.size() != hums.size())
            throw new IllegalArgumentException("hourly series differ in length");
        if (times.size() != 24) throw new IllegalArgumentException("hourly series is not a full day");
        double[] t = new double[24], r = new double[24];
        for (int i = 0; i < 24; i++) {
            LocalDateTime at = time(times.get(i), "hourly time");
            if (!at.toLocalDate().equals(today) || at.getHour() != i || at.getMinute() != 0 || at.getSecond() != 0)
                throw new IllegalArgumentException("hourly times are not today's 24 hours in order");
            t[i] = num(temps.get(i), "hourly temperature");
            r[i] = num(hums.get(i), "hourly humidity");
        }
        int[] p = peak(t, r);
        return new Reading(roundHalfUp(nowHi), p[0], p[1], today);
    }

    private static Map<?, ?> obj(Object v, String what) {
        if (!(v instanceof Map)) throw new IllegalArgumentException(what + " missing");
        return (Map<?, ?>) v;
    }

    private static List<?> arr(Object v, String what) {
        if (!(v instanceof List)) throw new IllegalArgumentException(what + " missing");
        return (List<?>) v;
    }

    private static double num(Object v, String what) {
        if (!(v instanceof Double)) throw new IllegalArgumentException(what + " missing");
        double d = (Double) v;
        if (!Double.isFinite(d)) throw new IllegalArgumentException(what + " is not a number");
        return d;
    }

    private static LocalDateTime time(Object v, String what) {
        if (!(v instanceof String)) throw new IllegalArgumentException(what + " missing");
        try {
            return LocalDateTime.parse((String) v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " unreadable");
        }
    }

    // ---- Text ----------------------------------------------------------------------------------

    /** 0 → 12AM, 12 → 12PM, 13 → 1PM; with {@code space}, "12 PM". */
    public static String hourLabel(int hour, boolean space) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour");
        int h = hour % 12 == 0 ? 12 : hour % 12;
        return h + (space ? " " : "") + (hour < 12 ? "AM" : "PM");
    }

    /** Minutes after midnight as 24-hour "6:00", "15:30". */
    public static String clock(int minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    /** Minutes after midnight as "9:00 AM". */
    public static String clock12(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return String.format(Locale.ROOT, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
    }

    // ---- Auto send times -------------------------------------------------------------------------

    /** Valid minutes only, deduplicated and sorted. May be empty or longer than the limit. */
    public static List<Integer> normalize(List<Integer> minutes) {
        TreeSet<Integer> s = new TreeSet<>();
        if (minutes != null) for (Integer m : minutes) if (m != null && m >= 0 && m < 1440) s.add(m);
        return new ArrayList<>(s);
    }

    /** Stored form "360,540"; anything unreadable, empty or over the limit gives the defaults. */
    public static List<Integer> parseTimes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>(DEFAULT_TIMES);
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                return new ArrayList<>(DEFAULT_TIMES);
            }
        }
        List<Integer> n = normalize(out);
        return n.size() < MIN_TIMES || n.size() > MAX_TIMES ? new ArrayList<>(DEFAULT_TIMES) : n;
    }

    public static String formatTimes(List<Integer> minutes) {
        StringBuilder b = new StringBuilder();
        for (Integer m : normalize(minutes)) b.append(b.length() == 0 ? "" : ",").append(m);
        return b.toString();
    }

    /** The first slot strictly after {@code now}, in the zone of {@code now}. Null when no times. */
    public static ZonedDateTime nextSlot(List<Integer> minutes, ZonedDateTime now) {
        List<Integer> n = normalize(minutes);
        if (n.isEmpty()) return null;
        for (int day = 0; day <= 1; day++) {
            LocalDate d = now.toLocalDate().plusDays(day);
            for (int m : n) {
                ZonedDateTime at = d.atStartOfDay(now.getZone()).plusMinutes(m);
                if (at.isAfter(now)) return at;
            }
        }
        return null; // Unreachable: tomorrow's first slot is always later.
    }

    // ---- Minimal strict JSON reader --------------------------------------------------------------

    /** Objects become maps, arrays lists, numbers Double, plus String, Boolean and null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            if (text == null) throw new IllegalArgumentException("empty reply");
            Json j = new Json(text);
            j.ws();
            Object v = j.value(0);
            j.ws();
            if (j.i != text.length()) throw new IllegalArgumentException("unreadable reply");
            return v;
        }

        private IllegalArgumentException bad() { return new IllegalArgumentException("unreadable reply"); }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private Object value(int depth) {
            if (depth > 16 || i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw bad();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw bad();
                i++;
                ws();
                if (m.containsKey(k)) throw new IllegalArgumentException("duplicate field " + k);
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw bad();
            }
        }

        private List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw bad();
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw bad();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw bad();
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException x) {
                            throw bad();
                        }
                        i += 4;
                        break;
                    default: throw bad();
                }
            }
            throw bad();
        }

        private static boolean digit(char c) { return c >= '0' && c <= '9'; }

        private Double number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            int digits = i;
            while (i < s.length() && digit(s.charAt(i))) i++;
            if (i == digits) throw bad(); // Rejects NaN, Infinity and other bare words.
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int frac = i;
                while (i < s.length() && digit(s.charAt(i))) i++;
                if (i == frac) throw bad();
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int exp = i;
                while (i < s.length() && digit(s.charAt(i))) i++;
                if (i == exp) throw bad();
            }
            double d = Double.parseDouble(s.substring(start, i));
            if (!Double.isFinite(d)) throw bad();
            return d;
        }
    }
}
