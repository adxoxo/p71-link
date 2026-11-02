package dev.p71link.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contact list upload (bulk 29). The upload replaces the watch list. No read path exists, so the
 * previous watch list cannot be restored from the phone.
 *
 * Records: [nameLen, nameUTF8 (max 32 bytes), numberLen, number (max 18 bytes)].
 * Records are grouped; a group closes once it passes 950 bytes, and a final group is always added.
 * Payloads: header [N+1, 0, countLE16], then [N+1, groupIndex (1-based), groupBytes] per group.
 * Each reply needs status byte 0 before the next payload.
 */
public final class Contacts {
    public static final int NAME_BYTES = 32, NUMBER_BYTES = 18, GROUP_SOFT_LIMIT = 950;

    public static final class Contact {
        public final String name, number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name.trim();
            this.number = cleanNumber(number);
            if (this.name.isEmpty()) throw new IllegalArgumentException("Contact name is empty");
            if (this.number.isEmpty()) throw new IllegalArgumentException("Contact number is empty");
            // A dial target is never shortened: reject a number the watch record cannot hold.
            if (this.number.getBytes(StandardCharsets.UTF_8).length > NUMBER_BYTES) {
                throw new IllegalArgumentException("That phone number is longer than the watch allows (" + NUMBER_BYTES + " characters).");
            }
        }
    }

    private Contacts() {}

    /** The name exactly as it is sent: shortened on a character boundary to the watch limit. */
    public static String sentName(Contact c) { return new String(clip(c.name, NAME_BYTES), StandardCharsets.UTF_8); }

    /** Keep digits, a leading plus, star, and hash. */
    public static String cleanNumber(String n) {
        if (n == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '0' && c <= '9' || c == '*' || c == '#' || c == '+' && b.length() == 0) b.append(c);
        }
        return b.toString();
    }

    /** UTF-8 bytes of the longest prefix within max bytes that does not split a character. */
    public static byte[] clip(String s, int max) {
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= max) return all;
        int end = 0, i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (end + len > max) break;
            end += len;
            i += Character.charCount(cp);
        }
        byte[] out = new byte[end];
        System.arraycopy(all, 0, out, 0, end);
        return out;
    }

    public static List<byte[]> payloads(List<Contact> contacts, int maximum) {
        if (contacts.size() > maximum || contacts.size() > 0xffff) throw new IllegalArgumentException("Too many contacts for this watch");
        List<byte[]> groups = new ArrayList<>();
        ByteArrayOutputStream group = new ByteArrayOutputStream();
        for (Contact c : contacts) {
            byte[] name = clip(c.name, NAME_BYTES);
            byte[] number = c.number.getBytes(StandardCharsets.UTF_8); // Validated length; never clipped.
            group.write(name.length);
            group.write(name, 0, name.length);
            group.write(number.length);
            group.write(number, 0, number.length);
            if (group.size() > GROUP_SOFT_LIMIT) {
                groups.add(group.toByteArray());
                group.reset();
            }
        }
        groups.add(group.toByteArray());
        int total = groups.size() + 1;
        if (total > 255) throw new IllegalArgumentException("Too many contacts for this watch");
        List<byte[]> out = new ArrayList<>();
        out.add(new byte[]{(byte) total, 0, (byte) contacts.size(), (byte) (contacts.size() >> 8)});
        for (int i = 0; i < groups.size(); i++) {
            byte[] g = groups.get(i);
            byte[] p = new byte[g.length + 2];
            p[0] = (byte) total;
            p[1] = (byte) (i + 1);
            System.arraycopy(g, 0, p, 2, g.length);
            out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    /** Reply status: payload[0] must exist and be zero. */
    public static void requireOk(byte[] replyFrame) {
        byte[] p = BulkProtocol.payload(replyFrame, BulkProtocol.CONTACTS);
        if (p.length == 0) throw new ProtocolException("Contact reply has no status");
        if ((p[0] & 255) != 0) throw new ProtocolException("The watch refused the contact list");
    }
}
