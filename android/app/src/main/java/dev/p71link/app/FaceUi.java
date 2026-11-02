package dev.p71link.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.FacePreviews;
import dev.p71link.protocol.PreviewLoads;

import java.util.List;
import java.util.function.IntConsumer;

/** Face picker views from the approved design: two-column cards, tabs, details and catalog preview opt-in. */
final class FaceUi {
    private FaceUi() {}

    /** Readable title of an installed face: its one-based position in watch order. Never the file name. */
    static String installedTitle(int row) { return "Watch face " + (row + 1); }

    // ---- Installed face artwork ----------------------------------------------------------------

    /** What to show for an installed face. Artwork appears only for an exact catalog file-name match. */
    static final class Art {
        final Bitmap bitmap;
        final String placeholder, detail;
        final boolean catalog;

        Art(Bitmap bitmap, String placeholder, String detail) {
            this.bitmap = bitmap;
            this.placeholder = placeholder;
            this.detail = detail;
            this.catalog = bitmap != null;
        }
    }

    /**
     * Resolve artwork for an installed file name. Uses the network only when the catalog list is already
     * loaded and one exact match has an image that was never tried; a failed image stays unavailable
     * until the user retries.
     */
    static Art installedArt(WatchManager w, String name) {
        FaceCatalog cat = FaceCatalog.loaded();
        if (cat == null || cat.index() == null) {
            return new Art(null, "Preview not loaded", "Not loaded. Catalog previews are optional and use the internet.");
        }
        FacePreviews.Match m = cat.index().find(name, w.firmware);
        switch (m.status) {
            case MATCH: {
                Bitmap b = cat.preview(m.previewUrl);
                if (b != null) return new Art(b, null, "Exact catalog file match. Catalog artwork, not live data.");
                if (cat.previewState(m.previewUrl) == PreviewLoads.State.FAILED) {
                    return new Art(null, "Preview unavailable", "Exact catalog file match, but its image could not be loaded.");
                }
                return new Art(null, "Loading preview…", "Exact catalog file match. Loading its image.");
            }
            case AMBIGUOUS:
                return new Art(null, "Preview unavailable", "Several catalog entries share this file name with different images.");
            case NO_IMAGE:
                return new Art(null, "Preview unavailable", "The catalog entry has no usable preview image.");
            case UNNAMED:
                return new Art(null, "Preview unavailable", "The watch reported no file name for this face.");
            case OTHER_HARDWARE:
                return new Art(null, "Preview unavailable", "The catalog is for " + FaceCatalog.HARDWARE + "; this watch reports another model.");
            case NO_MATCH:
            default:
                return new Art(null, "Preview unavailable", "No " + FaceCatalog.HARDWARE + " catalog face has this exact file name.");
        }
    }

    /**
     * The card above the installed faces: the explicit catalog-preview load, its progress, and manual
     * retries. Nothing loads until the user asks, and nothing retries on its own.
     */
    static View catalogControl(MainActivity a, List<BulkProtocol.InstalledFace> faces) {
        LinearLayout box = Ui.card(a, Ui.CARD);
        box.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        FaceCatalog cat = P71App.get().catalog;
        Runnable load = () -> {
            FaceCatalog.get().load(new FaceCatalog.Result<List<FaceCatalog.Face>>() {
                @Override public void ok(List<FaceCatalog.Face> f) { a.render(); }
                @Override public void fail(String m) { a.render(); }
            });
            a.render();
        };
        if (cat == null || cat.faces == null) {
            if (cat != null && cat.loading) {
                box.addView(Ui.text(a, "Loading catalog previews…", 14, Ui.TEXT2));
            } else if (cat != null && cat.loadError != null) {
                box.addView(Ui.text(a, "Catalog previews could not load. Check the internet connection and try again.", 14, Ui.ERROR));
                Ui.add(box, Ui.button(a, "Try again", false, v -> load.run()), 8);
            } else {
                box.addView(Ui.text(a, "The watch sends face names only. Catalog previews use the internet; nothing about you is sent.", 14, Ui.TEXT2));
                Ui.add(box, Ui.button(a, "Load catalog previews", false, v -> load.run()), 8);
            }
            return box;
        }
        int matched = 0;
        if (faces != null && cat.index() != null) {
            for (BulkProtocol.InstalledFace f : faces) {
                if (cat.index().find(f.name, a.watch.firmware).status == FacePreviews.Status.MATCH) matched++;
            }
        }
        int n = faces == null ? 0 : faces.size();
        box.addView(Ui.text(a, matched + " of " + n + " with a catalog preview. Previews are catalog art, not live data.", 14, Ui.TEXT2));
        if (cat.anyPreviewFailed()) {
            box.addView(Ui.text(a, "Some previews could not load.", 14, Ui.TEXT2));
            box.addView(Ui.link(a, "Retry previews", v -> {
                cat.retryPreviews();
                a.render();
            }));
        }
        return box;
    }

    // ---- Views ---------------------------------------------------------------------------------

    /** A two-column card: large image, readable title, short subtitle. */
    static LinearLayout card(Context c, FaceArtView art, String title, String sub, String description, View.OnClickListener click) {
        LinearLayout card = Ui.column(c);
        int p = Ui.dp(c, 10);
        card.setPadding(p, p, p, Ui.dp(c, 12));
        android.graphics.drawable.GradientDrawable bg = Ui.rounded(Ui.CARD, Ui.dp(c, 18));
        card.setBackground(click == null ? bg : new RippleDrawable(ColorStateList.valueOf(0x33C9F57B), bg, null));
        card.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(c, title, 16, Ui.TEXT);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 10), 0, 0);
        card.addView(t);
        if (sub != null) {
            TextView s = Ui.text(c, sub, 13, Ui.TEXT2);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), 0, 0);
            card.addView(s);
        }
        card.setContentDescription(description);
        if (click != null) {
            card.setOnClickListener(click);
            card.setClickable(true);
            card.setFocusable(true);
            Ui.pressable(card);
        }
        return card;
    }

    /** Lay cells out two per row with equal widths. */
    static LinearLayout grid(Context c, List<View> cells) {
        LinearLayout grid = Ui.column(c);
        for (int i = 0; i < cells.size(); i += 2) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            right.leftMargin = Ui.dp(c, 12);
            row.addView(cells.get(i), left);
            if (i + 1 < cells.size()) row.addView(cells.get(i + 1), right);
            else row.addView(new View(c), right);
            Ui.add(grid, row, i == 0 ? 0 : 12);
        }
        return grid;
    }

    /** Two-option tab control. */
    static LinearLayout tabs(Context c, String[] labels, int selected, IntConsumer pick) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBackground(Ui.rounded(Ui.CARD, Ui.dp(c, 16)));
        int p = Ui.dp(c, 4);
        l.setPadding(p, p, p, p);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView t = Ui.text(c, labels[i], 15, on ? Ui.ON_ACCENT : Ui.TEXT2);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(c, 44));
            if (on) t.setBackground(Ui.rounded(Ui.ACCENT, Ui.dp(c, 13)));
            t.setSelected(on);
            t.setContentDescription(labels[i] + (on ? ", selected" : ""));
            t.setOnClickListener(v -> pick.accept(index));
            l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return l;
    }

    /** A button that reads as disabled, for screen readers too, when it has no action. */
    static android.widget.Button action(Context c, String label, boolean primary, View.OnClickListener click) {
        android.widget.Button b = Ui.button(c, label, primary, click);
        if (click == null) {
            b.setEnabled(false);
            b.setAlpha(0.5f);
        }
        return b;
    }

    /** Small rounded status chip, for example the current face. */
    static TextView chip(Context c, String s, boolean live) { return Ui.chip(c, s, live); }

    /** Label and value pairs, for technical details kept out of titles. */
    static LinearLayout details(Context c, String[][] rows) {
        LinearLayout box = Ui.group(c);
        box.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            TextView k = Ui.text(c, rows[i][0], 14, Ui.TEXT2);
            TextView v = Ui.text(c, rows[i][1], 14, Ui.TEXT);
            v.setGravity(Gravity.END);
            v.setTextIsSelectable(true);
            r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3);
            lp.leftMargin = Ui.dp(c, 12);
            r.addView(v, lp);
            r.setContentDescription(rows[i][0] + ": " + rows[i][1]);
            Ui.add(box, r, i == 0 ? 0 : 8);
        }
        return box;
    }

    /** Failure text for an upload; refusals before any block was sent do not warn about partial files. */
    static String transferFailure(String m, String what) {
        return m.endsWith("Nothing was sent to the watch.") ? m : m + " The watch may keep part of the " + what + ".";
    }

    /** Block progress for the face transfer the manager owns, with Stop. Null when none runs. */
    static View transferProgress(MainActivity a) {
        WatchManager w = a.watch;
        if (!w.faceUpload.active()) return null;
        LinearLayout box = Ui.column(a);
        String what = w.faceUpload.label == null ? "the face" : w.faceUpload.label;
        box.addView(Ui.status(a, "Sending " + what + " to the watch… " + w.faceUpload.sent + " of " + w.faceUpload.total, Ui.TEXT2));
        android.widget.ProgressBar p = Ui.progress(a);
        p.setMax(Math.max(w.faceUpload.total, 1));
        p.setProgress(w.faceUpload.sent);
        Ui.add(box, p, 4);
        Ui.add(box, Ui.button(a, "Stop", false, v -> w.stopTransfer()), 8);
        return box;
    }
}
