package dev.p71link.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Two bounded worker lanes for the optional catalog. Preview images use one lane with a fixed number of
 * running and queued tasks; queued previews for a page that is gone can be dropped. Catalog metadata and
 * explicit file downloads use the other lane, so they never wait behind preview images. A full lane
 * refuses work instead of growing; callers turn that into a stable message or a later explicit retry.
 */
public final class FetchLanes {
    private static final class Task implements Runnable {
        final String key;
        final Runnable body;

        Task(String key, Runnable body) {
            this.key = key;
            this.body = body;
        }

        @Override public void run() { body.run(); }
    }

    private final ThreadPoolExecutor previews, control;
    private final int previewCapacity;

    public FetchLanes(int previewThreads, int previewQueue, int controlThreads, int controlQueue) {
        previews = lane(previewThreads, previewQueue, "p71-preview");
        control = lane(controlThreads, controlQueue, "p71-catalog");
        previewCapacity = previewThreads + previewQueue;
    }

    private static ThreadPoolExecutor lane(int threads, int queue, String name) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    /** Running plus queued preview tasks the lane accepts. */
    public int previewCapacity() { return previewCapacity; }

    /** Queue one preview task for {@code key}; false when the lane is full. */
    public boolean preview(String key, Runnable body) {
        try {
            previews.execute(new Task(key, body));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Queue catalog metadata or an explicit download; false when the lane is full. */
    public boolean control(Runnable body) {
        try {
            control.execute(body);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Remove preview tasks that have not started and return their keys. Running tasks finish normally. */
    public List<String> dropQueuedPreviews() {
        List<Runnable> drained = new ArrayList<>();
        previews.getQueue().drainTo(drained);
        List<String> keys = new ArrayList<>();
        for (Runnable r : drained) if (r instanceof Task) keys.add(((Task) r).key);
        return keys;
    }
}
