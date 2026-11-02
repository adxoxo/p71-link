package dev.p71link.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch bookkeeping for catalog preview images. One fetch per URL is in flight at a time; asking again
 * while it runs starts nothing. A failure stays failed, with no automatic retry, until the user asks
 * for a retry. A finished image is fetched again only after the bitmap cache dropped it.
 * At most {@code maxLoading} fetches are outstanding; a request beyond that starts nothing and stays
 * NONE, so it is admitted on a later display after a fetch finished, never by a background retry.
 * Not thread-safe: the app calls it on the main thread only.
 */
public final class PreviewLoads {
    public enum State { NONE, LOADING, READY, FAILED }

    private final Map<String, State> states = new HashMap<>();
    private final int maxLoading;
    private int loading;

    public PreviewLoads() { this(Integer.MAX_VALUE); }

    public PreviewLoads(int maxLoading) { this.maxLoading = Math.max(1, maxLoading); }

    /** Fetches started and not yet finished or cancelled. */
    public int loading() { return loading; }

    public State state(String url) {
        State s = url == null ? null : states.get(url);
        return s == null ? State.NONE : s;
    }

    /** True when the caller must start one fetch for {@code url} now. */
    public boolean start(String url) {
        if (url == null || state(url) != State.NONE || loading >= maxLoading) return false;
        states.put(url, State.LOADING);
        loading++;
        return true;
    }

    /** Record the end of the fetch started by {@link #start}. Late or unknown results are ignored. */
    public void finish(String url, boolean ok) {
        if (state(url) != State.LOADING) return;
        states.put(url, ok ? State.READY : State.FAILED);
        loading--;
    }

    /** A started fetch will not run (refused or dropped from the queue): it may be requested again. */
    public void cancel(String url) {
        if (state(url) != State.LOADING) return;
        states.remove(url);
        loading--;
    }

    /** The bitmap cache no longer holds this image; the next display may fetch it once again. */
    public void evicted(String url) {
        if (state(url) == State.READY) states.remove(url);
    }

    /** Explicit user retry: failed images may be fetched again. Returns how many were reset. */
    public int retryFailed() {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, State> e : states.entrySet()) if (e.getValue() == State.FAILED) failed.add(e.getKey());
        for (String u : failed) states.remove(u);
        return failed.size();
    }

    public boolean anyFailed() { return states.containsValue(State.FAILED); }

    /**
     * Power-of-two decode subsampling that keeps the longer side at or under {@code maxSide}, or -1 when
     * the reported image size is invalid or too large to accept at all.
     */
    public static int sampleSize(int width, int height, int maxSide, int maxSourceSide) {
        if (width <= 0 || height <= 0 || maxSide <= 0 || width > maxSourceSide || height > maxSourceSide) return -1;
        int s = 1;
        while (Math.max(width, height) / s > maxSide) s *= 2;
        return s;
    }
}
