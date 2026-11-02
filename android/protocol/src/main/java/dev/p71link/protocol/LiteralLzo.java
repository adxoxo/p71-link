package dev.p71link.protocol;

import java.io.ByteArrayOutputStream;

/** Original LZO1X literal-stream encoder. Valid streams, with no compression claim or native dependency. */
public final class LiteralLzo {
    private LiteralLzo() {}
    public static byte[] encode(byte[] input) {
        if (input == null || input.length > 1024) throw new IllegalArgumentException("Expected a resource block of at most 1024 bytes");
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length + 12);
        int length = input.length;
        if (length > 0 && length <= 238) {
            out.write(length + 17);
        } else if (length > 238) {
            out.write(0); int extra = length - 18;
            while (extra > 255) { out.write(0); extra -= 255; }
            out.write(extra);
        }
        out.write(input,0,length); out.write(17); out.write(0); out.write(0);
        return out.toByteArray();
    }
}
