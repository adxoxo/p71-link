package dev.p71link.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import dev.p71link.protocol.FacePreviews;
import dev.p71link.protocol.FetchLanes;
import dev.p71link.protocol.PreviewLoads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Optional public face catalog for the exact hardware. Used only when the user opens it.
 * A guest token is kept in memory only and is never logged or stored. Nothing about the user,
 * the phone, or health data is sent. Files pass through unchanged.
 */
final class FaceCatalog {
    static final String HARDWARE = "M71J_V1.0";
    private static final String BASE = "https://api1.qcwxkjvip.com/qcwx/";
    private static final String FILE_HOST = "api2.qcwxkjvip.com";
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_JSON = 4 * 1024 * 1024;

    static final class Face {
        final String name, binUrl, previewUrl, hardware;
        final int typeId;

        Face(String name, String binUrl, String previewUrl, int typeId, String hardware) {
            this.name = name;
            this.binUrl = binUrl;
            this.previewUrl = previewUrl;
            this.typeId = typeId;
            this.hardware = hardware;
        }
    }

    interface Result<T> {
        void ok(T v);

        void fail(String m);
    }

    /**
     * Longest decoded preview side. Catalog previews are 240 x 284 and decode at native size; larger
     * sources are subsampled. At most 300 x 300 ARGB per image, the cache below holds over 40 images,
     * more than one catalog page plus the installed faces, so showing a page never evicts its own images.
     */
    static final int PREVIEW_MAX_SIDE = 300;
    private static final int PREVIEW_MAX_SOURCE_SIDE = 4096, PREVIEW_MAX_BYTES = 512 * 1024;
    /** Decoded preview budget. It holds several times one catalog page plus the installed faces. */
    private static final int PREVIEW_CACHE_BYTES = 16 * 1024 * 1024;

    /**
     * Previews: 2 running and 6 queued at most. Catalog list and explicit downloads: their own lane of
     * 2 running and 4 queued, so an Apply download never waits behind preview images.
     */
    private final FetchLanes lanes = new FetchLanes(2, 6, 2, 4);
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Outstanding preview fetches never exceed what the preview lane accepts. */
    private final PreviewLoads loads = new PreviewLoads(lanes.previewCapacity());
    private final LruCache<String, Bitmap> previews = new LruCache<String, Bitmap>(PREVIEW_CACHE_BYTES) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getAllocationByteCount(); }

        @Override protected void entryRemoved(boolean evicted, String k, Bitmap old, Bitmap now) {
            if (now == null) loads.evicted(k); // Puts and evictions happen on the main thread.
        }
    };
    private volatile String token;
    // Main-thread state.
    List<Face> faces;
    private FacePreviews.Index index;
    boolean loading;
    String loadError;
    private final List<Result<List<Face>>> waiting = new ArrayList<>();
    private boolean notifyPosted;
    /** The visible face screen; it is re-rendered once after preview images arrive. Main thread only. */
    private static Runnable previewListener;

    /** The shared catalog client. Creating it uses no network; only load, preview and download do. */
    static FaceCatalog get() {
        P71App app = P71App.get();
        if (app.catalog == null) app.catalog = new FaceCatalog();
        return app.catalog;
    }

    /** The catalog if it was already loaded in this process, otherwise null. Never uses the network. */
    static FaceCatalog loaded() {
        FaceCatalog c = P71App.get().catalog;
        return c != null && c.faces != null ? c : null;
    }

    static void listen(Runnable r) { previewListener = r; }

    static void unlisten(Runnable r) { if (previewListener == r) previewListener = null; }

    /** Upgrade a catalog file URL to HTTPS and accept only the vendor file host. */
    static String safeFileUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.startsWith("http://")) u = "https://" + u.substring(7);
        try {
            URL url = new URL(u);
            if (!"https".equals(url.getProtocol()) || !FILE_HOST.equalsIgnoreCase(url.getHost())) return null;
            if (url.getQuery() != null || url.getUserInfo() != null) return null;
            return u;
        } catch (IOException e) {
            return null;
        }
    }

    /** Load the list once; callers that ask while it loads share the same request. Main thread. */
    void load(Result<List<Face>> r) {
        waiting.add(r);
        if (loading) return;
        loading = true;
        loadError = null;
        boolean queued = lanes.control(() -> {
            try {
                if (token == null) {
                    JSONObject t = json(BASE + "token/getToken?key=qcwx_android", false);
                    String v = t.optString("data", null);
                    if (v == null || v.isEmpty()) throw new IOException("no token");
                    token = v;
                }
                JSONObject list = json(BASE + "device-file/list-watch-face?hardwareVersion=" + HARDWARE, true);
                JSONArray data = list.optJSONArray("data");
                if (data == null) throw new IOException("no list");
                List<Face> out = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o == null) continue;
                    if (!HARDWARE.equals(o.optString("hardwareVersion"))) continue; // Exact hardware only.
                    if (o.optDouble("price", 1) != 0) continue;                    // Free items only.
                    String bin = safeFileUrl(o.optString("binUrl", null));
                    if (bin == null || !bin.toLowerCase(Locale.ROOT).endsWith(".di")) continue;
                    String name = FacePreviews.fileName(bin);
                    if (name == null) continue;
                    out.add(new Face(name, bin, safeFileUrl(o.optString("preImageUrl", null)), o.optInt("typeId"), HARDWARE));
                }
                List<Face> result = Collections.unmodifiableList(out);
                List<FacePreviews.Entry> entries = new ArrayList<>();
                for (Face f : result) entries.add(new FacePreviews.Entry(f.name, f.previewUrl, f.hardware));
                FacePreviews.Index built = new FacePreviews.Index(entries, HARDWARE);
                main.post(() -> {
                    faces = result;
                    index = built;
                    finishLoad(result, null);
                });
            } catch (IOException | RuntimeException e) {
                token = null;
                main.post(() -> finishLoad(null, "The face catalog could not be reached. Check the internet connection and try again."));
            }
        });
        if (!queued) main.post(() -> finishLoad(null, "The face catalog is busy. Try again in a moment."));
    }

    private void finishLoad(List<Face> result, String error) {
        loading = false;
        loadError = error;
        List<Result<List<Face>>> callers = new ArrayList<>(waiting);
        waiting.clear();
        for (Result<List<Face>> r : callers) {
            if (error == null) r.ok(result);
            else r.fail(error);
        }
    }

    /** Exact file-name preview lookup for the loaded list, or null before it loaded. */
    FacePreviews.Index index() { return index; }

    /**
     * The decoded preview for an allowed URL, or null. When it is not cached and was never tried, one
     * bounded fetch starts; asking again while it runs, or after it failed, starts nothing. When the
     * preview lane is full nothing starts and the image stays waiting; a later display after a fetch
     * finished admits it. Main thread.
     */
    Bitmap preview(String url) {
        if (url == null) return null;
        Bitmap bm = previews.get(url);
        if (bm != null) return bm;
        loads.evicted(url);
        if (!loads.start(url)) return null;
        boolean queued = lanes.preview(url, () -> {
            Bitmap decoded = null;
            try {
                decoded = decodeBounded(fetch(url, PREVIEW_MAX_BYTES, false));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                decoded = null;
            }
            final Bitmap result = decoded;
            main.post(() -> {
                if (result != null) previews.put(url, result);
                loads.finish(url, result != null);
                notifyPreviews();
            });
        });
        if (!queued) loads.cancel(url);
        return null;
    }

    /**
     * Drop preview fetches that have not started, for a catalog page that is no longer shown. They become
     * waiting again and start only if shown again. Running fetches finish normally. Main thread.
     */
    void dropQueuedPreviews() {
        for (String url : lanes.dropQueuedPreviews()) loads.cancel(url);
    }

    PreviewLoads.State previewState(String url) { return loads.state(url); }

    boolean anyPreviewFailed() { return loads.anyFailed(); }

    /** Explicit user retry of failed previews. Nothing retries on its own. */
    void retryPreviews() { if (loads.retryFailed() > 0) notifyPreviews(); }

    /** One re-render for any number of finished images. */
    private void notifyPreviews() {
        if (notifyPosted) return;
        notifyPosted = true;
        main.post(() -> {
            notifyPosted = false;
            Runnable r = previewListener;
            if (r != null) r.run();
        });
    }

    private static Bitmap decodeBounded(byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int sample = PreviewLoads.sampleSize(o.outWidth, o.outHeight, PREVIEW_MAX_SIDE, PREVIEW_MAX_SOURCE_SIDE);
        if (sample < 1) return null;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(b, 0, b.length, d);
    }

    void download(Face f, Result<byte[]> r) {
        boolean queued = lanes.control(() -> {
            try {
                byte[] b = fetch(f.binUrl, dev.p71link.protocol.BulkProtocol.MAX_FACE_FILE, false);
                if (b.length < 16) throw new IOException("too small");
                main.post(() -> r.ok(b));
            } catch (IOException | RuntimeException e) {
                main.post(() -> r.fail("The face could not be downloaded. Nothing was sent to the watch."));
            }
        });
        if (!queued) main.post(() -> r.fail("The download could not start because the catalog is busy. Nothing was sent to the watch."));
    }

    /** POST a JSON body to the vendor API with the in-memory guest token. Used only by user-opened features. */
    JSONObject postJson(String path, JSONObject body) throws IOException {
        if (token == null) {
            JSONObject t = json(BASE + "token/getToken?key=qcwx_android", false);
            String v = t.optString("data", null);
            if (v == null || v.isEmpty()) throw new IOException("no token");
            token = v;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("token", token);
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_JSON) throw new IOException("too large");
                }
                JSONObject o = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (o.optInt("retCode", -1) != 0) throw new IOException("server error");
                return o;
            } catch (org.json.JSONException e) {
                throw new IOException("bad reply");
            }
        } catch (IOException e) {
            token = null;
            throw e;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Executor for the user-opened forecast request on the catalog lane. That screen allows one request
     * at a time; if the lane is ever full, the request runs on its own thread instead of being dropped,
     * so its own completion callback always arrives.
     */
    java.util.concurrent.Executor io() {
        return r -> {
            if (!lanes.control(r)) new Thread(r, "p71-forecast").start();
        };
    }

    private JSONObject json(String url, boolean auth) throws IOException {
        byte[] b = fetch(url, MAX_JSON, auth);
        try {
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (o.optInt("retCode", -1) != 0) throw new IOException("server error");
            return o;
        } catch (org.json.JSONException e) {
            throw new IOException("bad reply");
        }
    }

    private byte[] fetch(String url, int max, boolean auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            if (auth && token != null) c.setRequestProperty("token", token);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long len = c.getContentLengthLong();
            if (len > max) throw new IOException("too large");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) throw new IOException("too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
