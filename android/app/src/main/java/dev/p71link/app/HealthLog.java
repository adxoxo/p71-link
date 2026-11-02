package dev.p71link.app;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Private on-phone record of values derived from watch replies. One JSON object per line.
 * Every record keeps its source. Writes report success; callers claim "saved" only on true.
 * Missing fields stay missing; readers must not substitute zero.
 */
final class HealthLog {
    private final File file;

    HealthLog(Context c) { file = new File(c.getFilesDir(), "health.jsonl"); }

    /** Append one record. Returns false when it was not stored. */
    synchronized boolean add(String kind, long timeMs, JSONObject values, String source) {
        try {
            String line = record(kind, null, timeMs, values, source).toString() + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            return true;
        } catch (JSONException | IOException e) {
            return false;
        }
    }

    /** Replace the record with the same kind and key, atomically. Returns false when nothing changed on disk. */
    synchronized boolean upsert(String kind, String key, long timeMs, JSONObject values, String source) {
        StringBuilder out = new StringBuilder();
        for (JSONObject o : all()) {
            if (kind.equals(o.optString("kind")) && key.equals(o.optString("key"))) continue;
            out.append(o.toString()).append('\n');
        }
        File tmp = new File(file.getPath() + ".tmp");
        try {
            out.append(record(kind, key, timeMs, values, source).toString()).append('\n');
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(out.toString().getBytes(StandardCharsets.UTF_8));
                f.getFD().sync();
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (JSONException | IOException | RuntimeException e) {
            tmp.delete();
            return false;
        }
    }

    private static JSONObject record(String kind, String key, long timeMs, JSONObject values, String source) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", kind);
        if (key != null) o.put("key", key);
        o.put("time", timeMs);
        o.put("source", source);
        o.put("values", values);
        return o;
    }

    synchronized List<JSONObject> all() {
        if (!file.exists()) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (JSONException ignored) {
                    // A damaged line is skipped, never repaired with guessed values.
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        return out;
    }

    List<JSONObject> ofKind(String kind) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all()) if (kind.equals(o.optString("kind"))) out.add(o);
        return out;
    }

    JSONObject latest(String kind) {
        List<JSONObject> list = all();
        for (int i = list.size() - 1; i >= 0; i--) if (kind.equals(list.get(i).optString("kind"))) return list.get(i);
        return null;
    }

    synchronized boolean clear() { return !file.exists() || file.delete(); }

    // ---- Presence-checked reads: a missing or non-numeric field is unavailable, never zero. ----

    static boolean hasNumber(JSONObject o, String key) {
        return o != null && o.has(key) && !o.isNull(key) && o.opt(key) instanceof Number;
    }

    /** The stored number as text, or null when it is missing or malformed. */
    static String number(JSONObject o, String key) {
        if (!hasNumber(o, key)) return null;
        Number n = (Number) o.opt(key);
        return String.format(java.util.Locale.getDefault(), "%,d", n.longValue());
    }

    static String numberOr(JSONObject o, String key, String unavailable) {
        String n = number(o, key);
        return n == null ? unavailable : n;
    }
}
