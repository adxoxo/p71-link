package dev.p71link.protocol;

/**
 * App-wide owner of one face resource transfer (vendor face, photo or Orbit background). Before a
 * transfer may change watch resources, the cached installed list, its order and the current-face index
 * are forgotten: any transfer can add, replace or reorder entries, and a partial one leaves them unknown.
 * Fresh 35 and 75 replies are needed before rows can be selected or marked current again. When the
 * transfer ends, in any way and whichever screen started it, the owner asks for exactly one refresh.
 * Main thread only.
 */
public final class FaceTransferOwner {
    /** Forget the installed list, its order and the current-face index. */
    public interface Facts { void forget(); }

    private final ExclusiveOp op = new ExclusiveOp();
    private final Facts facts;
    /** What the running transfer sends, and its block progress. */
    public String label;
    public int sent, total;
    /** Outcome of the last finished transfer; lastError is null when the watch accepted it. */
    public String lastLabel, lastError;

    public FaceTransferOwner(Facts facts) { this.facts = facts; }

    public boolean active() { return op.active(); }

    public boolean current(int ticket) { return op.current(ticket); }

    /** Start a transfer: a ticket above zero after the facts are forgotten, or 0 when one already runs. */
    public int begin(String label, int totalBlocks) {
        int t = op.begin();
        if (t == 0) return 0;
        facts.forget();
        this.label = label;
        sent = 0;
        total = totalBlocks;
        lastLabel = null;
        lastError = null;
        return t;
    }

    /** Record progress for the running transfer; false for an old one. */
    public boolean progress(int ticket, int done, int blocks) {
        if (!op.current(ticket)) return false;
        sent = done;
        total = blocks;
        return true;
    }

    /**
     * End the transfer for {@code ticket} with {@code error} (null when accepted). Returns true exactly
     * once per transfer, when the caller should start one list refresh; false for an old ticket.
     */
    public boolean finish(int ticket, String error) {
        if (!op.end(ticket)) return false;
        lastLabel = label;
        lastError = error;
        label = null;
        // A late or failed transfer may still have changed resources on the watch.
        facts.forget();
        return true;
    }

    /** The session ended: a running transfer cannot complete, and its outcome is recorded as unknown. */
    public void reset(String reason) {
        if (op.active()) {
            lastLabel = label;
            lastError = reason;
            facts.forget();
        }
        op.reset();
        label = null;
    }
}
