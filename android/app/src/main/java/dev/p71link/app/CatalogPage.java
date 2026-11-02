package dev.p71link.app;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import dev.p71link.protocol.ResourceTransfer;

import java.util.List;
import java.util.Locale;

/** Optional catalog of free faces made for this watch's hardware. Uses the internet only here. */
final class CatalogPage implements MainActivity.Page {
    private static final int PAGE = 20;
    private boolean loading, working;
    private int shown = PAGE;
    private String message;
    private boolean error;
    private int sent, total;

    private static FaceCatalog catalog() {
        P71App app = P71App.get();
        if (app.catalog == null) app.catalog = new FaceCatalog();
        return app.catalog;
    }

    @Override
    public View build(MainActivity a) {
        FaceCatalog cat = catalog();
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Browse watch faces"));
        page.addView(Ui.subtitle(a, "Free faces made for " + FaceCatalog.HARDWARE + " watches."));
        if (cat.faces == null) {
            page.addView(Ui.status(a, "This list comes from the watch maker's public server. Opening it uses the internet. "
                    + "No account is needed and nothing about you is sent.", Ui.TEXT2));
            Ui.add(page, Ui.button(a, loading ? "Loading…" : "Load catalog", true, loading ? null : v -> {
                loading = true;
                message = null;
                a.render();
                cat.load(new FaceCatalog.Result<List<FaceCatalog.Face>>() {
                    @Override public void ok(List<FaceCatalog.Face> f) { loading = false; a.render(); }
                    @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
                });
            }), 12);
        } else {
            page.addView(Ui.status(a, cat.faces.size() + " faces", Ui.TEXT2));
            if (working) {
                page.addView(Ui.status(a, total == 0 ? "Downloading…" : "Sending to the watch… " + sent + " of " + total, Ui.TEXT2));
                ProgressBar p = Ui.progress(a);
                p.setMax(Math.max(total, 1));
                p.setProgress(sent);
                Ui.add(page, p, 4);
            }
            if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
            int n = Math.min(shown, cat.faces.size());
            for (int i = 0; i < n; i++) {
                FaceCatalog.Face f = cat.faces.get(i);
                ImageView img = new ImageView(a);
                int w = Ui.dp(a, 60);
                img.setLayoutParams(new LinearLayout.LayoutParams(w, Math.round(w * 284f / 240f)));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.rounded(Ui.HERO, Ui.dp(a, 8)));
                img.setClipToOutline(true);
                img.setContentDescription("Preview of face " + (i + 1));
                Bitmap bm = cat.cachedPreview(f.previewUrl);
                if (bm != null) img.setImageBitmap(bm);
                else cat.preview(f.previewUrl, new FaceCatalog.Result<Bitmap>() {
                    @Override public void ok(Bitmap b) {
                        if (img.isAttachedToWindow()) img.setImageBitmap(b);
                        else a.render(); // The page was rebuilt; the cached preview shows on the new view.
                    }
                    @Override public void fail(String m) { }
                });
                String title = "Face " + (i + 1);
                Ui.add(page, Ui.row(a, title, "Tap to send to the watch", img, working ? null : v -> choose(a, f, title)), i == 0 ? 8 : 8);
            }
            if (n < cat.faces.size()) Ui.add(page, Ui.button(a, "Show more", false, v -> { shown += PAGE; a.render(); }), 12);
        }
        if (cat.faces == null && message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "Catalog order is not the order of faces on the watch. The watch may ask you to pick a new face after it arrives."));
        return page;
    }

    private void choose(MainActivity a, FaceCatalog.Face f, String title) {
        if (!a.watch.connected() || !a.watch.bulkAvailable()) {
            message = "Connect the watch first.";
            error = true;
            a.render();
            return;
        }
        a.confirm("Send " + title + "?", "The face is downloaded and sent to the watch unchanged. "
                + "The upload cannot be undone from the phone.", "Send", () -> {
            working = true;
            sent = 0;
            total = 0;
            message = null;
            a.render();
            catalog().download(f, new FaceCatalog.Result<byte[]>() {
                @Override public void ok(byte[] data) {
                    total = (data.length + ResourceTransfer.BLOCK - 1) / ResourceTransfer.BLOCK;
                    a.render();
                    a.watch.upload(ResourceTransfer.Kind.VENDOR_FACE, f.name, data, (d, n) -> {
                        sent = d;
                        total = n;
                        a.render();
                    }, new WatchManager.Result<Void>() {
                        @Override public void ok(Void v) {
                            working = false;
                            a.watch.faces = null; // Read the installed list again next time.
                            message = String.format(Locale.getDefault(), "The watch accepted %s. Check the watch; you may need to choose it.", title);
                            error = false;
                            a.render();
                        }

                        @Override public void fail(String m) {
                            working = false;
                            message = m + " The watch may keep part of the file.";
                            error = true;
                            a.render();
                        }
                    });
                }

                @Override public void fail(String m) { working = false; message = m; error = true; a.render(); }
            });
        });
    }
}
