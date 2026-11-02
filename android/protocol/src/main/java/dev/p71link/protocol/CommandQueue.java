package dev.p71link.protocol;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Serialized command queue for exactly one GATT session. All methods run on one thread.
 *
 * Session contract:
 * - One GATT write is outstanding at a time.
 * - An operation accepts replies from its first write onward, so a notification that arrives
 *   before the write callback still matches.
 * - An operation completes when every write is complete and its reply handler reports done.
 * - A timeout or a failed write leaves the transport and the watch state ambiguous. The queue then
 *   invalidates the whole session: it fails the current and every queued operation, ignores every
 *   later callback, and asks the session owner to close the GATT. Recovery needs a new connection
 *   and a new queue. Nothing is replayed.
 * - A cancelled running operation reports cancellation at once but stays current, so its late reply
 *   cannot complete a later request with the same opcode.
 * - Frames that no operation claims go to the unsolicited listener.
 */
public final class CommandQueue {
    public enum Channel { SHORT, BULK }

    public enum Step { NOT_MINE, MORE, DONE }

    public interface Transport {
        /** Start one write. Return false when it cannot start. Completion arrives through onWriteComplete. */
        boolean write(Channel channel, byte[] value);
    }

    public interface Cancellable { void cancel(); }

    public interface Scheduler { Cancellable schedule(Runnable task, long delayMs); }

    public interface Listener { void onUnsolicited(Channel channel, byte[] frame); }

    /** Told once when the session becomes unusable. The owner must close this GATT connection. */
    public interface SessionOwner { void onSessionInvalid(Exception reason); }

    public interface Callback<T> {
        void onResult(T value);

        void onError(Exception error);
    }

    /** Handles candidate reply frames. Throw ProtocolException for a frame that names this request but fails. */
    public interface ReplyHandler {
        Step accept(Channel channel, byte[] frame);
    }

    /** The watch did not answer. Its stored state is unknown and must be read again on a new session. */
    public static final class TimeoutException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String operation;
        public final boolean writeConfirmed;

        public TimeoutException(String operation, boolean writeConfirmed) {
            super("The watch did not answer. The result is unknown.");
            this.operation = operation;
            this.writeConfirmed = writeConfirmed;
        }
    }

    public static final class DisconnectedException extends Exception {
        private static final long serialVersionUID = 1L;
        public DisconnectedException(String m) { super(m); }
    }

    public static final class CancelledException extends Exception {
        private static final long serialVersionUID = 1L;
        public CancelledException(String m) { super(m); }
    }

    public static final class WriteFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        public WriteFailedException(String m) { super(m); }
    }

    /** One request/reply unit. */
    public static final class Operation {
        final String label;
        final Channel channel;
        final List<byte[]> writes;
        final ReplyHandler handler;
        final long timeoutMs;
        final Callback<Void> callback;
        int nextWrite;
        boolean replyDone, completed, reported, cancelled, aborted;
        Cancellable timer;

        Operation(String label, Channel channel, List<byte[]> writes, ReplyHandler handler, long timeoutMs,
                  Callback<Void> callback) {
            if (writes == null || writes.isEmpty()) throw new IllegalArgumentException("No writes");
            this.label = label;
            this.channel = channel;
            this.writes = writes;
            this.handler = handler;
            this.timeoutMs = timeoutMs;
            this.callback = callback;
            this.replyDone = handler == null;
        }

        public String label() { return label; }
    }

    private final Transport transport;
    private final Scheduler scheduler;
    private final Listener listener;
    private final SessionOwner owner;
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private final BulkProtocol.Assembler assembler = new BulkProtocol.Assembler();
    private Operation current;
    private boolean writeInFlight;
    /** Local transport deadline for one write callback, independent of any reply deadline. */
    public static final long WRITE_CALLBACK_MS = 5000;
    private Cancellable writeWatchdog;
    private boolean closed;
    private Exception closeReason;
    private int maxPending = 64;

    /** Transport counters for this session only. Each counts an actual operation, not an estimate. */
    public static final class Stats {
        public int shortWritesStarted, shortWritesConfirmed, bulkFragmentsStarted, bulkFragmentsConfirmed,
                shortNotifications, bulkNotifications, operationsSubmitted, operationsFailed;

        Stats copy() {
            Stats c = new Stats();
            c.shortWritesStarted = shortWritesStarted;
            c.shortWritesConfirmed = shortWritesConfirmed;
            c.bulkFragmentsStarted = bulkFragmentsStarted;
            c.bulkFragmentsConfirmed = bulkFragmentsConfirmed;
            c.shortNotifications = shortNotifications;
            c.bulkNotifications = bulkNotifications;
            c.operationsSubmitted = operationsSubmitted;
            c.operationsFailed = operationsFailed;
            return c;
        }
    }

    private final Stats stats = new Stats();
    private Channel inFlightChannel;

    public Stats stats() { return stats.copy(); }
    private int corruptFrames;

    public CommandQueue(Transport transport, Scheduler scheduler, Listener listener) {
        this(transport, scheduler, listener, null);
    }

    public CommandQueue(Transport transport, Scheduler scheduler, Listener listener, SessionOwner owner) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.listener = listener;
        this.owner = owner;
    }

    public void setMaxPending(int value) { maxPending = value; }

    public int corruptFrames() { return corruptFrames; }

    public boolean idle() { return current == null && pending.isEmpty() && !writeInFlight; }

    public boolean closed() { return closed; }

    public Exception closeReason() { return closeReason; }

    public int pendingCount() { return pending.size() + (current == null ? 0 : 1); }

    public Operation submit(String label, Channel channel, List<byte[]> writes, ReplyHandler handler, long timeoutMs,
                            Callback<Void> callback) {
        Operation op = new Operation(label, channel, writes, handler, timeoutMs, callback);
        stats.operationsSubmitted++;
        if (closed) {
            report(op, new DisconnectedException("Watch is not connected"));
            return op;
        }
        if (pendingCount() >= maxPending) {
            report(op, new IllegalStateException("Too many queued watch requests"));
            return op;
        }
        pending.add(op);
        pump();
        return op;
    }

    /**
     * Cancel an operation. A queued one is removed. A running one reports cancellation now but stays
     * current until its reply arrives, so the reply cannot satisfy another request. Its timer still runs.
     */
    public void cancel(Operation op) {
        if (op == null || op.reported || closed) return;
        if (pending.remove(op)) {
            op.completed = true;
            report(op, new CancelledException("Cancelled"));
            return;
        }
        if (op == current) {
            op.cancelled = true;
            report(op, new CancelledException("Cancelled"));
        }
    }

    /**
     * End a running operation whose late replies the caller knows to be harmless, for example a
     * manual measurement that is stopped separately. Later frames go to the unsolicited listener.
     */
    public void release(Operation op, Exception reason) {
        if (op == null || closed || op.completed) return;
        if (pending.remove(op)) {
            op.completed = true;
            report(op, reason);
            return;
        }
        if (op == current) finishWithError(op, reason);
    }

    public void onWriteComplete(boolean success) {
        if (closed || !writeInFlight) return; // Stale or duplicate callback.
        writeInFlight = false;
        if (success) {
            if (inFlightChannel == Channel.BULK) stats.bulkFragmentsConfirmed++;
            else stats.shortWritesConfirmed++;
        }
        cancelWatchdog();
        Operation op = current;
        if (op == null) {
            pump();
            return;
        }
        if (!success) {
            invalidate(new WriteFailedException("Bluetooth write failed. The result is unknown."));
            return;
        }
        op.nextWrite++;
        advance(op);
    }

    public void onNotification(Channel channel, byte[] value) {
        if (closed || value == null) return;
        if (channel == Channel.SHORT) stats.shortNotifications++;
        else stats.bulkNotifications++;
        if (channel == Channel.SHORT) {
            if (value.length != ShortProtocol.FRAME) {
                corruptFrames++;
                return;
            }
            route(channel, value.clone());
            return;
        }
        List<byte[]> frames;
        try {
            frames = assembler.accept(value);
        } catch (ProtocolException e) {
            assembler.reset();
            corruptFrames++;
            Operation op = current;
            if (op != null && op.channel == Channel.BULK && op.handler != null && !op.replyDone) finishWithError(op, e);
            return;
        }
        for (byte[] f : frames) {
            if (closed) return;
            route(channel, f);
        }
    }

    /** The link dropped. Fail everything and ignore every later callback for this session. */
    public void onDisconnected() {
        close(new DisconnectedException("Watch disconnected"), false);
    }

    private void invalidate(Exception reason) { close(reason, true); }

    private void close(Exception reason, boolean notifyOwner) {
        if (closed) return;
        closed = true;
        closeReason = reason;
        writeInFlight = false;
        cancelWatchdog();
        assembler.reset();
        Operation op = current;
        current = null;
        if (op != null) {
            op.completed = true;
            if (op.timer != null) op.timer.cancel();
            report(op, reason);
        }
        while (!pending.isEmpty()) {
            Operation p = pending.poll();
            p.completed = true;
            report(p, new DisconnectedException("The connection was reset before this request was sent"));
        }
        if (notifyOwner && owner != null) owner.onSessionInvalid(reason);
    }

    private void route(Channel channel, byte[] frame) {
        Operation op = current;
        boolean started = op != null && (op.nextWrite > 0 || writeInFlight);
        if (started && !op.replyDone && op.handler != null) {
            Step step;
            try {
                step = op.handler.accept(channel, frame);
            } catch (ProtocolException e) {
                if (!(e instanceof ProtocolException.Rejected)) corruptFrames++;
                finishWithError(op, e);
                return;
            } catch (RuntimeException e) {
                finishWithError(op, e);
                return;
            }
            if (step == Step.MORE) {
                restartTimer(op);
                return;
            }
            if (step == Step.DONE) {
                op.replyDone = true;
                maybeFinish(op);
                return;
            }
        }
        if (listener != null) listener.onUnsolicited(channel, frame);
    }

    private void pump() {
        if (closed || writeInFlight || current != null) return;
        Operation op = pending.poll();
        if (op == null) return;
        current = op;
        restartTimer(op);
        advance(op);
    }

    private void advance(Operation op) {
        if (op.aborted) {
            // A failed request sends none of its remaining writes.
            op.nextWrite = op.writes.size();
        }
        if (op.nextWrite >= op.writes.size()) {
            maybeFinish(op);
            return;
        }
        if (op.cancelled && op.nextWrite > 0 && op.handler == null) {
            // A cancelled write-only sequence stops between writes.
            op.replyDone = true;
            op.nextWrite = op.writes.size();
            maybeFinish(op);
            return;
        }
        writeInFlight = true;
        boolean started = transport.write(op.channel, op.writes.get(op.nextWrite));
        if (started) {
            inFlightChannel = op.channel;
            if (op.channel == Channel.BULK) stats.bulkFragmentsStarted++;
            else stats.shortWritesStarted++;
            // A lost write callback would otherwise block the queue forever, even for operations
            // without a reply deadline. Its outcome is unknown, so the session ends.
            writeWatchdog = scheduler.schedule(() -> {
                if (!closed && writeInFlight) {
                    invalidate(new WriteFailedException("Bluetooth did not confirm a write. The result is unknown."));
                }
            }, WRITE_CALLBACK_MS);
        } else {
            writeInFlight = false;
            if (op.nextWrite == 0) {
                // Nothing reached the radio for this request; the session stays consistent.
                finishWithError(op, new WriteFailedException("Bluetooth write could not start"));
            } else {
                invalidate(new WriteFailedException("Bluetooth write could not start. The result is unknown."));
            }
        }
    }

    private void cancelWatchdog() {
        if (writeWatchdog != null) writeWatchdog.cancel();
        writeWatchdog = null;
    }

    private void maybeFinish(Operation op) {
        if (op.completed || !op.replyDone || op.nextWrite < op.writes.size() || writeInFlight) return;
        op.completed = true;
        if (op.timer != null) op.timer.cancel();
        current = null;
        if (!op.reported) {
            op.reported = true;
            if (op.callback != null) op.callback.onResult(null);
        }
        pump();
    }

    /** A definite failure: the reply arrived and failed a rule. The session stays usable. */
    private void finishWithError(Operation op, Exception e) {
        if (op.completed) return;
        op.replyDone = true;
        op.aborted = true;
        report(op, e);
        if (writeInFlight) return; // The rest completes when the outstanding write callback arrives.
        op.completed = true;
        if (op.timer != null) op.timer.cancel();
        if (op == current) current = null;
        pump();
    }

    private void restartTimer(Operation op) {
        if (op.timer != null) op.timer.cancel();
        if (op.timeoutMs <= 0) return;
        op.timer = scheduler.schedule(() -> {
            if (!closed && op == current && !op.completed) {
                invalidate(new TimeoutException(op.label, op.nextWrite >= op.writes.size()));
            }
        }, op.timeoutMs);
    }

    private void report(Operation op, Exception e) {
        if (op.reported) return;
        stats.operationsFailed++;
        op.reported = true;
        if (op.callback != null) op.callback.onError(e);
    }
}
