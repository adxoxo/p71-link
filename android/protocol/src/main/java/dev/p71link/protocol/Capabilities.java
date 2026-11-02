package dev.p71link.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capability report carried by the clock reply (opcode 01).
 * Flags describe advertised companion features. They do not prove sensor hardware or accuracy.
 */
public final class Capabilities {
    public final boolean temperature, watchFaces, menstruation, customWallpaper, bloodOxygen, bloodPressure,
            genericFeature, oneKeyCheck, weather, wechat, contacts, lyrics, album, gps, jieliMusic, appMeasurement,
            manualBloodOxygen, yawei, manualHeartRate, ecard, location, music, rtkMcu, ebook, bloodGlucose,
            recording, newSleepProtocol;
    public final int displayWidth, displayHeight, maximumWatchFaces, maximumContacts;
    private final byte[] raw;

    private Capabilities(ShortProtocol.Reply r) {
        raw = r.bytes();
        temperature = r.payload(0) == 1;
        watchFaces = r.payload(1) == 1;
        menstruation = r.payload(2) == 1;
        int b3 = r.payload(3);
        customWallpaper = bit(b3, 0);
        bloodOxygen = bit(b3, 1);
        bloodPressure = bit(b3, 2);
        genericFeature = bit(b3, 3);
        oneKeyCheck = bit(b3, 4);
        weather = bit(b3, 5);
        wechat = !bit(b3, 6); // The vendor parser treats this bit as inverted.
        displayWidth = r.le16(4);
        displayHeight = r.le16(6);
        newSleepProtocol = r.payload(8) == 1;
        int faces = r.signed(9);
        maximumWatchFaces = faces <= 0 ? 6 : faces;
        int b10 = r.payload(10);
        contacts = bit(b10, 0);
        lyrics = bit(b10, 1);
        album = bit(b10, 2);
        gps = bit(b10, 3);
        jieliMusic = bit(b10, 4);
        appMeasurement = bit(b10, 5);
        manualBloodOxygen = bit(b10, 6);
        yawei = bit(b10, 7);
        int b11 = r.payload(11);
        manualHeartRate = bit(b11, 0);
        ecard = bit(b11, 1);
        location = bit(b11, 2);
        music = bit(b11, 4);
        rtkMcu = bit(b11, 5);
        ebook = bit(b11, 6);
        bloodGlucose = bit(b11, 7);
        int c = r.signed(12);
        maximumContacts = c == 0 ? 20 : c * 10;
        recording = bit(r.payload(13), 0);
    }

    public static Capabilities parse(byte[] frame) {
        return new Capabilities(ShortProtocol.reply(frame, ShortProtocol.CLOCK));
    }

    public byte[] raw() { return raw.clone(); }

    /** Ordered field map, using the same names as capabilities.json. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("temperature", temperature);
        m.put("watch_faces", watchFaces);
        m.put("menstruation", menstruation);
        m.put("custom_wallpaper", customWallpaper);
        m.put("blood_oxygen", bloodOxygen);
        m.put("blood_pressure", bloodPressure);
        m.put("generic_feature", genericFeature);
        m.put("one_key_check", oneKeyCheck);
        m.put("weather", weather);
        m.put("wechat", wechat);
        m.put("display_width", displayWidth);
        m.put("display_height", displayHeight);
        m.put("new_sleep_protocol", newSleepProtocol);
        m.put("maximum_watch_faces", maximumWatchFaces);
        m.put("contacts", contacts);
        m.put("lyrics", lyrics);
        m.put("album", album);
        m.put("gps", gps);
        m.put("jieli_music", jieliMusic);
        m.put("app_measurement", appMeasurement);
        m.put("manual_blood_oxygen", manualBloodOxygen);
        m.put("yawei", yawei);
        m.put("manual_heart_rate", manualHeartRate);
        m.put("ecard", ecard);
        m.put("location", location);
        m.put("music", music);
        m.put("rtk_mcu", rtkMcu);
        m.put("ebook", ebook);
        m.put("blood_glucose", bloodGlucose);
        m.put("maximum_contacts", maximumContacts);
        m.put("recording", recording);
        return m;
    }

    private static boolean bit(int value, int n) { return (value >> n & 1) != 0; }
}
