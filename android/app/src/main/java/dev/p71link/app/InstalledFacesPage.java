package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;

import dev.p71link.protocol.BulkProtocol;

import java.util.List;

/** Faces installed on the watch, as the watch lists them. Selection is confirmed by readback. */
final class InstalledFacesPage implements MainActivity.Page {
    private String message;
    private boolean error, busy, loading;

    @Override
    public void onShown(MainActivity a) {
        if (a.watch.connected() && a.watch.faces == null && a.watch.bulkAvailable()) load(a);
    }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadFaces(new WatchManager.Result<List<BulkProtocol.InstalledFace>>() {
            @Override public void ok(List<BulkProtocol.InstalledFace> v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Built-in faces"));
        page.addView(Ui.subtitle(a, "Faces stored on your watch."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to see its faces.", Ui.TEXT2));
            return page;
        }
        if (!w.bulkAvailable()) {
            page.addView(Ui.status(a, "This connection does not offer the face channel. Reconnect and try again.", Ui.ERROR));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading faces…", Ui.TEXT2));
        List<BulkProtocol.InstalledFace> faces = w.faces;
        if (faces != null) {
            if (faces.isEmpty()) page.addView(Ui.status(a, "The watch reported no installed faces.", Ui.TEXT2));
            int current = BulkProtocol.currentRow(faces, w.faceIndex);
            if (current < 0) page.addView(Ui.status(a, "The watch did not report which face is current.", Ui.TEXT2));
            for (int i = 0; i < faces.size(); i++) {
                final int row = i;
                BulkProtocol.InstalledFace f = faces.get(i);
                boolean selected = i == current;
                RadioButton r = new RadioButton(a);
                r.setChecked(selected);
                r.setClickable(false);
                r.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
                r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                Ui.add(page, Ui.row(a, FacesPage.pretty(f.name), selected ? "Current face" : null, r,
                        busy || selected ? null : v -> select(a, row, f.name)), i == 0 ? 0 : 10);
            }
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (!loading) Ui.add(page, Ui.button(a, "Read again", false, v -> load(a)), 16);
        page.addView(Ui.note(a, "The list and its order come from the watch. The current face comes from the watch's face number."));
        return page;
    }

    private void select(MainActivity a, int row, String name) {
        busy = true;
        message = "Switching…";
        error = false;
        a.render();
        a.watch.selectFace(row, new WatchManager.Result<Integer>() {
            @Override public void ok(Integer v) {
                busy = false;
                // The watch's own face number, read back after the write, confirmed this row. The cached
                // list order stays valid; its flags are delete eligibility, so no list reload is needed.
                message = FacesPage.pretty(name) + " is now selected on the watch.";
                a.render();
            }

            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
