package dev.p71link.protocol;

/**
 * Turns call notification updates into watch call events. Only an explicit incoming call type rings
 * the watch. The same call becoming ongoing (answered), any other non-incoming update of that call,
 * or its removal sends the call-ended event. Outgoing, ongoing, screening, and unknown calls never
 * create a ringing event. Caller text is used only when the notification supplied it.
 */
public final class CallTracker {
    /** Android Notification.CALL_TYPE_* values. */
    public static final int UNKNOWN = 0, INCOMING = 1, ONGOING = 2, SCREENING = 3;

    public static final class Event {
        /** Notifications.CALL (0, ringing) or Notifications.CALL_ENDED (4). */
        public final int type;
        /** Caller text from the notification, or null when none was supplied. */
        public final String text;

        Event(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private String ringingKey, ringingText;

    public boolean ringing() { return ringingKey != null; }

    /** True when this key is the call currently ringing on the watch. */
    public boolean tracking(String key) { return key != null && key.equals(ringingKey); }

    /** How a posted notification is handled: on the call path (with an optional event), or not a call. */
    public static final class Update {
        public final boolean callPath;
        public final Event event;

        Update(boolean callPath, Event event) {
            this.callPath = callPath;
            this.event = event;
        }
    }

    private static final Update NOT_CALL = new Update(false, null);

    /**
     * Adapter for a posted notification. callTypeOrNull is null when the notification carries no call
     * type. Such an update of the ringing call is treated as UNKNOWN, so the ringing is cleared; an
     * untracked notification without a call type is not a call and never rings. Caller text is not
     * taken from an update without a call type.
     */
    public Update onPosted(String key, Integer callTypeOrNull, String callerText) {
        if (callTypeOrNull == null) {
            if (!tracking(key)) return NOT_CALL;
            return new Update(true, posted(key, UNKNOWN, null));
        }
        return new Update(true, posted(key, callTypeOrNull, callerText));
    }

    /** A call notification was posted or updated. Returns the event to send, or null. */
    public Event posted(String key, int callType, String callerText) {
        if (key == null) return null;
        String text = callerText == null || callerText.trim().isEmpty() ? null : callerText.trim();
        if (callType == INCOMING) {
            if (key.equals(ringingKey) && java.util.Objects.equals(text, ringingText)) return null; // Same ring.
            ringingKey = key;
            ringingText = text;
            return new Event(Notifications.CALL, text);
        }
        if (key.equals(ringingKey)) return end(); // Answered, screened, or no longer marked incoming.
        return null;
    }

    /** A call notification went away. */
    public Event removed(String key) {
        return key != null && key.equals(ringingKey) ? end() : null;
    }

    /** Clear without sending, for example when the link is gone. */
    public void reset() {
        ringingKey = null;
        ringingText = null;
    }

    private Event end() {
        Event e = new Event(Notifications.CALL_ENDED, ringingText);
        reset();
        return e;
    }
}
