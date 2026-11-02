package dev.p71link.protocol;

import dev.p71link.protocol.CommandQueue.Callback;
import dev.p71link.protocol.CommandQueue.Channel;
import dev.p71link.protocol.CommandQueue.Operation;
import dev.p71link.protocol.CommandQueue.Step;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Typed request helpers over one CommandQueue. Parsers run on the queue thread. */
public final class WatchClient {
    public static final long SHORT_TIMEOUT_MS = 4000;
    public static final long HISTORY_TIMEOUT_MS = 6000;
    public static final long BULK_TIMEOUT_MS = 8000;

    private final CommandQueue queue;
    private volatile int bulkChunk = BulkProtocol.DEFAULT_CHUNK;
    /** Last passive 2F fragment report for this session, or -1 when none arrived. */
    private volatile int reportedFragment = -1;

    public void onPackageLength(int reported, int attPayload) {
        reportedFragment = reported;
        setBulkChunk(reported, attPayload);
    }

    public int reportedFragment() { return reportedFragment; }

    public WatchClient(CommandQueue queue) { this.queue = queue; }

    public CommandQueue queue() { return queue; }

    /** Fragment size: max(watch-announced length, 20), capped by the negotiated ATT payload. */
    public void setBulkChunk(int announced, int attPayload) {
        int v = Math.max(announced, BulkProtocol.DEFAULT_CHUNK);
        if (attPayload >= BulkProtocol.DEFAULT_CHUNK) v = Math.min(v, attPayload);
        bulkChunk = Math.min(v, 512);
    }

    public int bulkChunk() { return bulkChunk; }

    /** One short request, one reply with the expected command and action. */
    public <T> Operation request(String label, byte[] frame, int action, Function<byte[], T> parse, Callback<T> cb) {
        final int command = frame[0] & 0x7f;
        final Object[] box = new Object[1];
        return queue.submit(label, Channel.SHORT, Collections.singletonList(frame), (ch, f) -> {
            if (ch != Channel.SHORT) return Step.NOT_MINE;
            if (ShortProtocol.match(f, command, action) == null) return Step.NOT_MINE;
            box[0] = parse.apply(f);
            return Step.DONE;
        }, SHORT_TIMEOUT_MS, adapt(box, cb));
    }

    /** Write a value, then read it back through a separate request. Reports the readback. */
    public <T> void writeThenRead(String label, byte[] write, int writeAction, byte[] read, int readAction,
                                  Function<byte[], T> parse, Callback<T> cb) {
        request(label + " write", write, writeAction, f -> f, new Callback<byte[]>() {
            @Override public void onResult(byte[] ignored) { request(label + " readback", read, readAction, parse, cb); }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** A write with an action-2 echo, without an implicit readback. */
    public Operation writeAck(String label, byte[] frame, int action, Callback<Void> cb) {
        return request(label, frame, action, f -> null, cb);
    }

    /** Write with no reply in the decoded schema. Success means the Bluetooth write completed. */
    public Operation send(String label, byte[] frame, Callback<Void> cb) {
        return queue.submit(label, Channel.SHORT, Collections.singletonList(frame), null, SHORT_TIMEOUT_MS, cb);
    }

    /** Multi-frame reader. The reader returns true once its protocol-defined final frame arrives. */
    public interface FrameReader { boolean accept(byte[] frame); }

    public <R extends FrameReader> Operation history(String label, byte[] frame, R reader, Callback<R> cb) {
        final int command = frame[0] & 0x7f;
        return queue.submit(label, Channel.SHORT, Collections.singletonList(frame), (ch, f) -> {
            if (ch != Channel.SHORT || (f[0] & 0x7f) != command) return Step.NOT_MINE;
            return reader.accept(f) ? Step.DONE : Step.MORE;
        }, HISTORY_TIMEOUT_MS, new Callback<Void>() {
            @Override public void onResult(Void v) { cb.onResult(reader); }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** One bulk request frame; the reply is the next complete frame with the expected opcode. */
    public Operation bulk(String label, byte[] frame, int replyCommand, long timeoutMs, Callback<byte[]> cb) {
        final byte[][] box = new byte[1][];
        return queue.submit(label, Channel.BULK, BulkProtocol.fragments(frame, bulkChunk), (ch, f) -> {
            if (ch != Channel.BULK || BulkProtocol.command(f) != replyCommand) return Step.NOT_MINE;
            BulkProtocol.payload(f, replyCommand); // Length and CRC check.
            box[0] = f;
            return Step.DONE;
        }, timeoutMs, new Callback<Void>() {
            @Override public void onResult(Void v) { cb.onResult(box[0]); }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** Bulk frame with no reply in the decoded schema. Success means every fragment write completed. */
    public Operation bulkSend(String label, byte[] frame, Callback<Void> cb) {
        return queue.submit(label, Channel.BULK, BulkProtocol.fragments(frame, bulkChunk), null, BULK_TIMEOUT_MS, cb);
    }

    /** Bulk request whose reply must also satisfy a payload predicate, for example a read action byte. */
    public Operation bulkWhere(String label, byte[] frame, int replyCommand, java.util.function.Predicate<byte[]> payloadMatch,
                               Callback<byte[]> cb) {
        final byte[][] box = new byte[1][];
        return queue.submit(label, Channel.BULK, BulkProtocol.fragments(frame, bulkChunk), (ch, f) -> {
            if (ch != Channel.BULK || BulkProtocol.command(f) != replyCommand) return Step.NOT_MINE;
            byte[] p = BulkProtocol.payload(f, replyCommand);
            if (!payloadMatch.test(p)) return Step.NOT_MINE;
            box[0] = p;
            return Step.DONE;
        }, BULK_TIMEOUT_MS, new Callback<Void>() {
            @Override public void onResult(Void v) { cb.onResult(box[0]); }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /**
     * Text push. All chunks go out in order; completion needs every write plus one 72 acknowledgment.
     * The result means the watch acknowledged receipt, not that the text appeared.
     */
    public Operation notify(String text, int type, Callback<Void> cb) {
        return notifyFrames(Notifications.frames(text, type), cb);
    }

    /** Send prepared 72 frames; completion needs every write plus one 72 acknowledgment. */
    public Operation notifyFrames(List<byte[]> frames, Callback<Void> cb) {
        return queue.submit("notification", Channel.SHORT, frames, (ch, f) -> {
            if (ch != Channel.SHORT) return Step.NOT_MINE;
            if (ShortProtocol.match(f, ShortProtocol.NOTIFY_TEXT, ShortProtocol.NO_ACTION) == null) return Step.NOT_MINE;
            return Step.DONE;
        }, SHORT_TIMEOUT_MS + 250L * frames.size(), cb);
    }

    @SuppressWarnings("unchecked")
    private static <T> Callback<Void> adapt(Object[] box, Callback<T> cb) {
        return new Callback<Void>() {
            @Override public void onResult(Void v) { cb.onResult((T) box[0]); }
            @Override public void onError(Exception e) { cb.onError(e); }
        };
    }
}
