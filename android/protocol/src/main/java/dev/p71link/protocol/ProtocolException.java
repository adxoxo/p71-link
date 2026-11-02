package dev.p71link.protocol;

/** A reply or request that fails a protocol rule. It never represents a successful watch result. */
public class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    /** The watch answered with the request opcode plus bit 7. */
    public static final class Rejected extends ProtocolException {
        private static final long serialVersionUID = 1L;
        public final int command;
        public final int errorCode;

        public Rejected(int command, int errorCode) {
            super(String.format("Watch rejected command %02X (code %02X)", command, errorCode));
            this.command = command;
            this.errorCode = errorCode;
        }
    }
}
