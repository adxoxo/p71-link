package dev.p71link.protocol;

/**
 * One watch operation of a kind at a time, owned by the app-wide manager rather than a screen, so it
 * survives navigation. Each start gets a ticket; only the current ticket can end it, so a late result
 * from an operation that a reconnect already abandoned cannot release a newer one.
 * Not thread-safe: the app calls it on the main thread only.
 */
public final class ExclusiveOp {
    private int ticket;
    private boolean active;

    public boolean active() { return active; }

    /** A ticket above zero, or 0 when an operation is already running. */
    public int begin() {
        if (active) return 0;
        active = true;
        return ++ticket;
    }

    /** True while {@code t} is the running operation. */
    public boolean current(int t) { return active && t == ticket; }

    /** End the operation for {@code t}. Returns false, changing nothing, for an old ticket. */
    public boolean end(int t) {
        if (!active || t != ticket) return false;
        active = false;
        return true;
    }

    /** The session ended: the operation cannot complete. Any older ticket becomes stale. */
    public void reset() {
        active = false;
        ticket++;
    }
}
