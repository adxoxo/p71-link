package dev.p71link.protocol;

import dev.p71link.protocol.CommandQueue.Callback;
import dev.p71link.protocol.CommandQueue.Channel;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Dependency-free test runner. Fixtures come from captured P71J frames. */
public final class ProtocolTests {
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

    static void eq(Object a, Object b, String m) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(m + " expected " + b + " got " + a); }

    static void hex(byte[] got, String want, String m) { eq(Hex.encode(got), want, m); }

    static void throwsP(Body b, String m) {
        try {
            b.run();
        } catch (ProtocolException | IllegalArgumentException | IllegalStateException e) {
            return;
        } catch (Exception e) {
            throw new AssertionError(m + " threw " + e);
        }
        throw new AssertionError(m + " did not throw");
    }

    static byte[] h(String s) { return Hex.decode(s); }

    /** A drink read reply carrying the same fields as a write frame. */
    static byte[] readOf(byte[] write) {
        byte[] r = write.clone();
        r[0] = 0x28;
        r[15] = (byte) ShortProtocol.sum(r, 15);
        return r;
    }

    public static void main(String[] args) throws Exception {
        shortFrames();
        settings();
        notifications();
        bulk();
        readers();
        queue();
        transfer();
        faces();
        faceIntegration();
        if (args.length > 0) writeLzoVectors(args[0]);
        System.out.println("passed " + passed + ", failed " + failed);
        for (String f : failures) System.out.println("FAIL " + f);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------

    static void shortFrames() {
        test("battery request fixture", () -> hex(ShortProtocol.batteryRequest(), "03000000000000000000000000000003", "battery"));
        test("battery reply fixture", () -> eq(ShortProtocol.battery(h("03260000000000000000000000000029")), 38, "38%"));
        test("battery bad checksum", () -> throwsP(() -> ShortProtocol.battery(h("0326000000000000000000000000002a")), "checksum"));
        test("battery wrong command", () -> throwsP(() -> ShortProtocol.battery(h("04000000000000000000000000000004")), "unrelated"));
        test("battery over 100", () -> throwsP(() -> ShortProtocol.battery(ShortProtocol.frame(3, 101)), "range"));
        test("short length", () -> throwsP(() -> ShortProtocol.battery(h("032600")), "length"));
        test("brightness rejection fixture", () -> {
            try {
                ShortProtocol.match(h("9bee0000000000000000000000000089"), 0x1b, 1);
                throw new AssertionError("no rejection");
            } catch (ProtocolException.Rejected r) {
                eq(r.errorCode, 0xee, "code");
            }
        });
        test("unrelated returns null", () -> ok(ShortProtocol.match(h("03260000000000000000000000000029"), 6, 1) == null, "null"));
        test("action mismatch", () -> throwsP(() -> ShortProtocol.match(h("06020116000800000000000000000027"), 6, 1), "write echo is not read"));
        test("payload too long", () -> throwsP(() -> ShortProtocol.frame(1, new int[15]), "15"));
        test("byte out of range", () -> throwsP(() -> ShortProtocol.frame(1, 256), "256"));
        test("clock fixture", () -> hex(ShortProtocol.clock(LocalDateTime.of(2026, 11, 2, 9, 48, 27), 1),
                "012611020948270100000000000000b3", "clock"));
        test("clock fixture 2", () -> hex(ShortProtocol.clock(LocalDateTime.of(2026, 11, 2, 9, 48, 29), 1),
                "012611020948290100000000000000b5", "clock"));
        test("phone os fixture", () -> hex(ShortProtocol.phoneOs("SM-S918B"), "04020a534d2d53393138420000000014", "bind"));
        test("phone os long model fits", () -> ok(ShortProtocol.phoneOs("A very long phone model name").length == 16, "fits"));
        test("phone os utf8 cut", () -> {
            byte[] f = ShortProtocol.phoneOs("ÄÄÄÄÄÄÄ"); // 14 bytes, cut at 12 on a boundary
            eq(new String(Arrays.copyOfRange(f, 3, 15), StandardCharsets.UTF_8).replace("\0", ""), "ÄÄÄÄÄÄ", "boundary");
        });
        test("find watch", () -> hex(ShortProtocol.findWatch(), "5055aa0000000000000000000000004f", "find"));
        test("capability fixture", () -> {
            Capabilities c = Capabilities.parse(h("0100010127f0001c010106f101320062"));
            Map<String, Object> m = c.toMap();
            eq(m.size(), 31, "field count");
            Object[][] want = {{"temperature", false}, {"watch_faces", true}, {"menstruation", true},
                    {"new_sleep_protocol", true}, {"custom_wallpaper", true}, {"blood_oxygen", true},
                    {"blood_pressure", true}, {"generic_feature", false}, {"one_key_check", false}, {"weather", true},
                    {"wechat", true}, {"contacts", true}, {"lyrics", false}, {"album", false}, {"gps", false},
                    {"jieli_music", true}, {"app_measurement", true}, {"manual_blood_oxygen", true}, {"yawei", true},
                    {"manual_heart_rate", true}, {"ecard", false}, {"location", false}, {"music", false},
                    {"rtk_mcu", false}, {"ebook", false}, {"blood_glucose", false}, {"recording", false},
                    {"display_width", 240}, {"display_height", 284}, {"maximum_watch_faces", 6},
                    {"maximum_contacts", 500}};
            for (Object[] w : want) eq(m.get(w[0]), w[1], (String) w[0]);
        });
        test("face index ignores action byte", () -> {
            eq(ShortProtocol.faceIndex(ShortProtocol.frame(0x75, 0, 3)), 3, "read reply");
            eq(ShortProtocol.faceIndex(ShortProtocol.frame(0x75, 1, 2)), 2, "write reply");
        });
        test("face select bounds", () -> {
            hex(ShortProtocol.selectFace(2, 3), "750102000000000000000000000000" + "78", "select");
            throwsP(() -> ShortProtocol.selectFace(4, 3), "above count");
            throwsP(() -> ShortProtocol.selectFace(0, 3), "zero");
        });
    }

    static void settings() {
        test("dnd parse fixture", () -> {
            Settings.Dnd d = Settings.Dnd.parse(h("06010216000800000000000000000027"));
            ok(!d.enabled, "off");
            eq(d.startHour, 22, "sh");
            eq(d.endHour, 8, "eh");
            hex(d.withEnabled(true).write(), "06020116000800000000000000000027", "captured write");
            hex(d.write(), "06020216000800000000000000000028", "captured restore");
            ok(d.withEnabled(true).sameStored(Settings.Dnd.parse(h("06010116000800000000000000000026"))), "readback");
        });
        test("dnd invalid flag", () -> throwsP(() -> Settings.Dnd.parse(ShortProtocol.frame(6, 1, 3, 22, 0, 8, 0)), "flag 3"));
        test("dnd invalid hour", () -> throwsP(() -> Settings.Dnd.parse(ShortProtocol.frame(6, 1, 1, 24, 0, 8, 0)), "hour 24"));
        test("heart rate fixtures", () -> {
            Settings.HeartRateAuto a = Settings.HeartRateAuto.parse(h("1601020500000000000000000000001e"));
            ok(!a.enabled, "off");
            eq(a.rawInterval, 5, "interval");
            hex(a.withEnabled(true).write(), "1602010500000000000000000000001e", "captured");
            ok(ShortProtocol.match(h("16020100000000000000000000000019"), 0x16, 2) != null, "write ack");
            ok(Settings.HeartRateAuto.parse(h("1601010500000000000000000000001d")).enabled, "readback");
        });
        test("oxygen fixtures", () -> {
            ok(!Settings.oxygenAuto(h("2c01000000000000000000000000002d")), "off");
            hex(Settings.writeOxygenAuto(true), "2c02010000000000000000000000002f", "write");
            ok(Settings.oxygenAuto(h("2c01010000000000000000000000002e")), "readback");
            throwsP(() -> Settings.oxygenAuto(ShortProtocol.frame(0x2c, 1, 2)), "flag 2");
        });
        test("wrist fixture preserves options", () -> {
            Settings.Wrist w = Settings.Wrist.parse(h("0501010500000000000000000000000c"));
            ok(w.enabled && w.leftHand && w.palmFlag, "fields");
            hex(w.write(), "05020105000000000000000000000000".substring(0, 30) + "0d", "echo");
            Settings.Wrist off = w.withEnabled(false);
            eq(Hex.encode(off.write()).substring(0, 6), "050202", "off");
            eq(Hex.encode(off.write()).substring(6, 8), "05", "options kept");
        });
        test("display fixture", () -> {
            Settings.Display d = Settings.Display.parse(h("1f01050114000000053c050100000081"));
            eq(d.seconds, 5, "seconds");
            eq(d.min, 5, "min");
            eq(d.max, 60, "max");
            eq(d.step, 5, "step");
            eq(d.alpha, 0x14, "alpha");
            eq(d.supportsAlwaysOn, 0, "aod");
            byte[] w = d.withSeconds(10).write();
            hex(Arrays.copyOfRange(w, 0, 8), "1f020a0114000000", "preserve");
            throwsP(() -> d.withSeconds(7), "step");
            throwsP(() -> d.withSeconds(65), "max");
            byte[] face = d.withFaceStyle(3, 90).write();
            eq(face[2] & 255, 5, "face keeps seconds");
            eq(face[4] & 255, 10, "dimming");
        });
        test("time format fixture", () -> {
            Settings.TimeFormat t = Settings.TimeFormat.parse(h("0a010000000000000000a002000000ad"));
            ok(t.is24Hour && t.metric, "24h metric");
            eq(t.heartWarning, 0xa0, "warning");
            eq(t.extra, 2, "extra");
            byte[] w = t.with(false, true).write();
            hex(Arrays.copyOfRange(w, 0, 12), "0a020100000000000000a002", "preserve profile");
            Settings.TimeFormat changedSbp = Settings.TimeFormat.parse(ShortProtocol.frame(0x0a, 1, 0, 0, 0, 0, 0, 0, 120, 0, 0xa0, 2));
            ok(!t.sameStored(changedSbp), "sbp change detected");
            Settings.TimeFormat changedExtra = Settings.TimeFormat.parse(ShortProtocol.frame(0x0a, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0xa0, 1));
            ok(!t.sameStored(changedExtra), "extra change detected");
            ok(t.sameStored(Settings.TimeFormat.parse(h("0a010000000000000000a002000000ad"))), "same");
        });
        test("units", () -> {
            Settings.Units u = Settings.Units.parse(ShortProtocol.frame(0x19, 1, 1, 1));
            ok(u.celsius, "c");
            hex(Arrays.copyOfRange(u.withCelsius(false).write(), 0, 4), "19020102", "f");
        });
        test("goals round trip", () -> {
            Settings.Goals g = new Settings.Goals(8000, 300, 5000, 30, 480);
            byte[] w = g.write();
            byte[] read = w.clone();
            read[1] = 1;
            read[15] = (byte) ShortProtocol.sum(read, 15);
            ok(g.sameStored(Settings.Goals.parse(read)), "round trip");
            eq(w[2] & 255, 8000 & 255, "LE");
        });
        test("sedentary BCD", () -> {
            Settings.Sedentary s = Settings.Sedentary.parse(ShortProtocol.frame(0x26, 0x09, 0x00, 0x18, 0x30, 0x7f, 60));
            eq(s.endMinute, 30, "BCD");
            hex(Arrays.copyOfRange(s.write(), 0, 7), "250900183" + "07f3c", "write");
            throwsP(() -> s.with(9, 0, 18, 30, 0x7f, 45).write(), "cycle 45");
            throwsP(() -> Settings.Sedentary.parse(ShortProtocol.frame(0x26, 0x0a, 0, 0, 0, 0, 60)), "bad BCD");
        });
        test("weather frame forwards server fields", () -> {
            Settings.WeatherDay d = new Settings.WeatherDay(0x1_2345_6789L, 3, -5, 12, 80, false);
            hex(Arrays.copyOf(d.write(2), 11), "1a02896745230" + "3fb0c5002", "index, low32 LE, type, signed temps, humidity, umbrella 2");
            throwsP(() -> new Settings.WeatherDay(0, 0, 1, 2, 3, true), "type 0 is skipped, not sent");
            throwsP(() -> new Settings.WeatherDay(0, 1, -129, 2, 3, true), "no wrap");
        });
        test("weather numbers must be exact before narrowing", () -> {
            Settings.WeatherDay d = Settings.WeatherDay.fromNumbers(1730505600L, 3, -5, 127, 255, true);
            eq(d.high, 127, "upper byte bound");
            eq(Settings.WeatherDay.fromNumbers(1L, 255, -128, 0, 0, false).low, -128, "lower bound");
            eq(Settings.WeatherDay.fromNumbers(4294967297L, 3, 1, 2, 3, true).rawTimestamp, 4294967297L, "long timestamp kept exactly");
            eq(Settings.WeatherDay.fromNumbers(1.0e9, 3.0, -5.0, 12.0, 80.0, true).weatherType, 3, "integral doubles accepted");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1L, 4294967297L, 1, 2, 3, true), "REGRESSION oversized type not wrapped to 1");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1L, 3, 12.7, 20, 3, true), "fractional temperature rejected");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1.5, 3, 1, 2, 3, true), "fractional timestamp rejected");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1L, 3, 128, 2, 3, true), "temperature above byte");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1L, 3, 1, 2, 256, true), "humidity above byte");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1L, 3, Double.NaN, 2, 3, true), "NaN");
            throwsP(() -> Settings.WeatherDay.fromNumbers(1e17, 3, 1, 2, 3, true), "double too large to be exact");
            throwsP(() -> Settings.WeatherDay.fromNumbers(new java.math.BigInteger("18446744073709551617"), 3, 1, 2, 3, true), "BigInteger overflow");
            throwsP(() -> Settings.WeatherDay.fromNumbers(new java.math.BigDecimal("12.5"), 3, 1, 2, 3, true), "BigDecimal fraction");
            throwsP(() -> Settings.WeatherDay.fromNumbers(null, 3, 1, 2, 3, true), "missing");
        });
        test("cycle write layout", () -> {
            Settings.Cycle c = new Settings.Cycle(5, 28, 10, 5, true, 2, 3, 20, 30);
            hex(Arrays.copyOf(c.write(), 12), "2b0201051c0a05010203141e", "binary bytes, not BCD");
            throwsP(() -> new Settings.Cycle(5, 28, 1, 1, true, 0, 1, 20, 30), "lead day 0 is not an offered value");
            eq(Settings.Cycle.daysBetween(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 10, 23)), 10, "absolute days");
            throwsP(() -> new Settings.Cycle(5, 28, 300, 5, true, 1, 1, 20, 30), "no silent wrap above 255");
            throwsP(() -> new Settings.Cycle(0, 28, 1, 1, false, 1, 1, 0, 0), "period length");
        });
        test("drink slot keeps raw enable 2 through a time edit", () -> {
            byte[] reply = ShortProtocol.frame(0x28, 3, 2, 0x09, 0x30, 1, 0, 1, 0, 1, 0, 1);
            Settings.Alarm d = Settings.Alarm.parse(true, reply, 3);
            ok(d.enabled && d.rawEnable == 2, "raw 2 read as on and kept");
            Settings.Alarm e = d.edited(true, 10, 0, d.weekMask);
            eq(e.write(true)[2] & 255, 2, "unchanged switch keeps raw 2");
            eq(e.write(true)[3] & 255, 0x10, "BCD hour");
            eq(d.edited(false, 9, 30, d.weekMask).write(true)[2] & 255, 0, "turning off writes 0");
            hex(Arrays.copyOf(e.write(true), 12), "27030210000100010001000" + "1", "write layout");
            throwsP(() -> Settings.Alarm.read(true, 8), "slot 8");
            throwsP(() -> Settings.Alarm.parse(true, ShortProtocol.frame(0x28, 3, 3, 0x09, 0x30), 3), "enable 3");
            ok(e.sameStored(Settings.Alarm.parse(true, readOf(e.write(true)), 3)), "readback compare");
        });
        test("alarm round trip", () -> {
            Settings.Alarm a = new Settings.Alarm(2, true, 7, 45, 0b0011111);
            byte[] w = a.write(false);
            byte[] reply = Arrays.copyOf(w, 16);
            reply[0] = 0x24;
            reply[15] = (byte) ShortProtocol.sum(reply, 15);
            Settings.Alarm b = Settings.Alarm.parse(false, reply, 2);
            eq(b.hour, 7, "hour");
            eq(b.minute, 45, "minute");
            eq(b.weekMask, 0b0011111, "mask");
            throwsP(() -> Settings.Alarm.parse(false, reply, 3), "slot mismatch");
            throwsP(() -> new Settings.Alarm(5, true, 7, 0, 1).write(false), "slot 5");
        });
    }

    static void notifications() {
        test("hello adam fixture", () -> {
            List<byte[]> f = Notifications.frames("Hello Adam", Notifications.SMS);
            eq(f.size(), 1, "one chunk");
            hex(f.get(0), "7201010148656c6c6f204164616d00fc", "captured");
            ok(Notifications.chunkAcknowledged(h("72000000000000000000000000000072")), "ack");
        });
        test("multi chunk", () -> {
            List<byte[]> f = Notifications.frames("0123456789abcdefghijk", 1); // 21 bytes
            eq(f.size(), 2, "count");
            eq(f.get(1)[3] & 255, 2, "index");
            eq(f.get(1)[2] & 255, 2, "total");
            ok(ShortProtocol.checksumValid(f.get(1)), "checksum");
        });
        test("unicode reassembly", () -> {
            String s = "Grüße 😀 你好 — ok";
            List<byte[]> f = Notifications.frames(s, 14);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            for (byte[] p : f) b.write(p, 4, 11);
            eq(new String(b.toByteArray(), StandardCharsets.UTF_8).replace("\0", ""), s, "round trip");
        });
        test("256 limit without split pair", () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 255; i++) s.append('a');
            s.append("😀tail");
            String n = Notifications.normalize(s.toString());
            eq(n.length(), 255, "pair dropped");
            ok(!Character.isHighSurrogate(n.charAt(n.length() - 1)), "no lone high");
        });
        test("lone surrogate replaced", () -> ok(Notifications.normalize("a\uD800b").equals("a�b"), "replaced"));
        test("empty rejected", () -> throwsP(() -> Notifications.frames("  ", 1), "empty"));
        test("call transitions: incoming, answered same key, removal", () -> {
            CallTracker t = new CallTracker();
            CallTracker.Event e = t.posted("k1", CallTracker.INCOMING, "Alex");
            ok(e != null && e.type == 0 && "Alex".equals(e.text), "ring with caller");
            ok(t.posted("k1", CallTracker.INCOMING, "Alex") == null, "repeat update does not re-ring");
            e = t.posted("k1", CallTracker.ONGOING, "Alex");
            ok(e != null && e.type == 4, "answered clears ringing");
            ok(!t.ringing(), "no longer ringing");
            ok(t.posted("k1", CallTracker.ONGOING, "Alex") == null, "ongoing call does not ring");
            ok(t.removed("k1") == null, "already cleared");
        });
        test("call transitions: outgoing, unknown, screening never ring", () -> {
            CallTracker t = new CallTracker();
            ok(t.posted("out", CallTracker.ONGOING, "Bob") == null, "outgoing/ongoing");
            ok(t.posted("u", CallTracker.UNKNOWN, "Bob") == null, "unknown");
            ok(t.posted("s", CallTracker.SCREENING, "Bob") == null, "screening");
            ok(!t.ringing(), "nothing ringing");
            t.posted("k", CallTracker.INCOMING, "C");
            CallTracker.Event e = t.posted("k", CallTracker.UNKNOWN, "C");
            ok(e != null && e.type == 4, "ringing call turning unknown is cleared, not kept ringing");
        });
        test("call transitions: removal and missing caller text", () -> {
            CallTracker t = new CallTracker();
            CallTracker.Event e = t.posted("k", CallTracker.INCOMING, "  ");
            ok(e != null && e.type == 0 && e.text == null, "ring without invented text");
            ok(t.removed("other") == null, "other removal ignored");
            e = t.removed("k");
            ok(e != null && e.type == 4 && e.text == null, "end without invented text");
            List<byte[]> f = Notifications.callFrames(4, null);
            eq(f.size(), 1, "one padded chunk");
            hex(f.get(0), "720401010000000000000000000000" + "78", "padded type 4 control frame");
            eq(Notifications.callFrames(0, "Alex").get(0)[1] & 255, 0, "ring frame type 0");
        });
        test("REGRESSION adapter: ringing call update without call type clears ringing", () -> {
            CallTracker t = new CallTracker();
            CallTracker.Update u = t.onPosted("k", CallTracker.INCOMING, "Alex");
            ok(u.callPath && u.event != null && u.event.type == 0, "incoming rings");
            u = t.onPosted("k", null, null);                                   // same key, call type field dropped
            ok(u.callPath, "kept on the call path, not generic notifications");
            ok(u.event != null && u.event.type == 4, "ringing cleared");
            eq(u.event.text, "Alex", "end uses the caller text from the ring, nothing invented");
            ok(!t.ringing(), "not ringing");
            u = t.onPosted("k", null, "Alex");
            ok(!u.callPath && u.event == null, "after clearing, the key is ordinary again");
            u = t.onPosted("other", null, null);
            ok(!u.callPath && u.event == null, "untracked notification without call type is not a call");
            u = t.onPosted("x", CallTracker.UNKNOWN, null);
            ok(u.callPath && u.event == null, "unknown call type never rings");
            u = t.onPosted("y", CallTracker.INCOMING, null);
            ok(u.event != null && u.event.type == 0 && u.event.text == null, "no caller text: none invented");
            u = t.onPosted("y", CallTracker.ONGOING, null);
            ok(u.event != null && u.event.type == 4, "answered still clears");
        });
        test("type mapping", () -> {
            eq(Notifications.typeForPackage("com.whatsapp"), 6, "whatsapp");
            eq(Notifications.typeForPackage("com.facebook.orca"), 17, "messenger");
            eq(Notifications.typeForPackage("com.facebook.katana"), 5, "facebook");
            eq(Notifications.typeForPackage("com.samsung.android.messaging"), 1, "sms");
            eq(Notifications.typeForPackage("com.example"), 14, "other");
        });
    }

    static void bulk() {
        test("crc known answer", () -> eq(BulkProtocol.crc16("123456789".getBytes(StandardCharsets.US_ASCII)), 0x4b37, "4B37"));
        test("empty envelope", () -> hex(BulkProtocol.frame(0x35, new byte[0]), "bc350000ffff", "list request"));
        test("envelope round trip", () -> {
            byte[] f = BulkProtocol.frame(0x3a, new byte[]{1});
            eq(BulkProtocol.payload(f, 0x3a)[0], (byte) 1, "payload");
            throwsP(() -> BulkProtocol.payload(f, 0x35), "opcode");
            byte[] bad = f.clone();
            bad[6] = 2;
            throwsP(() -> BulkProtocol.payload(bad, 0x3a), "crc");
            throwsP(() -> BulkProtocol.payload(Arrays.copyOf(f, 6), 0x3a), "length");
        });
        test("assembler fragments", () -> {
            byte[] f = BulkProtocol.frame(0x35, "abcdefghijklmnopqrstuvwxyz0123456789".getBytes());
            BulkProtocol.Assembler a = new BulkProtocol.Assembler();
            List<byte[]> out = new ArrayList<>();
            for (byte[] p : BulkProtocol.fragments(f, 20)) out.addAll(a.accept(p));
            eq(out.size(), 1, "one frame");
            ok(Arrays.equals(out.get(0), f), "same");
            eq(a.accept(new byte[]{1, 2, 3}).size(), 0, "garbage discarded");
            eq(a.discarded(), 3, "discard count");
        });
        test("assembler two frames in one notification", () -> {
            byte[] a1 = BulkProtocol.frame(0x37, new byte[]{0});
            byte[] a2 = BulkProtocol.frame(0x38, new byte[]{0});
            byte[] both = new byte[a1.length + a2.length];
            System.arraycopy(a1, 0, both, 0, a1.length);
            System.arraycopy(a2, 0, both, a1.length, a2.length);
            eq(new BulkProtocol.Assembler().accept(both).size(), 2, "two");
        });
        test("assembler corrupt crc", () -> {
            byte[] f = BulkProtocol.frame(0x35, new byte[]{1, 2});
            f[7] = 9;
            throwsP(() -> new BulkProtocol.Assembler().accept(f), "crc");
        });
        test("wallpaper size", () -> {
            int[] px = new int[240 * 284];
            Arrays.fill(px, 0xff123456);
            byte[] w = BulkProtocol.rgb565Wallpaper(240, 284, px);
            eq(w.length, 136324, "length");
            hex(Arrays.copyOf(w, 4), "f0001c01", "prefix");
            int rgb = ((0x12 >> 3) << 11) | ((0x34 >> 2) << 5) | (0x56 >> 3);
            eq((w[4] & 255) | (w[5] & 255) << 8, rgb, "pixel");
            px[0] = 0x00123456;
            throwsP(() -> BulkProtocol.rgb565Wallpaper(240, 284, px), "alpha");
        });
        test("init payload", () -> hex(BulkProtocol.resourceInit(false, "time2_bg_img.ui", 136324),
                Hex.encode(BulkProtocol.frame(0x31, h("01" + "84140200" + "00000000" + "0f" + Hex.encode("time2_bg_img.ui".getBytes())))),
                "init"));
        test("init rejects bad names", () -> {
            throwsP(() -> BulkProtocol.resourceInit(true, "a/b.di", 10), "slash");
            throwsP(() -> BulkProtocol.resourceInit(true, "x.di", 0), "empty");
            throwsP(() -> BulkProtocol.resourceInit(true, "x.di", BulkProtocol.MAX_FACE_FILE + 1), "cap");
        });
        test("layout write/read", () -> {
            List<BulkProtocol.LayoutEntry> e = Arrays.asList(new BulkProtocol.LayoutEntry(1, 28, 78, 0xffffff),
                    new BulkProtocol.LayoutEntry(2, 93, 17, 0x00ff00), new BulkProtocol.LayoutEntry(3, 39, 215, 0x0000ff));
            byte[] f = BulkProtocol.layoutWrite(240, 284, e);
            eq(f.length, 6 + 25, "length");
            byte[] reply = BulkProtocol.frame(0x3a, Arrays.copyOfRange(f, 6, f.length));
            List<BulkProtocol.LayoutEntry> r = BulkProtocol.layoutRead(reply);
            eq(r.get(0).x, 28, "clock x");
            eq(r.get(2).y, 215, "data y");
            eq(r.get(1).rgb, 0x00ff00, "battery rgb");
            throwsP(() -> BulkProtocol.layoutWrite(240, 284, Arrays.asList(e.get(0), e.get(0), e.get(1))), "dup");
            byte[] blank = BulkProtocol.frame(0x3a, new byte[25]);
            throwsP(() -> BulkProtocol.layoutRead(blank), "zero types are not filled in");
            byte[] echo = Arrays.copyOfRange(f, 6, f.length);
            ok(!BulkProtocol.isLayoutReadPayload(echo), "write echo is not a read");
            byte[] readPayload = echo.clone();
            readPayload[0] = 1;
            ok(BulkProtocol.isLayoutReadPayload(readPayload), "read reply");
            throwsP(() -> BulkProtocol.layoutWrite(240, 284, Collections.singletonList(e.get(0))), "count");
        });
        test("face list", () -> {
            byte[] p = h("02" + "01" + "03" + Hex.encode("abc".getBytes()) + "00" + "02" + Hex.encode("de".getBytes()));
            List<BulkProtocol.InstalledFace> l = BulkProtocol.faceList(p);
            eq(l.size(), 2, "two");
            ok(l.get(0).deletable && !l.get(1).deletable, "delete flags");
            eq(l.get(1).name, "de", "name");
            throwsP(() -> BulkProtocol.faceList(h("03010161")), "count mismatch");
            throwsP(() -> BulkProtocol.faceList(h("010105")), "truncated");
        });
        test("REGRESSION delete flags are not the current face", () -> {
            byte[] p = h("04" + "0101" + "61" + "0101" + "62" + "0101" + "63" + "0001" + "64");
            List<BulkProtocol.InstalledFace> l = BulkProtocol.faceList(p);
            eq(l.size(), 4, "four rows in watch order");
            eq(l.get(3).name, "d", "order kept");
            ok(l.get(0).deletable && l.get(1).deletable && l.get(2).deletable && !l.get(3).deletable, "three deletable");
            int index = ShortProtocol.faceIndex(ShortProtocol.frame(0x75, 0, 2));
            eq(BulkProtocol.currentRow(l, index), 1, "only row index-1 is current");
            eq(BulkProtocol.currentRow(l, null), -1, "no 75 read: unknown");
            eq(BulkProtocol.currentRow(l, 0), -1, "index 0: unknown");
            eq(BulkProtocol.currentRow(l, 5), -1, "index past list: unknown");
        });
        test("status byte required", () -> {
            throwsP(() -> BulkProtocol.statusByte(BulkProtocol.frame(0x36, new byte[0]), 0x36), "empty");
            eq(BulkProtocol.statusByte(BulkProtocol.frame(0x36, new byte[]{0}), 0x36), 0, "zero");
            eq(BulkProtocol.statusByte(BulkProtocol.frame(0x38, new byte[]{3}), 0x38), 3, "error");
        });
        test("literal lzo structure", () -> {
            for (int n : new int[]{1, 3, 4, 17, 238, 239, 1024}) {
                byte[] e = LiteralLzo.encode(new byte[n]);
                byte[] tail = Arrays.copyOfRange(e, e.length - 3, e.length);
                hex(tail, "110000", "end marker " + n);
            }
        });
        test("contacts encoding", () -> {
            List<Contacts.Contact> list = new ArrayList<>();
            list.add(new Contacts.Contact("Adam", "+1 (555) 010-2000"));
            List<byte[]> p = Contacts.payloads(list, 500);
            eq(p.size(), 2, "header and one group");
            hex(p.get(0), "02000100", "header");
            hex(p.get(1), "0201" + "04" + Hex.encode("Adam".getBytes()) + "0c" + Hex.encode("+15550102000".getBytes()), "group");
            eq(Contacts.clip("ééééééééééééééééé", 32).length, 32, "clip on boundary");
            eq(Contacts.clip("aéééééééééééééééé", 32).length, 31, "no split char");
            List<Contacts.Contact> many = new ArrayList<>();
            for (int i = 0; i < 40; i++) many.add(new Contacts.Contact("Name number " + i + " with long text", "+4915112345678" + i));
            List<byte[]> mp = Contacts.payloads(many, 500);
            eq(mp.get(0)[0] & 255, mp.size(), "total matches");
            for (int i = 1; i < mp.size(); i++) {
                eq(mp.get(i)[1] & 255, i, "group index");
                ok(mp.get(i).length - 2 <= 950 + 1 + 32 + 1 + 18, "group bound");
            }
            eq(Contacts.payloads(new ArrayList<>(), 500).size(), 2, "empty list still sends a final group");
            throwsP(() -> Contacts.payloads(many, 10), "over maximum");
            throwsP(() -> new Contacts.Contact("x", "abc"), "no digits");
            throwsP(() -> new Contacts.Contact("x", "+491511234567890123456"), "REGRESSION overlong number rejected, never clipped");
            eq(Contacts.sentName(new Contacts.Contact("ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ", "1")).length(), 16, "sent name shown as sent");
            throwsP(() -> Contacts.requireOk(BulkProtocol.frame(0x29, new byte[0])), "missing status");
            throwsP(() -> Contacts.requireOk(BulkProtocol.frame(0x29, new byte[]{1})), "refused");
            Contacts.requireOk(BulkProtocol.frame(0x29, new byte[]{0}));
        });
        test("active alarm list", () -> {
            byte[] payload = h("01" + "02" + "0b" + "be" + "e001" + Hex.encode("Wake up".getBytes()) + "04" + "00" + "2c01");
            List<Alarms.Alarm> l = Alarms.parse(payload);
            eq(l.size(), 2, "two");
            Alarms.Alarm a = l.get(0);
            ok(a.enabled, "bit7");
            eq(a.repeatMask, 0x3e, "weekdays");
            eq(a.hour(), 8, "08:00");
            eq(a.title, "Wake up", "title");
            ok(!l.get(1).enabled && l.get(1).minuteOfDay == 300 && l.get(1).title.isEmpty(), "untitled once alarm");
            byte[] w = Alarms.writeRequest(l);
            byte[] wp = BulkProtocol.payload(w, 0x2c);
            eq(wp[0], (byte) 2, "write action");
            hex(Arrays.copyOfRange(wp, 1, wp.length), Hex.encode(Arrays.copyOfRange(payload, 1, payload.length)), "unchanged records byte-identical");
            throwsP(() -> Alarms.parse(h("010161")), "count mismatch / short record");
            throwsP(() -> Alarms.parse(h("0101" + "05" + "80" + "a005")), "truncated");
            throwsP(() -> Alarms.parse(h("0101" + "04" + "80" + "a005")), "minute 1440");
            throwsP(() -> Alarms.parse(h("0200")), "write echo is not a read");
            eq(Alarms.parse(h("0100")).size(), 0, "empty list");
            eq(Alarms.repeatText(0x3e), "Weekdays", "weekdays");
            eq(Alarms.repeatText(0x03), "Mon Sun", "Monday first");
            eq(new Alarms.Alarm(true, 0, 0, "ÄÄÄÄÄÄÄÄÄÄÄÄÄ").title.getBytes(StandardCharsets.UTF_8).length, 24, "title clip");
        });
        test("REGRESSION long alarm title kept through time edit", () -> {
            String title40 = "0123456789012345678901234567890123456789";
            byte[] rec = new byte[4 + 40];
            rec[0] = 44;
            rec[1] = (byte) 0x80;
            rec[2] = (byte) 0xe0;
            rec[3] = 1;
            System.arraycopy(title40.getBytes(StandardCharsets.UTF_8), 0, rec, 4, 40);
            byte[] payload = new byte[2 + rec.length];
            payload[0] = 1;
            payload[1] = 1;
            System.arraycopy(rec, 0, payload, 2, rec.length);
            Alarms.Alarm a = Alarms.parse(payload).get(0);
            Alarms.Alarm edited = a.edited(true, 0x7f, 9 * 60, a.title);
            byte[] w = BulkProtocol.payload(Alarms.writeRequest(Collections.singletonList(edited)), 0x2c);
            eq(w[2] & 255, 44, "record length still 44");
            eq(new String(Arrays.copyOfRange(w, 6, 46), StandardCharsets.UTF_8), title40, "title bytes unchanged");
            eq(a.edited(true, 0, 0, "New").title, "New", "changed title applies");
        });
        test("REGRESSION padded long alarm title kept through time edit", () -> {
            String padded = "  " + "abcdefghijklmnopqrstuvwxyz0123456789" + "    "; // 42 bytes with outer spaces
            byte[] tb = padded.getBytes(StandardCharsets.UTF_8);
            eq(tb.length, 42, "fixture length");
            byte[] payload = new byte[2 + 4 + tb.length];
            payload[0] = 1;
            payload[1] = 1;
            payload[2] = (byte) (4 + tb.length);
            payload[3] = (byte) 0x81;
            payload[4] = (byte) 0x2c;
            payload[5] = 1;
            System.arraycopy(tb, 0, payload, 6, tb.length);
            Alarms.Alarm a = Alarms.parse(payload).get(0);
            eq(a.title, padded, "parsed with spaces");
            Alarms.Alarm edited = a.edited(true, a.repeatMask, 7 * 60, a.title);
            byte[] w = BulkProtocol.payload(Alarms.writeRequest(Collections.singletonList(edited)), 0x2c);
            eq(w[2] & 255, 46, "record length unchanged");
            hex(Arrays.copyOfRange(w, 6, 48), Hex.encode(tb), "padded title bytes unchanged");
        });
        test("delete request", () -> hex(BulkProtocol.deleteFaceRequest("a.di"),
                Hex.encode(BulkProtocol.frame(0x39, h("01" + Hex.encode("a.di".getBytes())))), "delete"));
    }

    static void readers() {
        test("hr day with FF terminator keeps samples", () -> {
            Health.HeartRateDayReader r = new Health.HeartRateDayReader();
            ok(!r.accept(ShortProtocol.frame(0x15, 0, 24, 5)), "header");
            ok(!r.accept(ShortProtocol.frame(0x15, 1, 0, 0, 0, 0, 70, 71, 72, 0, 0, 0, 0, 0, 0)), "part 1");
            ok(r.accept(ShortProtocol.frame(0x15, 0xff)), "terminator");
            ok(!r.empty(), "not empty");
            eq(r.samples()[0], 70, "sample");
        });
        test("hr day empty", () -> {
            Health.HeartRateDayReader r = new Health.HeartRateDayReader();
            ok(r.accept(ShortProtocol.frame(0x15, 0xff)), "done");
            ok(r.empty(), "empty");
        });
        test("hr day order", () -> {
            Health.HeartRateDayReader r = new Health.HeartRateDayReader();
            r.accept(ShortProtocol.frame(0x15, 0, 3, 5));
            throwsP(() -> r.accept(ShortProtocol.frame(0x15, 2)), "skip");
        });
        test("hr day complete", () -> {
            Health.HeartRateDayReader r = new Health.HeartRateDayReader();
            r.accept(ShortProtocol.frame(0x15, 0, 3, 5));
            r.accept(ShortProtocol.frame(0x15, 1, 0x10, 0, 0, 0, 60));
            ok(r.accept(ShortProtocol.frame(0x15, 2, 61)), "last");
            eq(r.samples().length, 22, "9+13");
            eq(r.localEpochStart(), 16L, "time");
        });
        test("step detail requires header", () -> {
            Health.StepDetailReader r = new Health.StepDetailReader();
            throwsP(() -> r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 40, 0, 1, 1, 0, 100, 0, 70, 0)), "no header");
        });
        test("step detail ordered", () -> {
            Health.StepDetailReader r = new Health.StepDetailReader();
            ok(!r.accept(ShortProtocol.frame(0x43, 0xf0, 0, 1)), "header");
            ok(!r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 40, 0, 2, 1, 0, 100, 0, 70, 0)), "first");
            ok(r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 41, 1, 2, 2, 0, 50, 0, 30, 0)), "last");
            eq(r.slots().size(), 2, "two");
            eq(r.slots().get(0).calories, 10, "x10");
            eq(r.slots().get(0).caloriesRaw(), 1, "raw field kept");
            ok(r.slots().get(0).calorieTimesTenFlag, "flag kept");
            eq(r.slots().get(0).steps, 100, "steps");
        });
        test("step detail late single row", () -> {
            Health.StepDetailReader r = new Health.StepDetailReader();
            r.accept(ShortProtocol.frame(0x43, 0xf0, 0, 0));
            throwsP(() -> r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 40, 4, 5, 1, 0, 100, 0)), "late row");
        });
        test("step detail total change", () -> {
            Health.StepDetailReader r = new Health.StepDetailReader();
            r.accept(ShortProtocol.frame(0x43, 0xf0, 0, 0));
            r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 40, 0, 3));
            throwsP(() -> r.accept(ShortProtocol.frame(0x43, 0x26, 0x11, 0x02, 41, 1, 4)), "total");
        });
        test("step detail empty", () -> {
            Health.StepDetailReader r = new Health.StepDetailReader();
            ok(r.accept(ShortProtocol.frame(0x43, 0xff)) && r.empty(), "empty");
        });
        test("today steps big endian", () -> {
            Health.DayActivity d = Health.todaySteps(ShortProtocol.frame(0x48, 0, 0x09, 0xc4, 0, 0, 0, 0, 0, 90, 0, 0x07, 0xd0, 0, 12),
                    LocalDate.of(2026, 11, 2));
            eq(d.steps, 2500, "steps");
            eq(d.distance, 2000, "distance");
            eq(d.activeSeconds, 720, "minutes");
        });
        test("day totals two packets", () -> {
            Health.DayTotalsReader r = new Health.DayTotalsReader();
            ok(!r.accept(ShortProtocol.frame(7, 0, 0x01, 0x26, 0x11, 0x01, 0, 0x10, 0, 0, 0, 0, 0, 0, 50)), "p0");
            ok(r.accept(ShortProtocol.frame(7, 1, 0x01, 0x26, 0x11, 0x01, 0, 0x03, 0xe8, 0, 30, 1, 0xe0)), "p1");
            eq(r.result().steps, 4096, "steps");
            eq(r.result().distance, 1000, "distance");
            eq(r.result().sleepSeconds, 480 * 60, "sleep");
            Health.DayTotalsReader m = new Health.DayTotalsReader();
            m.accept(ShortProtocol.frame(7, 0, 0x01, 0x26, 0x11, 0x01));
            throwsP(() -> m.accept(ShortProtocol.frame(7, 1, 0x02, 0x26, 0x11, 0x01)), "date mismatch");
        });
        test("measurement start frames", () -> {
            hex(Arrays.copyOf(Health.startMeasurement(1), 3), "690100", "hr");
            hex(Arrays.copyOf(Health.startMeasurement(3), 3), "690325", "spo2");
            hex(Arrays.copyOf(Health.stopMeasurement(3), 4), "6a030000", "stop");
        });
        test("measurement outcomes: raw, measured, pending, failed", () -> {
            Health.MeasurementUpdate bp = Health.measurement(ShortProtocol.frame(0x69, 2, 0, 0, 200, 130), 2);
            eq(bp.outcome(), Health.Outcome.RAW_UNVERIFIED, "bp bytes stay raw");
            eq(bp.sbp, 200, "unsigned sbp");
            ok(!bp.hasResult(), "bp is not a completed measurement");
            Health.MeasurementUpdate o2 = Health.measurement(ShortProtocol.frame(0x69, 3, 0, 97), 3);
            eq(o2.outcome(), Health.Outcome.RAW_UNVERIFIED, "positive oxygen byte is raw, not percent");
            ok(!o2.hasResult() && o2.finished(), "ends stream without a result");
            Health.MeasurementUpdate hr = Health.measurement(ShortProtocol.frame(0x69, 1, 0, 72), 1);
            eq(hr.outcome(), Health.Outcome.MEASURED, "heart rate");
            ok(hr.hasResult(), "hr result");
            eq(Health.measurement(ShortProtocol.frame(0x69, 1, 0, 0), 1).outcome(), Health.Outcome.PENDING, "ack is not a result");
            eq(Health.measurement(ShortProtocol.frame(0x69, 3, 0, 0), 3).outcome(), Health.Outcome.PENDING, "zero oxygen pending");
            eq(Health.measurement(ShortProtocol.frame(0x69, 1, 0, 255), 1).outcome(), Health.Outcome.IMPOSSIBLE, "impossible hr");
            Health.MeasurementUpdate err = Health.measurement(ShortProtocol.frame(0x69, 3, 2, 0), 3);
            ok(err.failed() && !err.hasResult() && err.outcome() == Health.Outcome.FAILED, "error");
            throwsP(() -> Health.measurement(ShortProtocol.frame(0x69, 1, 0, 70), 3), "type mismatch");
        });
        test("stop never carries a value", () -> {
            for (int t = 1; t <= 3; t++) {
                byte[] f = Health.stopMeasurement(t);
                eq(f[1] & 255, t, "type");
                for (int i = 2; i < 15; i++) eq(f[i] & 255, 0, "zero payload byte " + i);
            }
        });
        test("history record differs from raw reply", () -> {
            // Decoded oxygen history (49) has an established [minute, value] record layout.
            Health.ManualHistory m = Health.manualHistory(h("00" + "1e00" + "61"));
            eq(m.records.get(0).value, 97, "history value");
            eq(m.records.get(0).minuteOfDay, 30, "history minute");
            // The same byte in a live 69 reply is not a decoded measurement.
            ok(!Health.measurement(ShortProtocol.frame(0x69, 3, 0, 97), 3).hasResult(), "live reply stays raw");
        });
        test("manual history", () -> {
            Health.ManualHistory m = Health.manualHistory(h("00" + "e001" + "48" + "0000" + "00"));
            eq(m.records.size(), 1, "zero value dropped");
            eq(m.records.get(0).minuteOfDay, 480, "minute");
            throwsP(() -> Health.manualHistory(h("000102")), "length");
            throwsP(() -> Health.manualHistory(h("00a00548")), "minute range");
        });
        test("sleep summary keeps unlabelled time and interval mismatch", () -> {
            // 22:36 (0x054c) to 07:00 (0x01a4) = 504 min; stages: light 120, deep 120, awake 20, unknown code 4 = 60 -> sum 320.
            Health.SleepDay d = Health.sleep(h("01" + "00" + "0c" + "4c05" + "a401" + "0278" + "0378" + "0514" + "043c")).get(0);
            Health.SleepSummary s = Health.summarize(d);
            eq(s.asleep(), 240, "light + deep");
            eq(s.awake, 20, "awake separate");
            eq(s.unlabelled, 60, "unlabelled kept");
            eq(s.stageSum, 320, "segment sum");
            eq(s.interval, 504, "recorded interval");
            ok(!s.consistent() && !s.complete(), "mismatch visible");
            Health.SleepDay onlyUnknown = Health.sleep(h("01" + "00" + "06" + "4c05" + "a401" + "0478")).get(0);
            Health.SleepSummary u = Health.summarize(onlyUnknown);
            ok(u.asleep() == 0 && u.unlabelled == 120 && !u.complete(), "zero light/deep with unknown stages is not complete");
            Health.SleepDay sameDay = Health.sleep(h("01" + "00" + "06" + "3c00" + "b400" + "0278")).get(0);
            Health.SleepSummary c = Health.summarize(sameDay);
            ok(c.consistent() && c.complete() && c.interval == 120, "matching record");
        });
        test("sleep", () -> {
            List<Health.SleepDay> d = Health.sleep(h("01" + "00" + "08" + "4c05" + "a401" + "0278" + "0378"));
            eq(d.size(), 1, "day");
            ok(d.get(0).startsPreviousDay(), "22:20 start");
            eq(d.get(0).stages.size(), 2, "stages");
            throwsP(() -> Health.sleep(h("01" + "00" + "07" + "4c05a40102")), "odd");
            throwsP(() -> Health.sleep(h("01" + "00" + "10" + "4c05")), "truncated");
        });
    }

    // ---------------------------------------------------------------------------------

    static final class FakeScheduler implements CommandQueue.Scheduler {
        long now;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        public CommandQueue.Cancellable schedule(Runnable r, long d) {
            long[] slot = {now + d, 0};
            due.add(slot);
            tasks.add(r);
            return () -> slot[1] = 1;
        }

        void advance(long ms) {
            now += ms;
            for (int i = 0; i < due.size(); i++) {
                long[] s = due.get(i);
                if (s[1] == 0 && s[0] <= now) {
                    s[1] = 1;
                    tasks.get(i).run();
                }
            }
        }
    }

    static final class FakeTransport implements CommandQueue.Transport {
        final List<byte[]> writes = new ArrayList<>();
        boolean refuse;

        public boolean write(Channel c, byte[] v) {
            if (refuse) return false;
            writes.add(v);
            return true;
        }
    }

    static final class Rec<T> implements Callback<T> {
        T value;
        Exception error;
        int calls;

        public void onResult(T v) {
            value = v;
            calls++;
        }

        public void onError(Exception e) {
            error = e;
            calls++;
        }
    }

    static void queue() {
        test("notification before write callback", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<Integer> r = new Rec<>();
            c.request("battery", ShortProtocol.batteryRequest(), ShortProtocol.NO_ACTION, ShortProtocol::battery, r);
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            eq(r.calls, 0, "waits for write callback");
            q.onWriteComplete(true);
            eq(r.value, 38, "value");
            ok(q.idle(), "idle");
        });
        test("serialized writes", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<Integer> a = new Rec<>(), b = new Rec<>();
            c.request("a", ShortProtocol.batteryRequest(), ShortProtocol.NO_ACTION, ShortProtocol::battery, a);
            c.request("b", Settings.readDnd(), 1, f -> Settings.Dnd.parse(f).startHour, b);
            eq(t.writes.size(), 1, "one in flight");
            q.onWriteComplete(true);
            eq(t.writes.size(), 1, "second waits for reply");
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            eq(t.writes.size(), 2, "second starts");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("06010216000800000000000000000027"));
            eq(b.value, 22, "dnd");
        });
        test("timeout fails without retry", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, s, null);
            Rec<Integer> r = new Rec<>();
            new WatchClient(q).request("battery", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, r);
            q.onWriteComplete(true);
            s.advance(WatchClient.SHORT_TIMEOUT_MS + 1);
            ok(r.error instanceof CommandQueue.TimeoutException, "timeout");
            eq(t.writes.size(), 1, "no retry");
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            eq(r.calls, 1, "late reply ignored");
        });
        test("unrelated and duplicate frames are unsolicited", () -> {
            FakeTransport t = new FakeTransport();
            List<byte[]> extra = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), (ch, f) -> extra.add(f));
            Rec<Capabilities> r = new Rec<>();
            new WatchClient(q).request("clock", ShortProtocol.clock(LocalDateTime.of(2026, 11, 2, 9, 48, 27), 1),
                    -1, Capabilities::parse, r);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x2f, 0xf4));
            q.onNotification(Channel.SHORT, h("0100010127f0001c010106f101320062"));
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("0100010127f0001c010106f101320062"));
            ok(r.value != null && r.value.displayWidth == 240, "capabilities");
            eq(extra.size(), 2, "package length and duplicate");
        });
        test("rejection fails", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<Object> r = new Rec<>();
            new WatchClient(q).request("brightness", ShortProtocol.frame(0x1b, 1), 1, f -> f, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("9bee0000000000000000000000000089"));
            ok(r.error instanceof ProtocolException.Rejected, "rejected");
        });
        test("corrupt reply fails", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<Integer> r = new Rec<>();
            new WatchClient(q).request("battery", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("0326000000000000000000000000002a"));
            ok(r.error instanceof ProtocolException, "corrupt");
        });
        test("stale action does not satisfy read", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<Settings.Dnd> r = new Rec<>();
            new WatchClient(q).request("dnd", Settings.readDnd(), 1, Settings.Dnd::parse, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("06020116000800000000000000000027"));
            ok(r.error != null && r.value == null, "write echo rejected");
        });
        test("disconnect fails all and ignores late callbacks", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<Integer> a = new Rec<>(), b = new Rec<>();
            c.request("a", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, a);
            c.request("b", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, b);
            q.onDisconnected();
            ok(a.error instanceof CommandQueue.DisconnectedException, "a");
            ok(b.error instanceof CommandQueue.DisconnectedException, "b");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            eq(a.calls + b.calls, 2, "no late success");
            Rec<Integer> late = new Rec<>();
            c.request("late", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, late);
            ok(late.error instanceof CommandQueue.DisconnectedException, "closed queue");
            eq(t.writes.size(), 1, "no new write");
        });
        test("cancel queued and running", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<Integer> a = new Rec<>(), b = new Rec<>(), d = new Rec<>();
            CommandQueue.Operation oa = c.request("a", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, a);
            CommandQueue.Operation ob = c.request("b", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, b);
            c.request("d", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, d);
            q.cancel(ob);
            ok(b.error instanceof CommandQueue.CancelledException, "queued cancel");
            q.cancel(oa);
            ok(a.error instanceof CommandQueue.CancelledException, "running cancel");
            q.onWriteComplete(true);
            eq(t.writes.size(), 1, "cancelled request stays current until its reply");
            q.onNotification(Channel.SHORT, ShortProtocol.frame(3, 40));
            eq(a.calls, 1, "cancelled request reports once");
            eq(t.writes.size(), 2, "d starts after the absorbed reply");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(3, 41));
            eq(d.value, 41, "d gets its own reply, not the cancelled one");
        });
        test("write failure invalidates session", () -> {
            FakeTransport t = new FakeTransport();
            List<Exception> invalid = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null, invalid::add);
            WatchClient c = new WatchClient(q);
            Rec<Integer> a = new Rec<>(), b = new Rec<>();
            c.request("a", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, a);
            c.request("b", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, b);
            q.onWriteComplete(false);
            ok(a.error instanceof CommandQueue.WriteFailedException, "failed");
            ok(b.error instanceof CommandQueue.DisconnectedException, "queued request failed");
            eq(invalid.size(), 1, "owner told to close GATT");
            eq(t.writes.size(), 1, "b never written");
        });
        test("refused first write keeps session", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            t.refuse = true;
            Rec<Integer> b = new Rec<>();
            new WatchClient(q).request("b", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, b);
            ok(b.error instanceof CommandQueue.WriteFailedException, "refused");
            ok(!q.closed(), "nothing reached the radio");
            t.refuse = false;
            Rec<Integer> c2 = new Rec<>();
            new WatchClient(q).request("c", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, c2);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            eq(c2.value, 38, "next request works");
        });
        test("REGRESSION timeout then same-opcode late reply", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            List<Exception> invalid = new ArrayList<>();
            List<byte[]> unsolicited = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, s, (ch, f) -> unsolicited.add(f), invalid::add);
            WatchClient c = new WatchClient(q);
            Rec<Settings.Dnd> a = new Rec<>(), b = new Rec<>();
            c.request("A", Settings.readDnd(), 1, Settings.Dnd::parse, a);
            c.request("B", Settings.readDnd(), 1, Settings.Dnd::parse, b);
            q.onWriteComplete(true);
            s.advance(WatchClient.SHORT_TIMEOUT_MS + 1);
            ok(a.error instanceof CommandQueue.TimeoutException, "A unknown outcome");
            ok(((CommandQueue.TimeoutException) a.error).writeConfirmed, "A write had completed");
            ok(b.error instanceof CommandQueue.DisconnectedException, "B failed, not started");
            eq(t.writes.size(), 1, "B never written");
            eq(invalid.size(), 1, "owner notified once");
            q.onNotification(Channel.SHORT, h("06010216000800000000000000000027"));
            q.onWriteComplete(true);
            eq(b.calls, 1, "late A reply cannot complete B");
            eq(a.calls, 1, "A reported once");
            eq(unsolicited.size(), 0, "closed session ignores frames");
        });
        test("REGRESSION missing write callback times out", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            List<Exception> invalid = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, s, null, invalid::add);
            WatchClient c = new WatchClient(q);
            Rec<Integer> a = new Rec<>(), b = new Rec<>();
            c.request("A", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, a);
            c.request("B", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, b);
            s.advance(WatchClient.SHORT_TIMEOUT_MS + 1);
            ok(a.error instanceof CommandQueue.TimeoutException, "A timed out");
            ok(!((CommandQueue.TimeoutException) a.error).writeConfirmed, "write never confirmed");
            ok(b.error != null, "B does not hang");
            ok(q.closed() && invalid.size() == 1, "session invalidated");
            q.onWriteComplete(true);
            eq(t.writes.size(), 1, "late callback starts nothing");
        });
        test("fresh session recovers after invalidation", () -> {
            FakeScheduler s = new FakeScheduler();
            FakeTransport oldT = new FakeTransport();
            CommandQueue old = new CommandQueue(oldT, s, null, e -> { });
            new WatchClient(old).request("A", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, new Rec<>());
            s.advance(WatchClient.SHORT_TIMEOUT_MS + 1);
            FakeTransport t = new FakeTransport();
            CommandQueue fresh = new CommandQueue(t, s, null, e -> { });
            Rec<Integer> r = new Rec<>();
            new WatchClient(fresh).request("A2", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, r);
            old.onWriteComplete(true);
            old.onNotification(Channel.SHORT, ShortProtocol.frame(3, 99));
            eq(r.calls, 0, "old generation cannot touch new session");
            fresh.onWriteComplete(true);
            fresh.onNotification(Channel.SHORT, ShortProtocol.frame(3, 55));
            eq(r.value, 55, "fresh read");
        });
        test("failed multi-write sends no remaining writes", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<Void> r = new Rec<>();
            new WatchClient(q).notify("0123456789abcdefghijklmnopqrstuvwxyz", 1, r);
            q.onNotification(Channel.SHORT, h("f2000000000000000000000000000072".substring(0, 30) + "f2"));
            ok(r.error instanceof ProtocolException.Rejected, "rejected");
            q.onWriteComplete(true);
            eq(t.writes.size(), 1, "stopped after the failure");
            ok(q.idle() && !q.closed(), "session usable");
        });
        test("REGRESSION lost write callback with no reply deadline", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            List<Exception> invalid = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, s, null, invalid::add);
            WatchClient c = new WatchClient(q);
            Rec<Void> measure = new Rec<>();
            CommandQueue.Operation op = q.submit("measure", Channel.SHORT, Collections.singletonList(Health.startMeasurement(3)),
                    (ch, f) -> CommandQueue.Step.MORE, 0, measure);
            Rec<Object> stop = new Rec<>();
            c.request("stop", Health.stopMeasurement(3), -1, f -> f, stop);
            s.advance(CommandQueue.WRITE_CALLBACK_MS + 1);
            ok(measure.error instanceof CommandQueue.WriteFailedException, "measure fails");
            ok(stop.error instanceof CommandQueue.DisconnectedException, "queued stop fails, does not stall");
            eq(measure.calls + stop.calls, 2, "each reported once");
            eq(invalid.size(), 1, "owner closes session once");
            q.release(op, new IllegalStateException("late give-up"));
            s.advance(60000);
            q.onWriteComplete(true);
            eq(measure.calls + stop.calls, 2, "nothing reported twice");
            eq(invalid.size(), 1, "still once");
            eq(t.writes.size(), 1, "stop never written on a dead session");
        });
        test("release after write confirmed lets stop run", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, s, null, e -> { throw new AssertionError("no invalidation"); });
            WatchClient c = new WatchClient(q);
            Rec<Void> measure = new Rec<>();
            CommandQueue.Operation op = q.submit("measure", Channel.SHORT, Collections.singletonList(Health.startMeasurement(3)),
                    (ch, f) -> (f[0] & 0x7f) == 0x69 ? CommandQueue.Step.MORE : CommandQueue.Step.NOT_MINE, 0, measure);
            Rec<Object> stop = new Rec<>();
            q.onWriteComplete(true);
            s.advance(44000);
            q.release(op, new IllegalStateException("no result"));
            ok(measure.error instanceof IllegalStateException, "honest timeout");
            c.request("stop", Health.stopMeasurement(3), -1, f -> f, stop);
            eq(t.writes.size(), 2, "stop sent");
            hex(t.writes.get(1), Hex.encode(Health.stopMeasurement(3)), "cancel form");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x6a, 3));
            ok(stop.error == null && stop.calls == 1, "stop acknowledged");
        });
        test("release with write in flight waits for callback", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, s, null);
            Rec<Void> measure = new Rec<>();
            CommandQueue.Operation op = q.submit("measure", Channel.SHORT, Collections.singletonList(Health.startMeasurement(1)),
                    (ch, f) -> CommandQueue.Step.MORE, 0, measure);
            q.release(op, new IllegalStateException("gave up"));
            Rec<Object> stop = new Rec<>();
            new WatchClient(q).request("stop", Health.stopMeasurement(1), -1, f -> f, stop);
            eq(t.writes.size(), 1, "stop waits");
            q.onWriteComplete(true);
            eq(t.writes.size(), 2, "stop starts after callback");
            ok(!q.closed(), "session kept");
        });
        test("write watchdog cleared by normal callbacks", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, s, null, e -> { throw new AssertionError("no invalidation"); });
            Rec<Integer> r = new Rec<>();
            new WatchClient(q).request("battery", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, r);
            s.advance(1000);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(3, 50));
            s.advance(60000);
            ok(!q.closed() && r.value == 50, "no late watchdog");
        });
        test("REGRESSION stale posted session callbacks", () -> {
            SessionGate g = new SessionGate();
            List<String> state = new ArrayList<>();
            List<Runnable> mainQueue = new ArrayList<>();
            int a = g.begin();                              // connect: session A
            mainQueue.add(() -> { if (g.current(a)) state.add("A closed: clear state and retry A"); });
            g.invalidate();                                 // forget runs before the posted close
            int b = g.begin();                              // new connect: session B
            mainQueue.add(() -> { if (g.current(b)) state.add("B ready"); });
            for (Runnable r : mainQueue) r.run();
            eq(state, Collections.singletonList("B ready"), "old close ignored, new session kept");
            g.invalidate();                                 // user disconnect
            boolean[] ran = {false};
            Runnable lateReady = () -> { if (g.current(b)) ran[0] = true; };
            lateReady.run();
            ok(!ran[0], "ready after disconnect ignored");
        });
        test("exact target name", () -> {
            ok(SessionGate.isTarget("P71J_CEC9", "P71J_CEC9"), "exact");
            ok(SessionGate.isTarget("p71j_cec9", "P71J_CEC9"), "case");
            ok(!SessionGate.isTarget("P71J_AB12", "P71J_CEC9"), "other P71");
            ok(!SessionGate.isTarget("P71J_CEC9X", "P71J_CEC9"), "prefix only");
            ok(!SessionGate.isTarget(null, "P71J_CEC9"), "no name");
            ok(!SessionGate.isTarget("P71J_CEC9", ""), "no target");
        });
        test("REGRESSION second measurement while one runs, duplicate replies", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler sch = new FakeScheduler();
            List<byte[]> extra = new ArrayList<>();
            CommandQueue q = new CommandQueue(t, sch, (ch, f) -> extra.add(f), e -> { throw new AssertionError("no invalidation"); });
            WatchClient c = new WatchClient(q);
            Measurement m = new Measurement(c, sch);
            Rec<Health.MeasurementUpdate> a = new Rec<>(), b = new Rec<>(), b2 = new Rec<>();
            m.start(1, a);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 0));       // ack, not a result
            m.start(1, b);
            ok(b.error instanceof Measurement.BusyException, "second request refused while A runs");
            eq(t.writes.size(), 1, "B never queued");
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 72));
            eq(a.value.value, 72, "A result");
            eq(t.writes.size(), 2, "stop A written");
            hex(t.writes.get(1), Hex.encode(Health.stopMeasurement(1)), "stop carries no value");
            m.start(1, b2);
            ok(b2.error instanceof Measurement.BusyException, "still refused until stop ack");
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 72));       // duplicate A reply
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 73));       // late A reply
            eq(extra.size(), 2, "late A replies are unsolicited");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x6a, 1));              // stop ack
            ok(!m.active(), "gate released by stop ack");
            Rec<Health.MeasurementUpdate> next = new Rec<>();
            m.start(1, next);
            eq(t.writes.size(), 3, "next measurement starts after stop");
            eq(next.calls, 0, "no earlier reply completes it");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 65));
            eq(next.value.value, 65, "own reply");
            eq(a.calls, 1, "A reported once");
        });
        test("measurement give-up stops and releases", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler sch = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, sch, null, e -> { throw new AssertionError("no invalidation"); });
            Measurement m = new Measurement(new WatchClient(q), sch);
            Rec<Health.MeasurementUpdate> a = new Rec<>();
            m.start(1, a);
            q.onWriteComplete(true);
            sch.advance(Measurement.RESULT_MS + 1);
            ok(a.error instanceof Measurement.NoResultException, "honest no-result");
            ok(m.active(), "gate held until stop ack");
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x6a, 1));
            ok(!m.active(), "released");
        });
        test("REGRESSION rejected stop keeps measurement gate closed", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler sch = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, sch, null, e -> { throw new AssertionError("no invalidation"); });
            Measurement m = new Measurement(new WatchClient(q), sch);
            Rec<Health.MeasurementUpdate> a = new Rec<>();
            m.start(1, a);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x69, 1, 0, 70));
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("eaee00000000000000000000000000d8"));          // stop rejected, valid checksum
            ok(!q.closed(), "queue still usable");
            ok(m.active(), "gate stays closed");
            Rec<Health.MeasurementUpdate> b = new Rec<>();
            m.start(1, b);
            ok(b.error instanceof Measurement.StopUnconfirmedException, "refused with reason");
            eq(t.writes.size(), 2, "no new start written");
            q.onDisconnected();
            ok(!m.active(), "session reset releases it");
        });
        test("measurement gate released by closed session", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler sch = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, sch, null);
            Measurement m = new Measurement(new WatchClient(q), sch);
            Rec<Health.MeasurementUpdate> a = new Rec<>();
            m.start(1, a);
            q.onDisconnected();
            ok(a.error instanceof CommandQueue.DisconnectedException, "failed");
            ok(!m.active(), "released");
        });
        test("alarm read ignores write echo", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<Void> w = new Rec<>();
            c.bulkSend("alarm write", Alarms.writeRequest(new ArrayList<>()), w);
            q.onWriteComplete(true);
            ok(w.error == null && w.calls == 1, "write done");
            Rec<byte[]> r = new Rec<>();
            c.bulkWhere("alarm read", Alarms.readRequest(), 0x2c, p -> p.length > 0 && p[0] == 1, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.BULK, BulkProtocol.frame(0x2c, h("0200")));
            eq(r.calls, 0, "echo of the write ignored");
            q.onNotification(Channel.BULK, BulkProtocol.frame(0x2c, h("0100")));
            eq(Alarms.parse(r.value).size(), 0, "read reply");
        });
        test("transport counters count actual operations", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            c.request("battery", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, new Rec<>());
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("03260000000000000000000000000029"));
            c.bulk("faces", BulkProtocol.faceListRequest(), 0x35, 1000, new Rec<>());
            q.onWriteComplete(true);
            byte[] reply = BulkProtocol.frame(0x35, h("0201036162630002" + "6465"));
            for (byte[] p : BulkProtocol.fragments(reply, 20)) q.onNotification(Channel.BULK, p);
            CommandQueue.Stats st = q.stats();
            eq(st.shortWritesStarted, 1, "short write");
            eq(st.shortWritesConfirmed, 1, "short confirmed");
            eq(st.bulkFragmentsStarted, 1, "one bulk fragment");
            eq(st.shortNotifications, 1, "short rx");
            eq(st.bulkNotifications, BulkProtocol.fragments(reply, 20).size(), "bulk rx fragments");
            eq(st.operationsFailed, 0, "no failures");
            eq(c.reportedFragment(), -1, "no 2F yet");
            c.onPackageLength(ShortProtocol.packageLength(ShortProtocol.frame(0x2f, 0xf4)), 244);
            eq(c.reportedFragment(), 244, "2F recorded");
        });
        test("REGRESSION contact lists cannot interleave", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            ContactTransfer ct = new ContactTransfer(new WatchClient(q));
            List<Contacts.Contact> l1 = new ArrayList<>(), l2 = new ArrayList<>();
            l1.add(new Contacts.Contact("A", "1"));
            l2.add(new Contacts.Contact("B", "2"));
            Rec<Void> r1 = new Rec<>(), r2 = new Rec<>();
            ct.send(l1, 500, null, r1);
            ct.send(l2, 500, null, r2);
            ok(r2.error instanceof ContactTransfer.BusyException, "second list refused");
            q.onWriteComplete(true);
            q.onNotification(Channel.BULK, BulkProtocol.frame(0x29, new byte[]{0}));   // header accepted
            q.onWriteComplete(true);
            q.onNotification(Channel.BULK, BulkProtocol.frame(0x29, new byte[]{0}));   // group accepted
            ok(r1.error == null && r1.calls == 1, "first list complete");
            byte[] w0 = BulkProtocol.payload(t.writes.get(0), 0x29), w1 = BulkProtocol.payload(t.writes.get(1), 0x29);
            eq(w0[1] & 255, 0, "header first");
            eq(w1[1] & 255, 1, "then group 1 of the same list");
            ok(!ct.active(), "gate open again");
            ct.send(l2, 500, null, r2 = new Rec<>());
            eq(t.writes.size(), 3, "next list starts only now");
        });
        test("contact refusal stops the list", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            ContactTransfer ct = new ContactTransfer(new WatchClient(q));
            List<Contacts.Contact> l = new ArrayList<>();
            l.add(new Contacts.Contact("A", "1"));
            Rec<Void> r = new Rec<>();
            ct.send(l, 500, null, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.BULK, BulkProtocol.frame(0x29, new byte[]{1}));
            ok(r.error != null, "refused");
            eq(t.writes.size(), 1, "no further part");
            ok(!ct.active(), "gate released after a definite refusal");
        });
        test("package length report", () -> {
            eq(ShortProtocol.packageLength(ShortProtocol.frame(0x2f, 0xf4)), 244, "value");
            eq(ShortProtocol.packageLength(ShortProtocol.frame(0x2f, 5)), 20, "floor 20");
            WatchClient c = new WatchClient(new CommandQueue(new FakeTransport(), new FakeScheduler(), null));
            eq(c.bulkChunk(), 20, "session starts at 20");
            c.setBulkChunk(255, 20);
            eq(c.bulkChunk(), 20, "MTU 23 caps a 255 report");
        });
        test("queue bound", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            q.setMaxPending(2);
            WatchClient c = new WatchClient(q);
            Rec<Integer> x = new Rec<>();
            c.request("1", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, new Rec<>());
            c.request("2", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, new Rec<>());
            c.request("3", ShortProtocol.batteryRequest(), -1, ShortProtocol::battery, x);
            ok(x.error instanceof IllegalStateException, "bounded");
        });
        test("multi chunk notification", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<Void> r = new Rec<>();
            new WatchClient(q).notify("0123456789abcdefghijklmnopqrstuvwxyz", 1, r);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, h("72000000000000000000000000000072"));
            eq(r.calls, 0, "waits for remaining chunks");
            q.onWriteComplete(true);
            q.onWriteComplete(true);
            q.onWriteComplete(true);
            eq(r.calls, 1, "done");
            ok(r.error == null, "ok");
            eq(t.writes.size(), 4, "four chunks");
        });
        test("history multi-frame", () -> {
            FakeTransport t = new FakeTransport();
            FakeScheduler s = new FakeScheduler();
            CommandQueue q = new CommandQueue(t, s, null);
            Rec<Health.HeartRateDayReader> r = new Rec<>();
            new WatchClient(q).history("hr", Health.readHeartRateDay(0), new Health.HeartRateDayReader(), r);
            q.onWriteComplete(true);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x15, 0, 2, 5));
            s.advance(WatchClient.HISTORY_TIMEOUT_MS - 10);
            q.onNotification(Channel.SHORT, ShortProtocol.frame(0x15, 1, 0, 0, 0, 0, 66));
            ok(r.value != null && r.value.samples()[0] == 66, "complete");
        });
        test("bulk reply reassembly", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            WatchClient c = new WatchClient(q);
            Rec<byte[]> r = new Rec<>();
            c.bulk("faces", BulkProtocol.faceListRequest(), 0x35, 1000, r);
            q.onWriteComplete(true);
            byte[] reply = BulkProtocol.frame(0x35, h("0201036162630002" + "6465"));
            for (byte[] p : BulkProtocol.fragments(reply, 20)) q.onNotification(Channel.BULK, p);
            eq(BulkProtocol.faceList(BulkProtocol.payload(r.value, 0x35)).size(), 2, "parsed");
        });
        test("bulk corrupt reply fails", () -> {
            FakeTransport t = new FakeTransport();
            CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
            Rec<byte[]> r = new Rec<>();
            new WatchClient(q).bulk("faces", BulkProtocol.faceListRequest(), 0x35, 1000, r);
            q.onWriteComplete(true);
            byte[] reply = BulkProtocol.frame(0x35, h("00"));
            reply[6] = 1;
            q.onNotification(Channel.BULK, reply);
            ok(r.error instanceof ProtocolException, "crc");
        });
        test("bulk chunk size", () -> {
            WatchClient c = new WatchClient(new CommandQueue(new FakeTransport(), new FakeScheduler(), null));
            c.setBulkChunk(0, 20);
            eq(c.bulkChunk(), 20, "min");
            c.setBulkChunk(244, 182);
            eq(c.bulkChunk(), 182, "att cap");
            c.setBulkChunk(100, 244);
            eq(c.bulkChunk(), 100, "announced");
        });
    }

    /** Fake watch that acknowledges each bulk step. */
    static final class FakeWatch {
        final FakeTransport t = new FakeTransport();
        final CommandQueue q = new CommandQueue(t, new FakeScheduler(), null);
        final WatchClient c = new WatchClient(q);
        final BulkProtocol.Assembler incoming = new BulkProtocol.Assembler();
        int initStatus, finishStatus;
        boolean emptyFinish;
        int failAtBlock = -1;
        final List<Integer> opcodes = new ArrayList<>();
        final List<byte[]> blocks = new ArrayList<>();

        void run(int maxSteps) {
            for (int i = 0; i < maxSteps && !q.idle(); i++) {
                if (t.writes.isEmpty()) return;
                byte[] w = t.writes.remove(0);
                List<byte[]> frames = incoming.accept(w);
                q.onWriteComplete(true);
                for (byte[] f : frames) {
                    int op = BulkProtocol.command(f);
                    opcodes.add(op);
                    byte[] p = BulkProtocol.payload(f, op);
                    byte[] reply;
                    if (op == 0x36 || op == 0x31) reply = BulkProtocol.frame(op, new byte[]{(byte) initStatus});
                    else if (op == 0x38 || op == 0x33) reply = BulkProtocol.frame(op, emptyFinish ? new byte[0] : new byte[]{(byte) finishStatus});
                    else {
                        int index = (p[0] & 255) | (p[1] & 255) << 8;
                        blocks.add(Arrays.copyOfRange(p, 2, p.length));
                        if (index == failAtBlock) {
                            q.onDisconnected();
                            return;
                        }
                        reply = BulkProtocol.frame(op, new byte[]{p[0], p[1]});
                    }
                    q.onNotification(Channel.BULK, reply);
                }
            }
        }
    }

    static void transfer() {
        test("vendor face upload sequence", () -> {
            FakeWatch w = new FakeWatch();
            byte[] file = new byte[2500];
            new Random(7).nextBytes(file);
            Rec<Void> r = new Rec<>();
            List<Integer> progress = new ArrayList<>();
            ResourceTransfer tr = new ResourceTransfer(w.c, ResourceTransfer.Kind.VENDOR_FACE, "000054b014c114cd.di", file,
                    (s, n) -> progress.add(s), r);
            eq(tr.totalBlocks(), 3, "blocks");
            tr.start();
            w.run(10000);
            ok(r.calls == 1 && r.error == null, "success " + r.error);
            eq(w.opcodes, Arrays.asList(0x36, 0x37, 0x37, 0x37, 0x38), "sequence");
            eq(progress, Arrays.asList(1, 2, 3), "progress");
            eq(w.blocks.get(2).length, LiteralLzo.encode(new byte[2500 - 2048]).length, "last block");
        });
        test("init refusal stops", () -> {
            FakeWatch w = new FakeWatch();
            w.initStatus = 1;
            Rec<Void> r = new Rec<>();
            new ResourceTransfer(w.c, ResourceTransfer.Kind.VENDOR_FACE, "x.di", new byte[10], null, r).start();
            w.run(100);
            ok(r.error != null, "error");
            eq(w.opcodes, Collections.singletonList(0x36), "no data sent");
        });
        test("finish error and empty finish fail", () -> {
            FakeWatch w = new FakeWatch();
            w.finishStatus = 2;
            Rec<Void> r = new Rec<>();
            new ResourceTransfer(w.c, ResourceTransfer.Kind.VENDOR_FACE, "x.di", new byte[10], null, r).start();
            w.run(100);
            ok(r.error != null, "status 2");
            FakeWatch e = new FakeWatch();
            e.emptyFinish = true;
            Rec<Void> r2 = new Rec<>();
            new ResourceTransfer(e.c, ResourceTransfer.Kind.VENDOR_FACE, "x.di", new byte[10], null, r2).start();
            e.run(100);
            ok(r2.error != null, "empty status");
        });
        test("link loss mid transfer does not replay", () -> {
            FakeWatch w = new FakeWatch();
            w.failAtBlock = 2;
            Rec<Void> r = new Rec<>();
            new ResourceTransfer(w.c, ResourceTransfer.Kind.WALLPAPER, BulkProtocol.WALLPAPER_NAME, new byte[4000], null, r).start();
            w.run(1000);
            ok(r.error instanceof CommandQueue.DisconnectedException, "disconnected");
            eq(w.opcodes, Arrays.asList(0x31, 0x32, 0x32), "stopped at block 2");
        });
        test("stop cancels", () -> {
            FakeWatch w = new FakeWatch();
            Rec<Void> r = new Rec<>();
            ResourceTransfer tr = new ResourceTransfer(w.c, ResourceTransfer.Kind.VENDOR_FACE, "x.di", new byte[5000], null, r);
            tr.start();
            tr.stop();
            w.run(1000);
            ok(r.error instanceof CommandQueue.CancelledException, "cancelled");
            ok(!w.opcodes.contains(0x37), "no blocks");
        });
        test("wallpaper name fixed", () -> throwsP(() -> new ResourceTransfer(new FakeWatch().c, ResourceTransfer.Kind.WALLPAPER,
                "face.ui", new byte[4], null, new Rec<>()), "name"));
    }

    /** Vectors for the independent Node lzo1x decoder: one hex line per case, input then encoded. */
    static void writeLzoVectors(String path) throws IOException {
        Random random = new Random(71);
        StringBuilder out = new StringBuilder();
        for (int n = 1; n <= 1024; n++) {
            for (int pattern = 0; pattern < 3; pattern++) {
                byte[] in = new byte[n];
                if (pattern == 1) Arrays.fill(in, (byte) 0xff);
                if (pattern == 2) random.nextBytes(in);
                out.append(Hex.encode(in)).append(' ').append(Hex.encode(LiteralLzo.encode(in))).append('\n');
            }
        }
        // Real resource shapes: a wallpaper block and a block of the face-file header layout.
        int[] px = new int[240 * 284];
        for (int i = 0; i < px.length; i++) px[i] = 0xff000000 | (i * 2654435761L > 0 ? (int) (i * 2654435761L) & 0xffffff : 0);
        byte[] wp = BulkProtocol.rgb565Wallpaper(240, 284, px);
        for (int b = 0; b < wp.length; b += 1024 * 37) {
            byte[] in = Arrays.copyOfRange(wp, b, Math.min(wp.length, b + 1024));
            out.append(Hex.encode(in)).append(' ').append(Hex.encode(LiteralLzo.encode(in))).append('\n');
        }
        try (FileOutputStream f = new FileOutputStream(path)) {
            f.write(out.toString().getBytes(StandardCharsets.US_ASCII));
        }
    }

    // ---- Face picker: preview identity, stale picks, preview loads, operation ownership, Orbit asset ----

    /** A 35 payload in watch order from [deleteFlag, name] pairs, parsed by the production parser. */
    static List<BulkProtocol.InstalledFace> installed(Object... flagName) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(flagName.length / 2);
        for (int i = 0; i < flagName.length; i += 2) {
            byte[] n = ((String) flagName[i + 1]).getBytes(StandardCharsets.UTF_8);
            b.write((Boolean) flagName[i] ? 1 : 0);
            b.write(n.length);
            b.write(n, 0, n.length);
        }
        return BulkProtocol.faceList(b.toByteArray());
    }

    static final String HW = "M71J_V1.0", FW = "M71J_1.00.27_231223";

    static FacePreviews.Entry entry(String name, String preview) { return new FacePreviews.Entry(name, preview, HW); }

    static void faces() {
        test("catalog file name is the URL basename", () -> {
            eq(FacePreviews.fileName("https://files.example.invalid/watchface/M71J_V1.0/0a1b2c3d4e5f6071.di"), "0a1b2c3d4e5f6071.di", "basename");
            eq(FacePreviews.fileName("https://files.example.invalid/watchface/"), null, "no basename");
            eq(FacePreviews.fileName(null), null, "null");
        });
        test("installed names map to their own exact catalog image, not catalog position", () -> {
            List<FacePreviews.Entry> cat = Arrays.asList(entry("aaaa.di", "https://p.invalid/a.png"),
                    entry("bbbb.di", "https://p.invalid/b.png"), entry("cccc.di", "https://p.invalid/c.png"));
            FacePreviews.Index ix = new FacePreviews.Index(cat, HW);
            // Watch order differs from catalog order; each row resolves by its own name.
            List<BulkProtocol.InstalledFace> watch = installed(false, "cccc.di", true, "aaaa.di", false, "bbbb.di");
            String[] want = {"https://p.invalid/c.png", "https://p.invalid/a.png", "https://p.invalid/b.png"};
            for (int i = 0; i < watch.size(); i++) {
                FacePreviews.Match m = ix.find(watch.get(i).name, FW);
                eq(m.status, FacePreviews.Status.MATCH, "row " + i);
                eq(m.previewUrl, want[i], "row " + i + " image");
            }
        });
        test("no substring, case, whitespace or extension-less matches", () -> {
            FacePreviews.Index ix = new FacePreviews.Index(Arrays.asList(entry("aaaa.di", "https://p.invalid/a.png")), HW);
            for (String n : new String[]{"AAAA.di", "aaaa.DI", "aaaa", "xaaaa.di", "aaaa.di.bak", " aaaa.di", "aaaa.di ", "aa"}) {
                FacePreviews.Match m = ix.find(n, FW);
                eq(m.status, FacePreviews.Status.NO_MATCH, n);
                eq(m.previewUrl, null, n + " has no image");
            }
        });
        test("unknown installed names stay unavailable", () -> {
            FacePreviews.Index ix = new FacePreviews.Index(Arrays.asList(entry("aaaa.di", "https://p.invalid/a.png")), HW);
            eq(ix.find("time2_bg_img.ui", FW).status, FacePreviews.Status.NO_MATCH, "photo background name");
            eq(ix.find("zzzz.di", FW).previewUrl, null, "unknown");
            eq(ix.find("", FW).status, FacePreviews.Status.UNNAMED, "empty");
            eq(ix.find(null, FW).status, FacePreviews.Status.UNNAMED, "null");
            eq(new FacePreviews.Index(null, HW).find("aaaa.di", FW).status, FacePreviews.Status.NO_MATCH, "no catalog");
        });
        test("other hardware entries are never used", () -> {
            List<FacePreviews.Entry> cat = Arrays.asList(new FacePreviews.Entry("aaaa.di", "https://p.invalid/other.png", "M72X_V2.0"),
                    entry("bbbb.di", "https://p.invalid/b.png"), new FacePreviews.Entry("bbbb.di", "https://p.invalid/b2.png", "M72X_V2.0"));
            FacePreviews.Index ix = new FacePreviews.Index(cat, HW);
            eq(ix.find("aaaa.di", FW).status, FacePreviews.Status.NO_MATCH, "only other hardware has it");
            eq(ix.find("bbbb.di", FW).previewUrl, "https://p.invalid/b.png", "same hardware wins, other ignored");
            eq(ix.find("bbbb.di", "M72X_2.00.1").status, FacePreviews.Status.OTHER_HARDWARE, "watch reports another model");
            eq(ix.find("bbbb.di", null).status, FacePreviews.Status.MATCH, "unread firmware: exact-name binding applies");
            eq(ix.find("bbbb.di", "garbage").status, FacePreviews.Status.OTHER_HARDWARE, "unparseable firmware");
        });
        test("duplicate names with different images show none, in any order", () -> {
            FacePreviews.Entry a1 = entry("dup.di", "https://p.invalid/1.png"), a2 = entry("dup.di", "https://p.invalid/1.png"),
                    b = entry("dup.di", "https://p.invalid/2.png"), n = entry("dup.di", null);
            List<List<FacePreviews.Entry>> orders = Arrays.asList(Arrays.asList(a1, a2, b), Arrays.asList(b, a1, a2),
                    Arrays.asList(a1, b, a2), Arrays.asList(a1, n), Arrays.asList(n, a1));
            for (List<FacePreviews.Entry> o : orders) {
                FacePreviews.Match m = new FacePreviews.Index(o, HW).find("dup.di", FW);
                eq(m.status, FacePreviews.Status.AMBIGUOUS, "order " + orders.indexOf(o));
                eq(m.previewUrl, null, "no image for ambiguous");
            }
            FacePreviews.Match same = new FacePreviews.Index(Arrays.asList(a1, a2), HW).find("dup.di", FW);
            eq(same.status, FacePreviews.Status.MATCH, "duplicates with one identical image are one image");
            eq(same.previewUrl, "https://p.invalid/1.png", "that image");
        });
        test("an exact entry without an allowed preview has no image", () -> {
            FacePreviews.Match m = new FacePreviews.Index(Arrays.asList(entry("aaaa.di", null)), HW).find("aaaa.di", FW);
            eq(m.status, FacePreviews.Status.NO_IMAGE, "status");
            eq(m.previewUrl, null, "url");
        });

        test("a face pick resolves only against the same names in the same watch order", () -> {
            List<BulkProtocol.InstalledFace> read1 = installed(false, "aaaa.di", true, "bbbb.di", false, "cccc.di");
            FaceChoice pick = FaceChoice.of(read1, 1);
            eq(pick.row, 1, "row");
            eq(pick.name, "bbbb.di", "name");
            eq(pick.resolve(read1), 1, "same reading");
            // A fresh reading with the same order resolves even when delete flags differ: flags are not identity.
            eq(pick.resolve(installed(true, "aaaa.di", false, "bbbb.di", true, "cccc.di")), 1, "re-read, same order");
            eq(pick.resolve(installed(false, "bbbb.di", false, "aaaa.di", false, "cccc.di")), -1, "reordered");
            eq(pick.resolve(installed(false, "aaaa.di", false, "bbbb.di", false, "cccc.di", false, "dddd.di")), -1, "face added");
            eq(pick.resolve(installed(false, "aaaa.di", false, "cccc.di")), -1, "face removed");
            eq(pick.resolve(installed(false, "aaaa.di", false, "xxxx.di", false, "cccc.di")), -1, "row now another face");
            eq(pick.resolve(null), -1, "list cleared by disconnect");
            ok(FaceChoice.of(read1, 3) == null && FaceChoice.of(read1, -1) == null && FaceChoice.of(null, 0) == null, "out of range");
        });
        test("duplicate installed names keep their own rows", () -> {
            List<BulkProtocol.InstalledFace> l = installed(false, "same.di", false, "same.di");
            eq(FaceChoice.of(l, 1).resolve(installed(false, "same.di", false, "same.di")), 1, "second row stays second");
        });

        test("preview fetches coalesce and failures stay failed until an explicit retry", () -> {
            PreviewLoads p = new PreviewLoads();
            int fetches = 0;
            String[] urls = new String[26];
            for (int i = 0; i < urls.length; i++) urls[i] = "https://p.invalid/" + i + ".png";
            for (int render = 0; render < 100; render++) for (String u : urls) if (p.start(u)) fetches++;
            eq(fetches, 26, "one fetch per image across 100 re-renders");
            for (String u : urls) p.finish(u, false);
            for (int render = 0; render < 100; render++) for (String u : urls) if (p.start(u)) fetches++;
            eq(fetches, 26, "no automatic retry after failure");
            eq(p.state(urls[0]), PreviewLoads.State.FAILED, "failed state is stable");
            ok(p.anyFailed(), "retry offered");
            eq(p.retryFailed(), 26, "explicit retry resets failures");
            for (String u : urls) if (p.start(u)) fetches++;
            eq(fetches, 52, "one new fetch each after retry");
            ok(!p.anyFailed(), "nothing failed while loading");
        });
        test("preview results: late, duplicate and eviction handling", () -> {
            PreviewLoads p = new PreviewLoads();
            p.finish("https://p.invalid/x.png", true);
            eq(p.state("https://p.invalid/x.png"), PreviewLoads.State.NONE, "result without a fetch is ignored");
            ok(p.start("https://p.invalid/a.png"), "start");
            p.evicted("https://p.invalid/a.png");
            ok(!p.start("https://p.invalid/a.png"), "eviction while loading does not start a second fetch");
            p.finish("https://p.invalid/a.png", true);
            p.finish("https://p.invalid/a.png", false);
            eq(p.state("https://p.invalid/a.png"), PreviewLoads.State.READY, "a late second result does not flip state");
            ok(!p.start("https://p.invalid/a.png"), "ready: no fetch");
            p.evicted("https://p.invalid/a.png");
            ok(p.start("https://p.invalid/a.png"), "after cache eviction, one fetch again");
            ok(!p.start(null), "no URL, no fetch");
        });
        test("preview decode is bounded", () -> {
            eq(PreviewLoads.sampleSize(240, 284, 300, 4096), 1, "catalog preview at native size");
            eq(PreviewLoads.sampleSize(480, 568, 300, 4096), 2, "double size halves");
            eq(PreviewLoads.sampleSize(4000, 3000, 300, 4096), 16, "large source subsampled");
            ok(4000 / PreviewLoads.sampleSize(4000, 3000, 300, 4096) <= 300, "within bound");
            eq(PreviewLoads.sampleSize(5000, 10, 300, 4096), -1, "oversized source refused");
            eq(PreviewLoads.sampleSize(0, 10, 300, 4096), -1, "invalid size");
            eq(PreviewLoads.sampleSize(-1, -1, 300, 4096), -1, "not an image");
        });

        test("face operations: one at a time, stale tickets cannot release newer ones", () -> {
            ExclusiveOp op = new ExclusiveOp();
            int t1 = op.begin();
            ok(t1 > 0 && op.active(), "first begins");
            eq(op.begin(), 0, "double submit refused");
            ok(op.end(t1) && !op.active(), "own ticket ends it");
            ok(!op.end(t1), "ending twice changes nothing");
            int t2 = op.begin();
            op.reset(); // Reconnect while t2 was in flight.
            ok(!op.active(), "session end releases");
            int t3 = op.begin();
            ok(t3 > 0 && t3 != t2, "new session operation");
            ok(!op.end(t2) && op.active() && !op.current(t2), "late result from abandoned operation is ignored");
            ok(op.current(t3) && op.end(t3), "current ticket ends it");
        });

        test("bundled Orbit is a native opaque 240x284 RGB565 photo background", () -> {
            java.io.File png = findUp("app/src/main/res/drawable-nodpi/face_orbit_aquryu.png");
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png);
            eq(img.getWidth(), 240, "width");
            eq(img.getHeight(), 284, "height");
            int[] px = img.getRGB(0, 0, 240, 284, null, 0, 240);
            int transparent = 0;
            long sum = 0;
            for (int v : px) {
                if ((v >>> 24) != 255) transparent++;
                sum += v & 0xffffff;
            }
            eq(transparent, 0, "every pixel opaque, no flattening changes the art");
            ok(sum != 0 && sum != (long) px.length * 0xffffff, "not blank");
            byte[] wire = BulkProtocol.rgb565Wallpaper(240, 284, px);
            eq(wire.length, 4 + 240 * 284 * 2, "wire size");
            hex(Arrays.copyOf(wire, 4), "f0001c01", "native width and height header");
        });
    }


    // ---- Face picker integration: pending Apply identity, transfer-owned face facts, bounded fetch lanes ----

    /** The manager's session identity: the gate epoch while a session is ready, otherwise 0. */
    static final class FakeLink implements PendingApply.Session {
        final SessionGate gate = new SessionGate();
        boolean ready;

        void connect() { gate.begin(); ready = true; }       // Every connect, even to the same watch, is a new epoch.

        void disconnect() { gate.invalidate(); ready = false; }

        @Override public int token() { return ready ? gate.token() : 0; }
    }

    /** The manager's face facts, forgotten by the transfer owner exactly as WatchManager wires it. */
    static final class Facts implements FaceTransferOwner.Facts {
        List<BulkProtocol.InstalledFace> faces;
        Integer index;
        boolean fresh;
        int forgets;

        @Override public void forget() { faces = null; index = null; fresh = false; forgets++; }
    }

    static void faceIntegration() {
        test("late catalog download after page exit or re-show starts no transfer", () -> {
            FakeLink link = new FakeLink();
            link.connect();
            PendingApply pending = new PendingApply(link);
            List<Runnable> downloads = new ArrayList<>();
            int[] transfers = {0}, stale = {0};
            PendingApply.Ticket t = pending.arm();
            ok(t != null && t.current(), "armed at Apply");
            downloads.add(() -> t.proceed(() -> transfers[0]++, () -> stale[0]++));
            pending.invalidate(); // Page hidden while the file downloads.
            pending.invalidate(); // Page shown again.
            for (Runnable r : downloads) r.run();
            eq(transfers[0], 0, "no transfer from a stale download");
            eq(stale[0], 1, "stale completion reported");
        });
        test("late catalog download after reconnect to the same watch starts no transfer", () -> {
            FakeLink link = new FakeLink();
            link.connect();
            PendingApply pending = new PendingApply(link);
            PendingApply.Ticket t = pending.arm();
            int before = t.session;
            link.disconnect();
            ok(!t.current(), "disconnected: stale");
            link.connect(); // Same device, new session.
            ok(link.token() != 0 && link.token() != before, "new session token");
            int[] transfers = {0};
            ok(!t.proceed(() -> transfers[0]++, () -> { }) && transfers[0] == 0, "refused before any transfer");
            PendingApply.Ticket fresh = pending.arm();
            ok(fresh.proceed(() -> transfers[0]++, () -> { }) && transfers[0] == 1, "a new Apply in the new session proceeds");
            eq(fresh.session, link.token(), "ticket carries the session the manager re-checks");
            link.ready = false;
            ok(new PendingApply(link).arm() == null, "no ready session, no Apply");
        });

        test("any transfer makes the face list, order and current index unknown until fresh 35 and 75", () -> {
            Facts f = new Facts();
            f.faces = installed(false, "aaaa.di", true, "bbbb.di", false, "cccc.di");
            f.index = 2;
            f.fresh = true;
            FaceChoice pick = FaceChoice.of(f.faces, 2);
            FaceTransferOwner owner = new FaceTransferOwner(f);
            int t = owner.begin("the Orbit background", 34);
            ok(t > 0, "transfer begins");
            ok(f.faces == null && f.index == null && !f.fresh, "facts forgotten before any block is sent");
            eq(BulkProtocol.currentRow(f.faces, f.index), -1, "no current badge during the transfer");
            eq(pick.resolve(f.faces), -1, "no installed-row selection during the transfer");
            eq(owner.begin("your photo", 34), 0, "second transfer refused");
            ok(owner.finish(t, null), "accepted: one refresh requested");
            ok(f.faces == null && f.index == null, "still unknown after acceptance");
            ok(!owner.finish(t, null), "refresh requested only once");
            // Fresh 35: a list without an index is not current yet; fresh 75 then marks it.
            f.faces = installed(false, "aaaa.di", true, "bbbb.di", false, "cccc.di", false, "dddd.di");
            eq(BulkProtocol.currentRow(f.faces, f.index), -1, "fresh 35 alone: current unknown");
            eq(pick.resolve(f.faces), -1, "old pick does not address the changed list");
            f.index = 4;
            eq(BulkProtocol.currentRow(f.faces, f.index), 3, "fresh 75 marks the current row");
        });
        test("partial transfer failure and a hidden page leave no old facts current", () -> {
            Facts f = new Facts();
            FaceTransferOwner owner = new FaceTransferOwner(f);
            f.faces = installed(false, "aaaa.di");
            f.index = 1;
            int t = owner.begin("Face 12", 40);
            ok(owner.progress(t, 17, 40) && owner.sent == 17, "progress recorded by the owner");
            f.faces = installed(false, "aaaa.di"); // A screen must not be able to resurrect the old list...
            f.index = 1;
            ok(owner.finish(t, "Stopped."), "failure still requests one refresh");
            ok(f.faces == null && f.index == null, "...the end of the transfer forgets it again");
            eq(owner.lastLabel, "Face 12", "outcome kept for other screens");
            eq(owner.lastError, "Stopped.", "failure recorded without the initiating page");
        });
        test("reconnect during a transfer: outcome unknown, late completion ignored", () -> {
            Facts f = new Facts();
            FaceTransferOwner owner = new FaceTransferOwner(f);
            int t = owner.begin("your photo", 34);
            owner.reset("The link ended during the transfer.");
            ok(!owner.active(), "released");
            eq(owner.lastError, "The link ended during the transfer.", "recorded as unknown");
            ok(!owner.progress(t, 5, 34) && owner.sent == 0, "late progress ignored");
            ok(!owner.finish(t, null), "late success neither refreshes nor claims acceptance");
            eq(owner.lastError, "The link ended during the transfer.", "outcome not overwritten");
            int t2 = owner.begin("the Orbit background", 34);
            ok(t2 > 0 && !owner.finish(t, null) && owner.active(), "old ticket cannot end a new transfer");
        });

        test("preview saturation is bounded and an explicit download still progresses", () -> {
            FetchLanes lanes = new FetchLanes(2, 6, 2, 4);
            PreviewLoads loads = new PreviewLoads(lanes.previewCapacity());
            java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
            Runnable blocked = () -> {
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ran.incrementAndGet();
            };
            int started = 0;
            // Three catalog pages of 20, each re-rendered 10 times, through FaceCatalog.preview's admission order.
            for (int pg = 0; pg < 3; pg++) {
                for (int render = 0; render < 10; render++) {
                    for (int i = 0; i < 20; i++) {
                        String u = "https://p.invalid/" + (pg * 20 + i) + ".png";
                        if (loads.start(u)) {
                            started++;
                            if (!lanes.preview(u, blocked)) loads.cancel(u);
                        }
                    }
                }
            }
            eq(started, 8, "outstanding previews capped at the lane capacity");
            eq(loads.loading(), 8, "accounting matches");
            ok(!lanes.preview("extra", blocked), "a full preview lane refuses instead of growing");
            java.util.concurrent.CountDownLatch downloaded = new java.util.concurrent.CountDownLatch(1);
            ok(lanes.control(downloaded::countDown), "explicit download accepted while previews are saturated");
            ok(downloaded.await(5, java.util.concurrent.TimeUnit.SECONDS), "download ran without waiting for previews");
            // Page change: queued previews of the old page are dropped and become requestable again.
            List<String> dropped = lanes.dropQueuedPreviews();
            eq(dropped.size(), 6, "queued previews dropped, running ones kept");
            for (String u : dropped) loads.cancel(u);
            eq(loads.loading(), 2, "only running fetches remain outstanding");
            eq(loads.state(dropped.get(0)), PreviewLoads.State.NONE, "dropped image waits for its page");
            int admitted = 0;
            for (int i = 0; i < 20; i++) {
                String u = "https://p.invalid/new" + i + ".png";
                if (loads.start(u) && lanes.preview(u, blocked)) admitted++;
            }
            eq(admitted, 6, "the visible page gets the freed slots promptly");
            hold.countDown();
        });
        test("catalog control lane refuses when full instead of queueing without bound", () -> {
            FetchLanes lanes = new FetchLanes(2, 6, 2, 4);
            java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
            Runnable blocked = () -> {
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            };
            int accepted = 0;
            for (int i = 0; i < 10; i++) if (lanes.control(blocked)) accepted++;
            eq(accepted, 6, "two running plus four queued");
            hold.countDown();
        });
        test("bounded previews: failures stay failed until explicit retry", () -> {
            PreviewLoads loads = new PreviewLoads(2);
            ok(loads.start("a") && loads.start("b") && !loads.start("c"), "cap of two");
            loads.finish("a", false);
            ok(loads.start("c"), "slot freed by a finished fetch");
            for (int render = 0; render < 50; render++) ok(!loads.start("a"), "failed image not refetched by renders");
            loads.finish("b", true);
            loads.finish("c", true);
            eq(loads.retryFailed(), 1, "explicit retry");
            ok(loads.start("a"), "retried once");
            loads.cancel("a");
            loads.cancel("a");
            eq(loads.loading(), 0, "double cancel does not underflow");
        });
    }

    /** A repository file located from the compiled test classes, so the check never skips silently. */
    static java.io.File findUp(String relative) throws IOException {
        java.io.File dir;
        try {
            dir = new java.io.File(ProtocolTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            java.io.File f = new java.io.File(dir, relative);
            if (f.isFile()) return f;
        }
        throw new IOException("missing " + relative);
    }

}
