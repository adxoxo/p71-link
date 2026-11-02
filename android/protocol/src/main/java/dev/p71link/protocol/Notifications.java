package dev.p71link.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text push (72): [type, chunkCount, oneBasedIndex, 11 UTF-8 bytes], last chunk zero padded.
 * A chunk acknowledgment proves receipt of that frame only, not a visible message.
 */
public final class Notifications {
    public static final int MAX_UTF16_UNITS = 256;
    public static final int CHUNK_BYTES = 11;

    /** 0 ringing; 4 call answered or ended (vendor call path). */
    public static final int CALL_ENDED = 4;
    public static final int CALL = 0, SMS = 1, QQ = 2, WECHAT = 3, FACEBOOK = 5, WHATSAPP = 6, TWITTER = 7,
            SKYPE = 8, LINE = 9, LINKEDIN = 10, INSTAGRAM = 11, TIM = 12, SNAPCHAT = 13, OTHER = 14,
            MESSENGER = 17, ZALO = 18, KAKAO = 19, TELEGRAM = 20, VIBER = 21;

    /** Package prefixes used by the vendor app, mapped to watch icon types. Only SMS is target-confirmed. */
    private static final Map<String, Integer> PACKAGES = new LinkedHashMap<>();

    static {
        PACKAGES.put("com.facebook.orca", MESSENGER);
        PACKAGES.put("com.tencent.mm", WECHAT);
        PACKAGES.put("com.tencent.mobileqq", QQ);
        PACKAGES.put("com.tencent.tim", TIM);
        PACKAGES.put("com.twitter", TWITTER);
        PACKAGES.put("com.facebook", FACEBOOK);
        PACKAGES.put("com.whatsapp", WHATSAPP);
        PACKAGES.put("com.skype", SKYPE);
        PACKAGES.put("jp.naver.line", LINE);
        PACKAGES.put("com.linkedin.android", LINKEDIN);
        PACKAGES.put("com.instagram.android", INSTAGRAM);
        PACKAGES.put("com.snapchat.android", SNAPCHAT);
        PACKAGES.put("com.viber", VIBER);
        PACKAGES.put("org.telegram", TELEGRAM);
        PACKAGES.put("com.kakao.talk", KAKAO);
        PACKAGES.put("com.zing.zalo", ZALO);
        PACKAGES.put("com.google.android.apps.messaging", SMS);
        PACKAGES.put("com.samsung.android.messaging", SMS);
        PACKAGES.put("com.android.mms", SMS);
    }

    private Notifications() {}

    public static int typeForPackage(String pkg) {
        if (pkg == null) return OTHER;
        for (Map.Entry<String, Integer> e : PACKAGES.entrySet()) {
            if (pkg.equals(e.getKey()) || pkg.startsWith(e.getKey() + ".")) return e.getValue();
        }
        return OTHER;
    }

    /** Truncate to 256 UTF-16 units without splitting a surrogate pair; replace lone surrogates. */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder(Math.min(text.length(), MAX_UTF16_UNITS));
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    if (b.length() + 2 > MAX_UTF16_UNITS) break;
                    b.append(c).append(text.charAt(++i));
                    continue;
                }
                c = '�';
            } else if (Character.isLowSurrogate(c)) {
                c = '�';
            } else if (c == '\0') {
                c = ' ';
            }
            if (b.length() + 1 > MAX_UTF16_UNITS) break;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Fragment the normalized text. A chunk may end inside a UTF-8 sequence; the watch
     * reassembles the byte stream before decoding, as the vendor sender does.
     */
    public static List<byte[]> frames(String text, int type) {
        ShortProtocol.range(type, 0, 255, "notification type");
        String clean = normalize(text);
        if (clean.trim().isEmpty()) throw new IllegalArgumentException("Empty notification");
        byte[] encoded = clean.getBytes(StandardCharsets.UTF_8);
        int count = (encoded.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (count > 255) throw new IllegalArgumentException("Notification too long");
        List<byte[]> packets = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            int[] body = new int[14];
            body[0] = type;
            body[1] = count;
            body[2] = n + 1;
            for (int i = 0; i < CHUNK_BYTES && n * CHUNK_BYTES + i < encoded.length; i++) {
                body[i + 3] = encoded[n * CHUNK_BYTES + i] & 255;
            }
            packets.add(ShortProtocol.frame(ShortProtocol.NOTIFY_TEXT, body));
        }
        return Collections.unmodifiableList(packets);
    }

    /** One zero-padded chunk with no text: a call event when the notification gave no caller text. */
    public static byte[] controlFrame(int type) {
        ShortProtocol.range(type, 0, 255, "notification type");
        return ShortProtocol.frame(ShortProtocol.NOTIFY_TEXT, type, 1, 1);
    }

    /** Frames for a call event: the caller text when present, otherwise one padded control chunk. */
    public static List<byte[]> callFrames(int type, String textOrNull) {
        if (textOrNull == null || textOrNull.trim().isEmpty()) return Collections.singletonList(controlFrame(type));
        return frames(textOrNull, type);
    }

    /** Title and body joined the way a watch line reads naturally. */
    public static String compose(String title, String body) {
        String t = title == null ? "" : title.trim();
        String m = body == null ? "" : body.trim();
        if (t.isEmpty()) return m;
        if (m.isEmpty() || m.equals(t)) return t;
        return t + ": " + m;
    }

    /** Captured acknowledgment `72000000000000000000000000000072` carries a zero payload. */
    public static boolean chunkAcknowledged(byte[] reply) {
        ShortProtocol.Reply r = ShortProtocol.reply(reply, ShortProtocol.NOTIFY_TEXT);
        return r.payload(0) == 0;
    }
}
