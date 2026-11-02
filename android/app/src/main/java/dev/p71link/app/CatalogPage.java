package dev.p71link.app;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;

import dev.p71link.protocol.PendingApply;
import dev.p71link.protocol.PreviewLoads;
import dev.p71link.protocol.ResourceTransfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional catalog of free faces made for this watch's hardware. Uses the internet only here. */
final class CatalogPage implements MainActivity.Page {
    /** One page of cards. Paging keeps the visible previews well inside the bounded image cache. */
    private static final int PAGE = 20;
    /** The Apply whose download is running for this showing, or null. */
    private PendingApply.Ticket downloading;
    private int start;
    private String message;
    private boolean error;
    private MainActivity shown;
    private final Runnable previews = () -> { if (shown != null) shown.render(); };
    /**
     * A confirmed Apply waits for its dialog and its download. Leaving or re-showing this page, or any
     * change of watch session, makes it stale; a stale download never starts a transfer.
     */
    private final PendingApply pending = new PendingApply(() -> P71App.get().watch.sessionToken());

    @Override
    public void onShown(MainActivity a) {
        shown = a;
        pending.invalidate();
        downloading = null; // A download from an earlier showing is stale and cannot start a transfer.
        FaceCatalog.listen(previews);
    }

    @Override
    public void onHidden(MainActivity a) {
        pending.invalidate();
        downloading = null;
        FaceCatalog.unlisten(previews);
        FaceCatalog c = P71App.get().catalog;
        if (c != null) c.dropQueuedPreviews();
        shown = null;
    }

    @Override
    public View build(MainActivity a) {
        FaceCatalog cat = FaceCatalog.get();
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Browse watch faces"));
        page.addView(Ui.subtitle(a, "Free faces made for " + FaceCatalog.HARDWARE + " watches."));
        if (cat.faces == null) {
            page.addView(Ui.status(a, "This list comes from the watch maker's public server. Opening it uses the internet. "
                    + "No account is needed and nothing about you is sent.", Ui.TEXT2));
            Ui.add(page, FaceUi.action(a, cat.loading ? "Loading…" : cat.loadError != null ? "Try again" : "Load catalog", true,
                    cat.loading ? null : v -> {
                        message = null;
                        cat.load(new FaceCatalog.Result<List<FaceCatalog.Face>>() {
                            @Override public void ok(List<FaceCatalog.Face> f) { a.render(); }
                            @Override public void fail(String m) { a.render(); }
                        });
                        a.render();
                    }), 12);
            if (cat.loadError != null) page.addView(Ui.status(a, cat.loadError, Ui.ERROR));
        } else {
            int total = cat.faces.size();
            if (start >= total) start = Math.max(0, (total - 1) / PAGE * PAGE);
            int end = Math.min(start + PAGE, total);
            page.addView(Ui.status(a, total == 0 ? "No faces" : String.format(Locale.getDefault(), "Faces %d–%d of %d", start + 1, end, total), Ui.TEXT2));
            if (downloading != null && downloading.current()) page.addView(Ui.status(a, "Downloading…", Ui.TEXT2));
            View progress = FaceUi.transferProgress(a);
            if (progress != null) Ui.add(page, progress, 4);
            if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
            boolean canSend = (downloading == null || !downloading.current()) && !w.faceBusy();
            List<View> cells = new ArrayList<>();
            for (int i = start; i < end; i++) {
                FaceCatalog.Face f = cat.faces.get(i);
                Bitmap bm = cat.preview(f.previewUrl);
                String placeholder = f.previewUrl == null || cat.previewState(f.previewUrl) == PreviewLoads.State.FAILED
                        ? "Preview unavailable" : "Loading preview…";
                FaceArtView art = new FaceArtView(a);
                art.setArt(bm, placeholder);
                String title = "Face " + (i + 1);
                String sub = bm != null ? "Catalog preview · tap to send" : "Tap to send";
                cells.add(FaceUi.card(a, art, title, sub, title + ". " + (bm != null ? "Catalog preview image." : placeholder + ".")
                        + " Opens a send confirmation.", canSend ? v -> choose(a, f, title) : null));
            }
            Ui.add(page, FaceUi.grid(a, cells), 8);
            if (cat.anyPreviewFailed()) {
                Ui.add(page, Ui.button(a, "Retry previews", false, v -> {
                    cat.retryPreviews();
                    a.render();
                }), 12);
            }
            if (total > PAGE) {
                android.widget.Button prev = FaceUi.action(a, "Previous", false, start > 0 ? v -> page(a, -PAGE) : null);
                android.widget.Button next = FaceUi.action(a, "Next", false, end < total ? v -> page(a, PAGE) : null);
                Ui.add(page, Ui.buttonPair(a, prev, next), 12);
            }
        }
        page.addView(Ui.note(a, "Catalog order is not the order of faces on the watch. Catalog artwork may show sample numbers. "
                + "The watch may ask you to pick a new face after it arrives."));
        return page;
    }

    private void page(MainActivity a, int delta) {
        start = Math.max(0, start + delta);
        // Previews queued for the previous page must not delay the new one.
        FaceCatalog.get().dropQueuedPreviews();
        a.render();
    }

    private void choose(MainActivity a, FaceCatalog.Face f, String title) {
        final PendingApply.Ticket ticket = a.watch.bulkAvailable() ? pending.arm() : null;
        if (ticket == null) {
            message = "Connect the watch first.";
            error = true;
            a.render();
            return;
        }
        a.confirm("Send " + title + "?", "The face is downloaded and sent to the watch unchanged. "
                + "The upload cannot be undone from the phone.\n\nFile: " + f.name, "Send", () -> {
            if (!ticket.current()) {
                stale(a);
                return;
            }
            if ((downloading != null && downloading.current()) || a.watch.faceBusy()) return;
            downloading = ticket;
            message = null;
            a.render();
            FaceCatalog.get().download(f, new FaceCatalog.Result<byte[]>() {
                @Override public void ok(byte[] data) {
                    // Checked again on completion: the page or session may have changed during the download.
                    if (downloading == ticket) downloading = null;
                    ticket.proceed(() -> {
                        a.watch.upload(ResourceTransfer.Kind.VENDOR_FACE, f.name, title, ticket.session, data, (d, n) -> a.render(),
                                new WatchManager.Result<Void>() {
                                    @Override public void ok(Void v) {
                                        message = String.format(Locale.getDefault(), "The watch accepted %s. Check the watch; you may need to choose it.", title);
                                        error = false;
                                        a.render();
                                    }

                                    @Override public void fail(String m) {
                                        message = FaceUi.transferFailure(m, "file");
                                        error = true;
                                        a.render();
                                    }
                                });
                        a.render();
                    }, () -> stale(a));
                }

                @Override public void fail(String m) {
                    if (downloading == ticket) downloading = null;
                    if (!ticket.current()) return; // Belongs to an earlier showing or session.
                    message = m;
                    error = true;
                    a.render();
                }
            });
        });
    }

    /** A pending Apply outlived its page showing or session: nothing is sent. */
    private void stale(MainActivity a) {
        message = "Not sent. The page or the watch connection changed before the face was ready. Nothing was sent to the watch.";
        error = true;
        a.render();
    }
}
