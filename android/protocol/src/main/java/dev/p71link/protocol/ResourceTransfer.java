package dev.p71link.protocol;

import dev.p71link.protocol.CommandQueue.Callback;
import dev.p71link.protocol.CommandQueue.Operation;

import java.util.Arrays;

/**
 * Face resource upload: init, one-based 1024-byte blocks, finish. One step at a time, no retries.
 * DIY wallpaper uses 31/32/33. Vendor market faces use 36/37/38 with a required status byte.
 * This class never addresses a firmware-update handler.
 */
public final class ResourceTransfer {
    public static final int BLOCK = 1024;

    public interface Progress {
        void onProgress(int sentBlocks, int totalBlocks);
    }

    public enum Kind { WALLPAPER, VENDOR_FACE }

    private final WatchClient client;
    private final Kind kind;
    private final String name;
    private final byte[] file;
    private final int total;
    private final Progress progress;
    private final Callback<Void> done;
    private int sent;
    private boolean stopped, finished;
    private Operation current;

    public ResourceTransfer(WatchClient client, Kind kind, String name, byte[] file, Progress progress, Callback<Void> done) {
        if (file == null || file.length == 0 || file.length > BulkProtocol.MAX_FACE_FILE) {
            throw new IllegalArgumentException("Invalid face file size");
        }
        if (kind == Kind.WALLPAPER && !BulkProtocol.WALLPAPER_NAME.equals(name)) {
            throw new IllegalArgumentException("Wallpaper uses its fixed resource name");
        }
        this.client = client;
        this.kind = kind;
        this.name = name;
        this.file = file.clone();
        this.total = (file.length + BLOCK - 1) / BLOCK;
        this.progress = progress;
        this.done = done;
    }

    public int totalBlocks() { return total; }

    private boolean vendor() { return kind == Kind.VENDOR_FACE; }

    public void start() {
        int op = vendor() ? BulkProtocol.FACE_INIT : BulkProtocol.DIY_INIT;
        current = client.bulk("face init", BulkProtocol.resourceInit(vendor(), name, file.length), op,
                WatchClient.BULK_TIMEOUT_MS, step(reply -> {
                    if (status(reply, op) != 0) throw new ProtocolException("The watch refused the face file");
                    sendBlock();
                }));
    }

    /** Stop after the current step. The watch may keep a partial resource; no rollback exists. */
    public void stop() {
        if (finished) return;
        stopped = true;
        client.queue().cancel(current);
    }

    private void sendBlock() {
        if (sent >= total) {
            sendFinish();
            return;
        }
        int from = sent * BLOCK;
        byte[] raw = Arrays.copyOfRange(file, from, Math.min(file.length, from + BLOCK));
        int op = vendor() ? BulkProtocol.FACE_DATA : BulkProtocol.DIY_DATA;
        current = client.bulk("face block", BulkProtocol.resourceBlock(vendor(), sent + 1, raw), op,
                WatchClient.BULK_TIMEOUT_MS, step(reply -> {
                    sent++;
                    if (progress != null) progress.onProgress(sent, total);
                    sendBlock();
                }));
    }

    private void sendFinish() {
        int op = vendor() ? BulkProtocol.FACE_FINISH : BulkProtocol.DIY_FINISH;
        current = client.bulk("face finish", BulkProtocol.resourceFinish(vendor()), op, WatchClient.BULK_TIMEOUT_MS,
                step(reply -> {
                    if (status(reply, op) != 0) throw new ProtocolException("The watch reported a face install error");
                    finished = true;
                    done.onResult(null);
                }));
    }

    /** Vendor init/finish need a status byte. DIY replies with a payload use the same nonzero-error rule. */
    private int status(byte[] reply, int op) {
        if (vendor()) return BulkProtocol.statusByte(reply, op);
        byte[] p = BulkProtocol.payload(reply, op);
        return p.length == 0 ? 0 : p[0] & 255;
    }

    private interface Body { void run(byte[] reply); }

    private Callback<byte[]> step(Body body) {
        return new Callback<byte[]>() {
            @Override public void onResult(byte[] reply) {
                if (stopped) {
                    fail(new CommandQueue.CancelledException("Transfer stopped"));
                    return;
                }
                try {
                    body.run(reply);
                } catch (RuntimeException e) {
                    fail(e);
                }
            }

            @Override public void onError(Exception e) { fail(e); }
        };
    }

    private void fail(Exception e) {
        if (finished) return;
        finished = true;
        done.onError(e);
    }
}
