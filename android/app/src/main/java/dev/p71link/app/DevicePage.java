package dev.p71link.app;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Device information and local data export. */
final class DevicePage implements MainActivity.Page {
    private String message;
    private boolean error;

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Data and device"));
        page.addView(Ui.subtitle(a, "Your data stays on this phone unless you export it."));
        Ui.add(page, Ui.row(a, "Watch", w.deviceName == null ? "None saved" : w.deviceName, null, null), 0);
        Ui.add(page, Ui.row(a, "Firmware", w.firmware == null ? "Not read yet" : w.firmware, null, null), 10);
        if (w.caps != null) {
            Ui.add(page, Ui.row(a, "Screen", w.caps.displayWidth + " × " + w.caps.displayHeight + " pixels", null, null), 10);
            StringBuilder f = new StringBuilder();
            if (w.caps.watchFaces) f.append("Faces · ");
            if (w.caps.customWallpaper) f.append("Photo face · ");
            if (w.caps.manualHeartRate) f.append("Heart rate · ");
            if (w.caps.bloodPressure) f.append("Blood pressure · ");
            if (w.caps.weather) f.append("Weather · ");
            if (w.caps.contacts) f.append("Contacts · ");
            if (w.caps.menstruation) f.append("Cycle · ");
            String list = f.length() > 3 ? f.substring(0, f.length() - 3) : "None reported";
            Ui.add(page, Ui.row(a, "Reported features", list, null, null), 10);
        }
        Ui.add(page, Ui.row(a, "App", "P71 Link development build", null, null), 10);
        Ui.add(page, Ui.button(a, "Export my data", true, v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "p71link-export.json");
            a.startActivityForResult(i, MainActivity.EXPORT);
        }), 16);
        Ui.add(page, Ui.button(a, "Delete health data on this phone", false, v -> a.confirm("Delete health data?",
                "Saved readings on this phone are deleted. The watch keeps its own data.", "Delete", () -> {
                    boolean ok = P71App.get().health.clear();
                    message = ok ? "Health data on this phone was deleted." : "Health data could not be deleted.";
                    error = !ok;
                    a.render();
                })), 10);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        return page;
    }

    @Override
    public void onActivityResult(MainActivity a, int request, int result, Intent data) {
        if (request != MainActivity.EXPORT || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri target = data.getData();
        message = "Exporting…";
        error = false;
        a.watch.diagnostics(new WatchManager.Result<JSONObject>() {
            @Override public void ok(JSONObject diag) { write(a, target, diag); }
            @Override public void fail(String m) { write(a, target, null); }
        });
    }

    private void write(MainActivity a, android.net.Uri target, JSONObject diag) {
        try (OutputStream out = a.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new java.io.IOException("No output");
            JSONObject o = export(a);
            o.put("connectionDiagnostics", diag == null ? "unavailable" : diag);
            out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            message = "Exported.";
            error = false;
        } catch (Exception e) {
            message = "The export could not be written.";
            error = true;
        }
        a.render();
    }

    private JSONObject export(MainActivity a) throws JSONException {
        WatchManager w = a.watch;
        JSONObject o = new JSONObject();
        o.put("app", "P71 Link");
        o.put("exportedAt", System.currentTimeMillis());
        JSONObject dev = new JSONObject();
        dev.put("name", w.deviceName);
        dev.put("firmware", w.firmware);
        if (w.caps != null) {
            JSONObject c = new JSONObject();
            for (Map.Entry<String, Object> e : w.caps.toMap().entrySet()) {
                if (e.getKey().contains("oxygen")) continue; // Oxygen is excluded from this app by the user.
                c.put(e.getKey(), e.getValue());
            }
            dev.put("reportedCapabilities", c);
        }
        o.put("device", dev);
        JSONArray records = new JSONArray();
        for (JSONObject r : P71App.get().health.all()) {
            // Oxygen is excluded from this app; older local records of that kind are not exported.
            if (r.optString("kind").startsWith("spo2")) continue;
            records.put(r);
        }
        o.put("healthRecords", records);
        o.put("note", "Records hold values from watch replies. Each record names its source. Fields ending in Raw keep "
                + "watch counters whose units are not confirmed. Fields described as sums or calculated were computed on "
                + "this phone from watch records. Dates marked as phone dates come from this phone, not the watch. "
                + "Missing fields were not reported. These are not medical measurements.");
        return o;
    }
}
