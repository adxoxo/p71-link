package dev.p71link.protocol;

/**
 * Main-thread epoch for user link intent. Every connect attempt, disconnect, and forget starts a new
 * epoch. Callbacks posted for an older epoch must do nothing: they cannot clear a newer session's
 * state, reconnect a forgotten watch, or deliver results as current.
 */
public final class SessionGate {
    private int epoch;

    /** Start a new epoch and return its token. */
    public int begin() { return ++epoch; }

    /** End the current epoch without starting a session. */
    public void invalidate() { epoch++; }

    public boolean current(int token) { return token == epoch; }

    public int token() { return epoch; }

    /** Exact, case-insensitive advertised-name match for the bound target watch. */
    public static boolean isTarget(String advertised, String target) {
        return advertised != null && target != null && !target.isEmpty() && advertised.trim().equalsIgnoreCase(target.trim());
    }
}
