package dev.p71link.app;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import dev.p71link.protocol.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional weather for the watch. The user enters a place; the app asks the watch maker's forecast
 * service only when the user taps Get forecast. Server values are shown and sent unchanged. A record
 * missing a required field is left out, never filled in. Nothing refreshes in the background.
 */
final class WeatherPage implements MainActivity.Page {
    private static final String KEY = "weatherPlace";
    private List<Settings.WeatherDay> days;
    private int skipped;
    private long fetchedAt;
    private boolean busy;
    private String message;
    private boolean error;

    private static JSONObject place(Store s) {
        try {
            String raw = s.get(KEY);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public View build(MainActivity a) {
        JSONObject p = place(a.store);
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Weather"));
        page.addView(Ui.subtitle(a, "Send a forecast to the watch when you choose. This uses the internet."));
        String where = p.optString("city", "");
        String coords = p.has("lat") ? String.format(Locale.ROOT, "%.3f, %.3f", p.optDouble("lat"), p.optDouble("lon")) : "";
        Ui.add(page, Ui.row(a, "Place", where.isEmpty() ? "Not set" : where + " · " + coords, null, v -> editPlace(a, p)), 0);
        if (days != null) {
            page.addView(Ui.section(a, "Forecast from the service, in its order"));
            for (int i = 0; i < days.size(); i++) {
                Settings.WeatherDay d = days.get(i);
                Ui.add(page, Ui.row(a, "Entry " + (i + 1), "Low " + d.low + "°, high " + d.high + "° · humidity " + d.humidity
                        + " · " + (d.umbrella ? "umbrella advised" : "no umbrella advised") + " · type code " + d.weatherType, null, null), i == 0 ? 0 : 8);
            }
            if (skipped > 0) page.addView(Ui.note(a, skipped + " entries were incomplete or had values the watch cannot take exactly, and are left out."));
            page.addView(Ui.note(a, "Fetched " + HealthPage.when(fetchedAt) + ". Temperatures are the service's values; the unit is not stated by the service."));
        }
        Ui.add(page, Ui.buttonPair(a,
                Ui.button(a, busy ? "Working…" : "Get forecast", true, busy || where.isEmpty() ? null : v -> fetch(a, p)),
                Ui.button(a, "Send to watch", false, busy || days == null || days.isEmpty() ? null : v -> send(a))), 16);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "The place you type is sent to the watch maker's forecast service. Nothing else about you is sent."));
        return page;
    }

    private void editPlace(MainActivity a, JSONObject p) {
        EditText city = new EditText(a), lat = new EditText(a), lon = new EditText(a);
        city.setHint("City");
        city.setText(p.optString("city", ""));
        lat.setHint("Latitude, for example 51.507");
        lon.setHint("Longitude, for example -0.128");
        int num = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        lat.setInputType(num);
        lon.setInputType(num);
        if (p.has("lat")) lat.setText(String.valueOf(p.optDouble("lat")));
        if (p.has("lon")) lon.setText(String.valueOf(p.optDouble("lon")));
        LinearLayout box = Ui.column(a);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(city);
        box.addView(lat);
        box.addView(lon);
        a.dialog().setTitle("Place").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            try {
                double la = Double.parseDouble(lat.getText().toString().trim());
                double lo = Double.parseDouble(lon.getText().toString().trim());
                String c = city.getText().toString().trim();
                if (c.isEmpty() || la < -90 || la > 90 || lo < -180 || lo > 180) throw new NumberFormatException();
                a.store.put(KEY, new JSONObject().put("city", c).put("lat", la).put("lon", lo).toString());
                days = null;
                message = null;
            } catch (NumberFormatException | org.json.JSONException e) {
                message = "Enter a city and valid coordinates.";
                error = true;
            }
            a.render();
        }).show();
    }

    private void fetch(MainActivity a, JSONObject p) {
        busy = true;
        message = null;
        a.render();
        P71App app = P71App.get();
        if (app.catalog == null) app.catalog = new FaceCatalog();
        FaceCatalog api = app.catalog;
        api.io().execute(() -> {
            List<Settings.WeatherDay> out = new ArrayList<>();
            int bad = 0;
            String fail = null;
            try {
                JSONObject body = new JSONObject().put("longitude", p.getDouble("lon")).put("latitude", p.getDouble("lat"))
                        .put("language", "en").put("city", p.getString("city"));
                JSONArray list = api.postJson("weather-com/five-days-forecastAndroid", body).optJSONArray("data");
                if (list == null) throw new java.io.IOException("no list");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    try {
                        if (o == null || !(o.opt("timeStamp") instanceof Number) || !(o.opt("digitType") instanceof Number)
                                || !(o.opt("lowTemp") instanceof Number) || !(o.opt("highTemp") instanceof Number)
                                || !(o.opt("humidity") instanceof Number) || !(o.opt("isBringUmbrella") instanceof Boolean)) {
                            throw new IllegalArgumentException("incomplete");
                        }
                        // Original numbers are checked as exact integers in range before any narrowing.
                        out.add(Settings.WeatherDay.fromNumbers((Number) o.get("timeStamp"), (Number) o.get("digitType"),
                                (Number) o.get("lowTemp"), (Number) o.get("highTemp"), (Number) o.get("humidity"),
                                o.getBoolean("isBringUmbrella")));
                    } catch (IllegalArgumentException | org.json.JSONException e) {
                        bad++;
                    }
                }
            } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                fail = "The forecast service could not be reached or sent an unexpected reply. Nothing was sent to the watch.";
            }
            final String f = fail;
            final int skip = bad;
            a.runOnUiThread(() -> {
                busy = false;
                if (f != null) {
                    message = f;
                    error = true;
                } else {
                    days = out;
                    skipped = skip;
                    fetchedAt = System.currentTimeMillis();
                    message = out.isEmpty() ? "The service returned no usable forecast entries." : null;
                    error = out.isEmpty();
                }
                a.render();
            });
        });
    }

    private void send(MainActivity a) {
        if (!a.watch.connected()) {
            message = "Connect the watch first.";
            error = true;
            a.render();
            return;
        }
        busy = true;
        a.render();
        a.watch.sendWeather(days, new WatchManager.Result<Integer>() {
            @Override public void ok(Integer n) {
                busy = false;
                message = "The watch accepted " + n + " forecast entries. Check the weather screen on the watch.";
                error = false;
                a.render();
            }

            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
