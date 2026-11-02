package dev.p71link.app;

import android.app.TimePickerDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.p71link.protocol.HeatIndex;
import dev.p71link.protocol.Notifications;
import dev.p71link.protocol.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Davao City heat index at the top: worked out from a fresh Open-Meteo request at each send and sent
 * as a text message, with an optional auto send at chosen times (see HeatIndexScheduler).
 *
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
    // Heat index: values exist only after a real fetch on this screen; nothing is shown from memory.
    private HeatIndex.Reading heat;
    private long heatAt;
    private boolean heatBusy, heatError;
    private String heatMessage;

    private static JSONObject place(Store s) {
        try {
            String raw = s.get(KEY);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public void onShown(MainActivity a) { HeatIndexScheduler.arm(a); } // Sets the alarm again after a restart.

    @Override
    public View build(MainActivity a) {
        JSONObject p = place(a.store);
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Weather"));
        heatSection(a, page);
        page.addView(Ui.section(a, "Watch maker forecast"));
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

    // ---- Davao City heat index ----------------------------------------------------------------

    private void heatSection(MainActivity a, LinearLayout page) {
        page.addView(Ui.section(a, HeatIndex.PLACE + " heat index"));
        if (heat == null) {
            Ui.add(page, Ui.row(a, "Not fetched yet", "Send now or Refresh gets today's values", null, null), 0);
        } else {
            Ui.add(page, Ui.row(a, "Now", HeatIndex.levelText(heat.nowC), value(a, heat.nowC + "°C"), null), 0);
            Ui.add(page, Ui.row(a, "Peak", "at " + HeatIndex.hourLabel(heat.peakHour, true) + " · " + HeatIndex.levelText(heat.peakC),
                    value(a, heat.peakC + "°C"), null), 8);
            java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(heatAt).atZone(HeatIndex.ZONE);
            Ui.add(page, Ui.row(a, "Source", "Open-Meteo · " + HeatIndex.clock12(t.getHour() * 60 + t.getMinute()), null, null), 8);
        }
        Ui.add(page, Ui.buttonPair(a,
                Ui.button(a, heatBusy ? "Working…" : "Send now", true, heatBusy ? null : v -> heatFetch(a, true)),
                Ui.button(a, "Refresh", false, heatBusy ? null : v -> heatFetch(a, false))), 12);
        if (heatMessage != null) page.addView(Ui.status(a, heatMessage, heatError ? Ui.ERROR : Ui.TEXT2));

        boolean on = HeatIndexScheduler.enabled(a);
        Switch auto = Ui.toggle(a, on, true, "Auto send");
        auto.setOnCheckedChangeListener((b, checked) -> { HeatIndexScheduler.setEnabled(a, checked); a.render(); });
        Ui.add(page, Ui.row(a, "Auto send", "At the times below, when the watch is connected", auto, null), 12);
        StringBuilder times = new StringBuilder();
        for (int m : HeatIndexScheduler.times(a)) times.append(times.length() == 0 ? "" : "  ").append(HeatIndex.clock(m));
        Ui.add(page, Ui.row(a, "Times", times.toString(), Ui.text(a, "Edit", 14, Ui.ACCENT), v -> editTimes(a)), 8);
        String last = HeatIndexScheduler.lastResult(a);
        page.addView(Ui.note(a, last == null ? "Last auto send: none yet."
                : "Last auto send: " + HeatIndexScheduler.slotLabel(HeatIndexScheduler.lastSlot(a)) + " " + last));
        page.addView(Ui.note(a, "Uses the internet at each send. Only Davao City's fixed location is sent to Open-Meteo. "
                + "Values are worked out from Open-Meteo's temperature and humidity forecast; they are not PAGASA readings."));
    }

    private static TextView value(MainActivity a, String s) {
        TextView t = Ui.text(a, s, 20, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A fresh request; with {@code send}, the new values go to the watch. Nothing older is ever sent. */
    private void heatFetch(MainActivity a, boolean send) {
        if (send && !a.watch.connected()) {
            heatMessage = "Connect the watch first.";
            heatError = true;
            a.render();
            return;
        }
        heatBusy = true;
        heatMessage = null;
        a.render();
        HeatIndexScheduler.fetch((reading, err) -> {
            if (err != null) {
                heatBusy = false;
                heat = null; // A failed request leaves no values on screen.
                heatMessage = "Could not get the heat index: " + err + "." + (send ? " Nothing was sent to the watch." : "");
                heatError = true;
                a.render();
                return;
            }
            heat = reading;
            heatAt = System.currentTimeMillis();
            if (!send) {
                heatBusy = false;
                a.render();
                return;
            }
            a.watch.notifyText(reading.message(), Notifications.SMS, new WatchManager.Result<Void>() {
                @Override public void ok(Void v) {
                    heatBusy = false;
                    heatMessage = "The watch received it. Check that the text appears.";
                    heatError = false;
                    a.render();
                }

                @Override public void fail(String m) { heatBusy = false; heatMessage = m; heatError = true; a.render(); }
            });
            a.render();
        });
    }

    /** Edit a working copy; Save keeps 1 to 8 distinct times, sorted, and sets the alarm again. */
    private void editTimes(MainActivity a) {
        java.util.List<Integer> work = new ArrayList<>(HeatIndexScheduler.times(a));
        LinearLayout box = Ui.column(a);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        LinearLayout list = Ui.column(a);
        box.addView(list);
        Runnable[] refresh = new Runnable[1];
        TextView add = Ui.link(a, "Add a time", v -> pickTime(a, 12 * 60, m -> {
            if (work.contains(m)) { a.toast("That time is already in the list."); return; }
            work.add(m);
            refresh[0].run();
        }));
        box.addView(add);
        refresh[0] = () -> {
            java.util.List<Integer> sorted = HeatIndex.normalize(work);
            work.clear();
            work.addAll(sorted);
            list.removeAllViews();
            for (int idx = 0; idx < work.size(); idx++) {
                final int i = idx, m = work.get(idx);
                LinearLayout r = new LinearLayout(a);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.addView(Ui.text(a, HeatIndex.clock(m), 18, Ui.TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                r.addView(Ui.link(a, "Change", v -> pickTime(a, m, nm -> {
                    if (nm != m && work.contains(nm)) { a.toast("That time is already in the list."); return; }
                    work.set(i, nm);
                    refresh[0].run();
                })));
                TextView remove = Ui.link(a, "Remove", v -> { work.remove(i); refresh[0].run(); });
                enable(remove, work.size() > HeatIndex.MIN_TIMES);
                r.addView(remove);
                list.addView(r);
            }
            enable(add, work.size() < HeatIndex.MAX_TIMES);
        };
        refresh[0].run();
        a.dialog().setTitle("Auto send times (Davao time)").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    HeatIndexScheduler.setTimes(a, work);
                    a.render();
                }).show();
    }

    private static void enable(TextView t, boolean on) {
        t.setEnabled(on);
        t.setClickable(on);
        t.setAlpha(on ? 1f : 0.4f);
    }

    private static void pickTime(MainActivity a, int minutes, java.util.function.IntConsumer picked) {
        new TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, (tp, h, m) -> picked.accept(h * 60 + m),
                minutes / 60, minutes % 60, true).show();
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
