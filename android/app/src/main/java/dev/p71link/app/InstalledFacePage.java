package dev.p71link.app;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.FaceChoice;

import java.util.List;

/**
 * One installed face: larger verified artwork or an honest placeholder, and Apply. Apply uses the watch's
 * select command for this list position, then an independent face-number readback confirms it.
 */
final class InstalledFacePage implements MainActivity.Page {
    private final FaceChoice choice;
    private String message;
    private boolean error;
    private MainActivity shown;
    private final Runnable previews = () -> { if (shown != null) shown.render(); };

    InstalledFacePage(FaceChoice choice) { this.choice = choice; }

    @Override
    public void onShown(MainActivity a) {
        shown = a;
        FaceCatalog.listen(previews);
    }

    @Override
    public void onHidden(MainActivity a) {
        FaceCatalog.unlisten(previews);
        shown = null;
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        String title = FaceUi.installedTitle(choice.row);
        page.addView(Ui.title(a, title));
        List<BulkProtocol.InstalledFace> faces = w.faces;
        int row = choice.resolve(faces);
        if (row < 0) {
            page.addView(Ui.subtitle(a, "This face is not in the list the app holds now."));
            page.addView(Ui.status(a, w.faceListOp.active() ? "Reading faces from the watch…"
                    : "The watch disconnected, or its face list was read again and changed. Go back and choose the face again.", Ui.TEXT2));
            return page;
        }
        BulkProtocol.InstalledFace f = faces.get(row);
        int current = BulkProtocol.currentRow(faces, w.faceIndex);
        boolean isCurrent = row == current;
        page.addView(Ui.subtitle(a, "On your watch · position " + (row + 1) + " of " + faces.size()));

        FaceUi.Art art = FaceUi.installedArt(w, f.name);
        FaceArtView big = new FaceArtView(a);
        big.setArt(art.bitmap, art.placeholder);
        big.setCurrent(isCurrent);
        big.setMaxWidth(Ui.dp(a, 260));
        LinearLayout frame = Ui.card(a, Ui.HERO);
        frame.setGravity(Gravity.CENTER_HORIZONTAL);
        frame.addView(big);
        android.widget.TextView caption = Ui.text(a, art.catalog
                ? "Catalog preview: artwork from the watch maker's catalog for this exact file. Numbers in it are samples, not live data."
                : art.detail, 13, Ui.TEXT2);
        caption.setPadding(0, Ui.dp(a, 10), 0, 0);
        frame.addView(caption);
        frame.setContentDescription(title + ". " + (art.catalog ? "Catalog preview image, not live data." : art.placeholder + ". " + art.detail));
        Ui.add(page, frame, 0);

        String state;
        if (isCurrent) state = "The watch reports this as the current face.";
        else if (current < 0) state = "The current face is unknown until the watch reports its face number.";
        else state = "The watch reports " + FaceUi.installedTitle(current) + " as current.";
        page.addView(Ui.status(a, state, Ui.TEXT2));

        boolean usable = w.connected() && w.bulkAvailable() && !w.faceBusy() && !isCurrent;
        if (w.faceSelectOp.active()) page.addView(Ui.status(a, "Switching… waiting for the watch to report its face number.", Ui.TEXT2));
        else if (w.faceUpload.active() || w.faceListOp.active()) page.addView(Ui.status(a, "The watch is busy with faces. Apply when it finishes.", Ui.TEXT2));
        Ui.add(page, FaceUi.action(a, isCurrent ? "This is the current face" : "Apply to watch", true,
                usable ? v -> apply(a, title) : null), 8);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));

        if (FaceCatalog.loaded() == null || FaceCatalog.loaded().anyPreviewFailed()) {
            Ui.add(page, FaceUi.catalogControl(a, faces), 16);
        }

        page.addView(Ui.section(a, "Details"));
        page.addView(FaceUi.details(a, new String[][]{
                {"Position on watch", (row + 1) + " of " + faces.size()},
                {"File on watch", f.name == null || f.name.isEmpty() ? "No name reported" : f.name},
                {"Delete allowed", (f.deletable ? "Yes" : "No") + ", as flagged by the watch"},
                {"Current face", isCurrent ? "Yes, by face number readback" : current < 0 ? "Unknown" : "No"},
                {"Preview", art.detail},
        }));
        page.addView(Ui.note(a, "Apply selects this list position on the watch and then reads the face number back. "
                + "The delete flag only says whether the watch allows deleting; P71 Link does not delete faces."));
        return page;
    }

    private void apply(MainActivity a, String title) {
        WatchManager w = a.watch;
        // Resolve the pick again at the moment of the click: the list may have been read again since.
        int row = choice.resolve(w.faces);
        if (row < 0) {
            message = "The face list changed. Go back and choose the face again.";
            error = true;
            a.render();
            return;
        }
        if (w.faceBusy()) return;
        message = null;
        error = false;
        w.selectFace(row, new WatchManager.Result<Integer>() {
            @Override public void ok(Integer v) {
                message = title + " is now current. The watch confirmed it by reporting its face number.";
                error = false;
                a.render();
            }

            @Override public void fail(String m) {
                message = m + (a.watch.faceIndex == null ? " The current face is unknown until the watch is read again." : "");
                error = true;
                a.render();
            }
        });
        a.render();
    }
}
