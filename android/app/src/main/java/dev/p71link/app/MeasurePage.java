package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

import dev.p71link.protocol.Health;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Manual measurement. The screen shows the exact value the watch sent, or an honest failure.
 * No estimated or substitute value is ever shown or stored.
 */
final class MeasurePage implements MainActivity.Page {
    private final int type;
    private boolean measuring;
    private String result, message;
    private boolean error;

    MeasurePage(int type) {
        if (type != Health.TYPE_HEART_RATE) throw new IllegalArgumentException("Heart rate only");
        this.type = type;
    }

    private String name() { return "Heart rate"; }

    private String kind() { return "heart_rate"; }

    private String unit() { return " bpm"; }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, name()));
        page.addView(Ui.subtitle(a, "Wear the watch snugly and keep still while it measures."));
        LinearLayout card = Ui.card(a, Ui.HERO);
        card.addView(Ui.text(a, measuring ? "Measuring…" : result != null ? result : "—", 40, Ui.TEXT));
        card.addView(Ui.text(a, measuring ? "This can take up to 45 seconds." : "Value from the watch sensor", 13, Ui.TEXT2));
        Ui.add(page, card, 0);
        if (!measuring) {
            Ui.add(page, Ui.button(a, "Measure now", true, v -> {
                if (!w.connected()) {
                    message = "Connect the watch first.";
                    error = true;
                    a.render();
                    return;
                }
                measuring = true;
                result = null;
                message = null;
                a.render();
                w.measure(type, new WatchManager.Result<Health.MeasurementUpdate>() {
                    @Override public void ok(Health.MeasurementUpdate u) { finished(a, u); }
                    @Override public void fail(String m) { measuring = false; message = m; error = true; a.render(); }
                });
            }), 16);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.section(a, "Saved on this phone"));
        List<JSONObject> all = P71App.get().health.all();
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 20; i--) {
            JSONObject o = all.get(i);
            if (!kind().equals(o.optString("kind"))) continue;
            String v = HealthLog.number(o.optJSONObject("values"), "value");
            Ui.add(page, Ui.row(a, v == null ? "Value unavailable" : v + unit(), HealthPage.when(o.optLong("time")), null, null), shown == 0 ? 0 : 8);
            shown++;
        }
        if (shown == 0) page.addView(Ui.status(a, "No measurements yet.", Ui.TEXT2));
        page.addView(Ui.note(a, "A watch reading is not a medical measurement."));
        return page;
    }

    private void finished(MainActivity a, Health.MeasurementUpdate u) {
        measuring = false;
        Health.Outcome o = u == null ? Health.Outcome.PENDING : u.outcome();
        switch (o) {
            case MEASURED:
                result = u.value + unit();
                message = null;
                error = false;
                try {
                    JSONObject v = new JSONObject().put("value", u.value);
                    if (!P71App.get().health.add(kind(), System.currentTimeMillis(), v, "manual measurement")) {
                        message = "The reading could not be saved on this phone.";
                        error = true;
                    }
                } catch (JSONException ignored) {
                    // Not stored.
                }
                break;
            case RAW_UNVERIFIED:
                // The unit of this reply is not established. Show it as raw, never as a percentage, never save it.
                result = null;
                message = "The watch sent a value this app cannot confirm. Nothing was saved.";
                error = false;
                break;
            case FAILED:
                message = "The watch could not complete the measurement. Check the fit and try again.";
                error = true;
                break;
            case IMPOSSIBLE:
                message = "The watch sent a value outside the possible range. It was not saved.";
                error = true;
                break;
            default:
                message = "The watch sent no result.";
                error = true;
        }
        a.render();
    }
}
