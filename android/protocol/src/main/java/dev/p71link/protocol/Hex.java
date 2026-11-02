package dev.p71link.protocol;

/** Hexadecimal helpers for fixtures and diagnostics. Product screens never show these strings. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] data) {
        if (data == null) return "";
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = DIGITS[(data[i] >> 4) & 15];
            out[i * 2 + 1] = DIGITS[data[i] & 15];
        }
        return new String(out);
    }

    public static byte[] decode(String text) {
        String clean = text.replaceAll("\\s", "");
        if ((clean.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex digit");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}
