package dev.p71link.protocol;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Isolated checks for the Davao City heat index: formula, levels, text, parsing, alarm times. */
public final class HeatIndexTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
        }
    }

    static void ok(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static void eq(Object a, Object b, String m) {
        if (!java.util.Objects.equals(a, b)) throw new AssertionError(m + " expected " + b + " got " + a);
    }

    static void near(double a, double b, double tol, String m) {
        if (!(Math.abs(a - b) <= tol)) throw new AssertionError(m + " expected " + b + " got " + a);
    }

    static void rejects(Body b, String m) {
        try {
            b.run();
        } catch (IllegalArgumentException e) {
            return;
        } catch (Exception e) {
            throw new AssertionError(m + " threw " + e);
        }
        throw new AssertionError(m + " did not reject");
    }

    static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    static final String TEMPS = "[24.5,24.4,24.4,24.2,24.0,23.9,24.1,25.7,28.8,30.7,32.1,33.2,33.7,33.5,32.5,30.4,29.1,26.2,26.4,26.6,26.3,25.9,25.7,25.6]";
    static final String HUMS = "[96,96,95,95,96,95,94,90,74,65,59,55,53,55,63,71,81,97,97,97,97,92,90,88]";

    static String hours(LocalDate d, int n) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "\"%sT%02d:00\"", d, i));
        return b.append("]").toString();
    }

    /** A reply shaped like the live Open-Meteo reply captured on 2026-11-02 for these coordinates. */
    static String reply(String zone, String curTime, String curT, String curRh, String times, String temps, String hums) {
        return "{\"latitude\":7.0650263,\"longitude\":125.574326,\"generationtime_ms\":0.0557,\"utc_offset_seconds\":28800,"
                + "\"timezone\":\"" + zone + "\",\"timezone_abbreviation\":\"GMT+8\",\"elevation\":7.0,"
                + "\"current_units\":{\"time\":\"iso8601\",\"interval\":\"seconds\",\"temperature_2m\":\"°C\",\"relative_humidity_2m\":\"%\"},"
                + "\"current\":{\"time\":\"" + curTime + "\",\"interval\":900,\"temperature_2m\":" + curT + ",\"relative_humidity_2m\":" + curRh + "},"
                + "\"hourly_units\":{\"time\":\"iso8601\",\"temperature_2m\":\"°C\",\"relative_humidity_2m\":\"%\"},"
                + "\"hourly\":{\"time\":" + times + ",\"temperature_2m\":" + temps + ",\"relative_humidity_2m\":" + hums + "}}";
    }

    static String live() { return reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", hours(DAY, 24), TEMPS, HUMS); }

    static double fToC(double f) { return (f - 32) * 5 / 9; }

    static double[] fill(double v) { double[] a = new double[24]; Arrays.fill(a, v); return a; }

    public static void main(String[] args) {
        formula();
        levels();
        text();
        parsing();
        times();
        System.out.println("heat index tests passed " + passed + ", failed " + failed);
        for (String f : failures) System.out.println("FAIL " + f);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void formula() {
        test("NWS 90F 70% is about 106F (Rothfusz)", () -> {
            double hi = HeatIndex.heatIndexF(90, 70);
            near(hi, 105.922, 0.01, "rothfusz");
            eq((int) Math.round(hi), 106, "NWS table value");
        });
        test("NWS 80F 40% is about 80F (simple formula kept)", () -> {
            double hi = HeatIndex.heatIndexF(80, 40);
            near(hi, 79.58, 1e-9, "simple formula 0.5*(80+61+14.4+3.76)");
            eq((int) Math.round(hi), 80, "NWS table value");
        });
        test("high humidity adjustment: 84F 95% adds (95-85)/10*(87-84)/5 = 0.6F", () -> {
            near(HeatIndex.heatIndexF(84, 95), 100.8817, 0.001, "adjusted");
            near(HeatIndex.heatIndexF(84, 95) - 100.2817, 0.6, 0.001, "adjustment size");
        });
        test("high humidity adjustment applies only up to 87F", () -> {
            near(HeatIndex.heatIndexF(86, 90), 105.394, 0.001, "86F 90% adjusted (NWS table 105)");
            near(HeatIndex.heatIndexF(88, 90), -42.379 + 2.04901523 * 88 + 10.14333127 * 90 - .22475541 * 88 * 90
                    - .00683783 * 88 * 88 - .05481717 * 90 * 90 + .00122874 * 88 * 88 * 90 + .00085282 * 88 * 90 * 90
                    - .00000199 * 88 * 88 * 90 * 90, 1e-9, "88F no adjustment");
        });
        test("low humidity adjustment: 100F 10% subtracts (13-10)/4*sqrt((17-5)/17)", () -> {
            near(HeatIndex.heatIndexF(100, 10), 94.1225, 0.001, "adjusted");
            near(94.7526 - HeatIndex.heatIndexF(100, 10), 0.75 * Math.sqrt(12.0 / 17.0), 0.001, "adjustment size");
        });
        test("celsius path converts both ways", () -> {
            double tc = (90 - 32) * 5.0 / 9.0;
            near(HeatIndex.heatIndexC(tc, 70), fToC(105.922), 0.01, "90F/70% in C");
            eq(HeatIndex.roundHalfUp(HeatIndex.heatIndexC(tc, 70)), 41, "41.07C rounds to 41");
        });
        test("inputs out of range rejected", () -> {
            rejects(() -> HeatIndex.heatIndexC(30, 100.5), "RH>100");
            rejects(() -> HeatIndex.heatIndexC(30, -1), "RH<0");
            rejects(() -> HeatIndex.heatIndexC(Double.NaN, 50), "NaN temp");
            rejects(() -> HeatIndex.heatIndexC(30, Double.POSITIVE_INFINITY), "infinite RH");
            HeatIndex.heatIndexC(30, 0);
            HeatIndex.heatIndexC(30, 100);
        });
    }

    static void levels() {
        test("round half up", () -> {
            eq(HeatIndex.roundHalfUp(26.49), 26, "26.49");
            eq(HeatIndex.roundHalfUp(26.5), 27, "26.5");
            eq(HeatIndex.roundHalfUp(32.5), 33, "32.5");
        });
        test("PAGASA boundaries after rounding", () -> {
            double[] in = {26.4, 26.5, 32.0, 32.49, 32.5, 41.0, 41.49, 41.5, 51.0, 51.49, 51.5, 60};
            String[] want = {null, "Caution", "Caution", "Caution", "Extreme Caution", "Extreme Caution", "Extreme Caution",
                    "Danger", "Danger", "Danger", "Extreme Danger", "Extreme Danger"};
            for (int i = 0; i < in.length; i++) eq(HeatIndex.level(HeatIndex.roundHalfUp(in[i])), want[i], "level of " + in[i]);
            eq(HeatIndex.level(26), null, "26");
            eq(HeatIndex.level(27), "Caution", "27");
            eq(HeatIndex.level(32), "Caution", "32");
            eq(HeatIndex.level(33), "Extreme Caution", "33");
            eq(HeatIndex.level(41), "Extreme Caution", "41");
            eq(HeatIndex.level(42), "Danger", "42");
            eq(HeatIndex.level(51), "Danger", "51");
            eq(HeatIndex.level(52), "Extreme Danger", "52");
            eq(HeatIndex.levelText(26), "below Caution", "no level text");
        });
    }

    static void text() {
        test("hour labels", () -> {
            eq(HeatIndex.hourLabel(0, false), "12AM", "midnight");
            eq(HeatIndex.hourLabel(1, false), "1AM", "1");
            eq(HeatIndex.hourLabel(11, false), "11AM", "11");
            eq(HeatIndex.hourLabel(12, false), "12PM", "noon");
            eq(HeatIndex.hourLabel(13, false), "1PM", "13");
            eq(HeatIndex.hourLabel(23, false), "11PM", "23");
            eq(HeatIndex.hourLabel(12, true), "12 PM", "spaced");
            rejects(() -> HeatIndex.hourLabel(24, false), "24");
        });
        test("message format", () -> {
            eq(new HeatIndex.Reading(33, 39, 12, DAY).message(),
                    "Davao heat index: 33°C now, peak 39°C at 12PM (Extreme Caution)", "example");
            eq(new HeatIndex.Reading(25, 26, 0, DAY).message(),
                    "Davao heat index: 25°C now, peak 26°C at 12AM (below Caution)", "no level, midnight");
            eq(new HeatIndex.Reading(40, 52, 15, DAY).message(),
                    "Davao heat index: 40°C now, peak 52°C at 3PM (Extreme Danger)", "afternoon");
        });
        test("message stays under the 256 UTF-16 limit and is not altered", () -> {
            for (int c : new int[]{-999, -40, 0, 26, 27, 33, 42, 52, 120, 999}) {
                String m = new HeatIndex.Reading(c, c, 23, DAY).message();
                ok(m.length() < Notifications.MAX_UTF16_UNITS / 2, "length " + m.length());
                eq(Notifications.normalize(m), m, "normalize keeps it whole");
                ok(Notifications.frames(m, Notifications.SMS).size() <= 10, "chunk count");
            }
        });
        test("clock text", () -> {
            eq(HeatIndex.clock(360), "6:00", "6:00");
            eq(HeatIndex.clock(930), "15:30", "15:30");
            eq(HeatIndex.clock12(540), "9:00 AM", "9 AM");
            eq(HeatIndex.clock12(0), "12:00 AM", "midnight");
            eq(HeatIndex.clock12(765), "12:45 PM", "noon");
        });
    }

    static void parsing() {
        test("live-shaped reply parses; peak tie goes to the earliest hour", () -> {
            HeatIndex.Reading r = HeatIndex.parse(live(), DAY);
            eq(r.nowC, 29, "now 26.2C 97%");
            // Rounded 39 at 12:00 (38.74), 13:00 (38.97) and 14:00 (39.29): earliest wins.
            eq(r.peakC, 39, "peak");
            eq(r.peakHour, 12, "peak hour");
            eq(r.message(), "Davao heat index: 29°C now, peak 39°C at 12PM (Extreme Caution)", "message");
        });
        test("peak tie on equal values goes to the earliest hour", () -> {
            double[] t = fill(30), rh = fill(50);
            t[5] = 33; t[9] = 33; t[20] = 33;
            int[] p = HeatIndex.peak(t, rh);
            eq(p[1], 5, "earliest");
            eq(p[0], HeatIndex.roundHalfUp(HeatIndex.heatIndexC(33, 50)), "value");
            rejects(() -> HeatIndex.peak(new double[23], new double[23]), "partial day");
            rejects(() -> HeatIndex.peak(new double[24], new double[23]), "length mismatch");
        });
        test("missing arrays rejected", () -> {
            rejects(() -> HeatIndex.parse(live().replace("\"temperature_2m\":" + TEMPS + ",", ""), DAY), "no hourly temps");
            rejects(() -> HeatIndex.parse(live().replace(",\"relative_humidity_2m\":" + HUMS, ""), DAY), "no hourly RH");
            rejects(() -> HeatIndex.parse(reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", "null", TEMPS, HUMS), DAY), "times null");
            rejects(() -> HeatIndex.parse(live().replace("\"current\":{", "\"currentX\":{"), DAY), "no current");
        });
        test("length mismatch and partial series rejected", () -> {
            String t23 = TEMPS.replace(",25.6]", "]"), h23 = HUMS.replace(",88]", "]");
            rejects(() -> HeatIndex.parse(reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", hours(DAY, 24), t23, HUMS), DAY), "temps short");
            rejects(() -> HeatIndex.parse(reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", hours(DAY, 24), TEMPS, h23), DAY), "RH short");
            rejects(() -> HeatIndex.parse(reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", hours(DAY, 23), t23, h23), DAY), "partial day");
        });
        test("NaN, null and non-numbers rejected", () -> {
            rejects(() -> HeatIndex.parse(live().replace("[24.5,", "[NaN,"), DAY), "NaN token");
            rejects(() -> HeatIndex.parse(live().replace("[24.5,", "[null,"), DAY), "null value");
            rejects(() -> HeatIndex.parse(live().replace("[24.5,", "[\"24.5\","), DAY), "string value");
            rejects(() -> HeatIndex.parse(live().replace("\"temperature_2m\":26.2", "\"temperature_2m\":Infinity"), DAY), "Infinity");
            rejects(() -> HeatIndex.parse(live().replace("[24.5,", "[1e999,"), DAY), "overflow");
        });
        test("humidity outside 0..100 rejected", () -> {
            rejects(() -> HeatIndex.parse(live().replace("[96,", "[101,"), DAY), "hourly RH 101");
            rejects(() -> HeatIndex.parse(live().replace("\"relative_humidity_2m\":97}", "\"relative_humidity_2m\":100.5}"), DAY), "current RH");
            rejects(() -> HeatIndex.parse(live().replace("[96,", "[-1,"), DAY), "negative RH");
        });
        test("wrong day, zone, units or hour order rejected", () -> {
            rejects(() -> HeatIndex.parse(live(), DAY.plusDays(1)), "stale day");
            rejects(() -> HeatIndex.parse(live().replace("\"time\":\"2026-11-02T17:00\"", "\"time\":\"2026-11-01T17:00\""), DAY), "current yesterday");
            rejects(() -> HeatIndex.parse(reply("GMT", "2026-11-02T17:00", "26.2", "97", hours(DAY, 24), TEMPS, HUMS), DAY), "zone");
            rejects(() -> HeatIndex.parse(live().replace("\"temperature_2m\":\"°C\"", "\"temperature_2m\":\"°F\""), DAY), "units");
            String swapped = hours(DAY, 24).replace("T01:00", "TXX").replace("T02:00", "T01:00").replace("TXX", "T02:00");
            rejects(() -> HeatIndex.parse(reply("Asia/Manila", "2026-11-02T17:00", "26.2", "97", swapped, TEMPS, HUMS), DAY), "order");
        });
        test("malformed JSON rejected", () -> {
            rejects(() -> HeatIndex.parse("", DAY), "empty");
            rejects(() -> HeatIndex.parse(null, DAY), "null");
            rejects(() -> HeatIndex.parse(live() + "x", DAY), "trailing");
            rejects(() -> HeatIndex.parse(live().substring(0, 200), DAY), "truncated");
            rejects(() -> HeatIndex.parse("[1,2]", DAY), "not an object");
            rejects(() -> HeatIndex.parse(live().replace("\"elevation\":7.0", "\"timezone\":\"Asia/Manila\""), DAY), "duplicate field");
        });
    }

    static void times() {
        ZoneId z = HeatIndex.ZONE;
        test("next slot later today and across midnight", () -> {
            List<Integer> t = HeatIndex.DEFAULT_TIMES;
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 10, 15, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, z), "later today");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 21, 0, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 3, 6, 0, 0, 0, z), "exactly at last slot goes to tomorrow");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, z)),
                    ZonedDateTime.of(2027, 1, 1, 6, 0, 0, 0, z), "year end");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, z), "a fired slot is not repeated");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 8, 59, 59, 999_000_000, z)),
                    ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, z), "just before");
            eq(HeatIndex.nextSlot(new ArrayList<>(), ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, z)), null, "none");
        });
        test("next slot with unsorted and duplicate input", () -> {
            List<Integer> t = Arrays.asList(1260, 360, 720, 360, 1260, 5000, -1);
            eq(HeatIndex.normalize(t), Arrays.asList(360, 720, 1260), "normalized");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 13, 0, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 2, 21, 0, 0, 0, z), "unsorted");
            eq(HeatIndex.nextSlot(t, ZonedDateTime.of(2026, 11, 2, 22, 0, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 3, 6, 0, 0, 0, z), "unsorted across midnight");
            eq(HeatIndex.nextSlot(Arrays.asList(0), ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, z)),
                    ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, z), "midnight slot");
        });
        test("next slot computed in Manila from another zone's clock", () -> {
            ZonedDateTime utc = ZonedDateTime.of(2026, 11, 2, 14, 30, 0, 0, ZoneId.of("UTC")); // 22:30 Manila
            eq(HeatIndex.nextSlot(HeatIndex.DEFAULT_TIMES, utc.withZoneSameInstant(z)).toInstant(),
                    ZonedDateTime.of(2026, 11, 3, 6, 0, 0, 0, z).toInstant(), "instant");
        });
        test("stored times parse, dedupe, sort and fall back", () -> {
            eq(HeatIndex.parseTimes("900, 360,900"), Arrays.asList(360, 900), "dedupe sort");
            eq(HeatIndex.parseTimes(null), HeatIndex.DEFAULT_TIMES, "default");
            eq(HeatIndex.parseTimes("x"), HeatIndex.DEFAULT_TIMES, "garbage");
            eq(HeatIndex.parseTimes("1,2,3,4,5,6,7,8,9"), HeatIndex.DEFAULT_TIMES, "over 8");
            eq(HeatIndex.parseTimes("1,2,3,4,5,6,7,8"), Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), "8 kept");
            eq(HeatIndex.formatTimes(Arrays.asList(900, 360, 360)), "360,900", "format");
            eq(HeatIndex.DEFAULT_TIMES, Arrays.asList(360, 540, 720, 900, 1080, 1260), "defaults 6,9,12,15,18,21");
        });
    }
}
