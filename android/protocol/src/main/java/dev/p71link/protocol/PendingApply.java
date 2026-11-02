package dev.p71link.protocol;

/**
 * Identity of a user's Apply that waits on something slow: a confirmation dialog or a file download.
 * It stays current only while the page that started it is still the same showing of that page and the
 * same ready watch session exists. A session is a link epoch: every connect gets a new one, even to the
 * same device, so a reconnect always makes earlier pending work stale. Main thread only.
 */
public final class PendingApply {
    /** Token of the ready watch session, or 0 when none is ready. */
    public interface Session { int token(); }

    private final Session session;
    private int generation;

    public PendingApply(Session session) { this.session = session; }

    /** The page was hidden or shown again: every earlier ticket becomes stale. */
    public void invalidate() { generation++; }

    /** Capture the page showing and the ready session, or null when no session is ready. */
    public Ticket arm() {
        int s = session.token();
        return s == 0 ? null : new Ticket(generation, s);
    }

    public final class Ticket {
        /** The session this Apply belongs to; pass it to the transfer so the manager can refuse a newer one. */
        public final int session;
        private final int generation;

        private Ticket(int generation, int session) {
            this.generation = generation;
            this.session = session;
        }

        public boolean current() {
            return generation == PendingApply.this.generation && session != 0 && session == PendingApply.this.session.token();
        }

        /** Run {@code next} only while current; otherwise run {@code stale}. Returns whether next ran. */
        public boolean proceed(Runnable next, Runnable stale) {
            if (current()) {
                next.run();
                return true;
            }
            stale.run();
            return false;
        }
    }
}
