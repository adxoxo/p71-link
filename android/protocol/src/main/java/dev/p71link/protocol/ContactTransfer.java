package dev.p71link.protocol;

import dev.p71link.protocol.CommandQueue.Callback;

import java.util.List;

/**
 * Whole-list contact replacement for one session, one list at a time. The next part is sent only
 * after the previous one was accepted. A second list is refused until the first completes or fails,
 * so parts of two lists can never interleave.
 */
public final class ContactTransfer {
    public interface Progress { void update(int done, int total); }

    public static final class BusyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        BusyException() { super("A contact list is already being sent. Wait until it finishes."); }
    }

    private final WatchClient client;
    private boolean active;

    public ContactTransfer(WatchClient client) { this.client = client; }

    public boolean active() { return active && !client.queue().closed(); }

    public void send(List<Contacts.Contact> contacts, int maximum, Progress progress, Callback<Void> cb) {
        if (active()) {
            cb.onError(new BusyException());
            return;
        }
        List<byte[]> payloads;
        try {
            payloads = Contacts.payloads(contacts, maximum);
        } catch (IllegalArgumentException e) {
            cb.onError(e);
            return;
        }
        active = true;
        part(payloads, 0, progress, cb);
    }

    private void part(List<byte[]> payloads, int index, Progress progress, Callback<Void> cb) {
        if (index >= payloads.size()) {
            active = false;
            cb.onResult(null);
            return;
        }
        client.bulk("contacts", BulkProtocol.frame(BulkProtocol.CONTACTS, payloads.get(index)), BulkProtocol.CONTACTS,
                WatchClient.BULK_TIMEOUT_MS, new Callback<byte[]>() {
                    @Override public void onResult(byte[] reply) {
                        try {
                            Contacts.requireOk(reply);
                        } catch (RuntimeException e) {
                            active = false;
                            cb.onError(e);
                            return;
                        }
                        if (progress != null) progress.update(index + 1, payloads.size());
                        part(payloads, index + 1, progress, cb);
                    }

                    @Override public void onError(Exception e) {
                        active = false;
                        cb.onError(e);
                    }
                });
    }
}
