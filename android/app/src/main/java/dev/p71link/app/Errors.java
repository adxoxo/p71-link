package dev.p71link.app;

import dev.p71link.protocol.CommandQueue;
import dev.p71link.protocol.ProtocolException;

/** Plain-language error text. No packet bytes or protocol jargon reach product screens. */
final class Errors {
    private Errors() {}

    static String message(Throwable e) {
        if (e instanceof CommandQueue.TimeoutException) {
            return "The watch did not answer, so the result is unknown. Reconnect and check the setting again.";
        }
        if (e instanceof CommandQueue.WriteFailedException) {
            return "Bluetooth could not deliver the request. The result is unknown. Reconnect and check again.";
        }
        if (e instanceof CommandQueue.DisconnectedException) return "The watch is not connected.";
        if (e instanceof CommandQueue.CancelledException) return "Stopped.";
        if (e instanceof ProtocolException.Rejected) return "The watch does not accept this request.";
        if (e instanceof ProtocolException) return "The watch sent a reply this app cannot confirm.";
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            String m = e.getMessage();
            return m == null ? "That value is not allowed." : m;
        }
        return "Something went wrong. Nothing was confirmed.";
    }

    /** True when a write may or may not have reached the watch. */
    static boolean ambiguous(Throwable e) {
        return e instanceof CommandQueue.TimeoutException || e instanceof CommandQueue.WriteFailedException;
    }
}
