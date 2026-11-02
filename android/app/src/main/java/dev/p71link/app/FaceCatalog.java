package dev.p71link.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        final String name, binUrl, previewUrl;
        final int typeId;

        Face(String name, String binUrl, String previewUrl, int typeId) {
            this.name = name;
            this.binUrl = binUrl;
            this.previewUrl = previewUrl;
            this.typeId = typeId;
        }
    }

    interface Result<T> {
        void ok(T v);

        void fail(String m);
    }

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> previews = new LruCache<>(48);
    private volatile String token;
    private final java.util.Set<String> loadingPreviews = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    List<Face> faces;

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

    void load(Result<List<Face>> r) {
        io.execute(() -> {
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
                    String name = bin.substring(bin.lastIndexOf('/') + 1);
                    out.add(new Face(name, bin, safeFileUrl(o.optString("preImageUrl", null)), o.optInt("typeId")));
                }
                List<Face> result = Collections.unmodifiableList(out);
                main.post(() -> { faces = result; r.ok(result); });
            } catch (IOException | RuntimeException e) {
                token = null;
                main.post(() -> r.fail("The face catalog could not be reached. Check the internet connection and try again."));
            }
        });
    }

    Bitmap cachedPreview(String url) { return url == null ? null : previews.get(url); }

    void preview(String url, Result<Bitmap> r) {
        if (url == null || !loadingPreviews.add(url)) return;
        io.execute(() -> {
            try {
                byte[] b = fetch(url, 512 * 1024, false);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm == null) throw new IOException("not an image");
                previews.put(url, bm);
                main.post(() -> r.ok(bm));
            } catch (IOException | RuntimeException e) {
                main.post(() -> r.fail("Preview unavailable"));
            } finally {
                loadingPreviews.remove(url);
            }
        });
    }

    void download(Face f, Result<byte[]> r) {
        io.execute(() -> {
            try {
                byte[] b = fetch(f.binUrl, dev.p71link.protocol.BulkProtocol.MAX_FACE_FILE, false);
                if (b.length < 16) throw new IOException("too small");
                main.post(() -> r.ok(b));
            } catch (IOException | RuntimeException e) {
                main.post(() -> r.fail("The face could not be downloaded. Nothing was sent to the watch."));
            }
        });
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

    ExecutorService io() { return io; }

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
