package dev.p71link.protocol;

import dev.p71link.protocol.CommandQueue.Callback;
import dev.p71link.protocol.CommandQueue.Channel;
import dev.p71link.protocol.CommandQueue.Operation;
import dev.p71link.protocol.CommandQueue.Step;

import java.util.Collections;

/**
 * One manual measurement at a time for a session.
 *
 * The gate opens at start and closes only after the stop request [type,0,0] is acknowledged, or the
 * session ends. A rejected or unanswered stop keeps it closed for the rest of the session. A second request while the gate is open fails at once and is never queued, so a
 * later measurement cannot start before the earlier stop. Notifications on one characteristic arrive
 * in order, so any 69 frame the watch sent before it handled the stop arrives before the 6A
 * acknowledgment and is routed as unsolicited, not to a later request. No value is ever sent in stop.
 */
public final class Measurement {
    public static final long RESULT_MS = 45000;

    public static final class BusyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        BusyException() { super("A measurement is already running. Wait until it stops."); }
    }

    public static final class NoResultException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        NoResultException() { super("The watch gave no result in time. Nothing was recorded."); }
    }

    /** The watch did not acknowledge a stop. Measuring stays blocked until a new connection. */
    public static final class StopUnconfirmedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        StopUnconfirmedException() {
            super("The last measurement did not stop cleanly. Reconnect the watch before measuring again.");
        }
    }

    private final WatchClient client;
    private final CommandQueue.Scheduler scheduler;
    private boolean active, stopUnconfirmed;

    public Measurement(WatchClient client, CommandQueue.Scheduler scheduler) {
        this.client = client;
        this.scheduler = scheduler;
    }

    /** True from start until the stop acknowledgment or the end of the session. */
    public boolean active() { return active && !client.queue().closed(); }

    /** The result arrives once; the gate stays open until the stop completes. */
    public void start(int type, Callback<Health.MeasurementUpdate> cb) {
        if (active()) {
            cb.onError(stopUnconfirmed ? new StopUnconfirmedException() : new BusyException());
            return;
        }
        active = true;
        final Health.MeasurementUpdate[] last = new Health.MeasurementUpdate[1];
        final Operation[] op = new Operation[1];
        final CommandQueue.Cancellable[] deadline = new CommandQueue.Cancellable[1];
        op[0] = client.queue().submit("measure", Channel.SHORT, Collections.singletonList(Health.startMeasurement(type)),
                (ch, f) -> {
                    if (ch != Channel.SHORT || ShortProtocol.match(f, ShortProtocol.MEASURE_START, ShortProtocol.NO_ACTION) == null) {
                        return Step.NOT_MINE;
                    }
                    Health.MeasurementUpdate u = Health.measurement(f, type);
                    last[0] = u;
                    return u.finished() ? Step.DONE : Step.MORE;
                }, 0, new Callback<Void>() {
                    @Override public void onResult(Void v) {
                        if (deadline[0] != null) deadline[0].cancel();
                        cb.onResult(last[0]);
                        stop(type);
                    }

                    @Override public void onError(Exception e) {
                        if (deadline[0] != null) deadline[0].cancel();
                        cb.onError(e);
                        stop(type);
                    }
                });
        if (!op[0].completed) {
            deadline[0] = scheduler.schedule(() -> client.queue().release(op[0], new NoResultException()), RESULT_MS);
        }
    }

    private void stop(int type) {
        if (client.queue().closed()) {
            active = false;
            return;
        }
        client.request("measure stop", Health.stopMeasurement(type), ShortProtocol.NO_ACTION, f -> null,
                new Callback<Object>() {
                    @Override public void onResult(Object v) { active = false; }

                    /** Without an acknowledged stop the gate stays closed for this session. */
                    @Override public void onError(Exception e) {
                        if (client.queue().closed()) active = false;
                        else stopUnconfirmed = true;
                    }
                });
    }
}
