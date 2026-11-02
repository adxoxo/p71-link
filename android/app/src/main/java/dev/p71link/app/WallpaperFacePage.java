package dev.p71link.app;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.ResourceTransfer;

import java.util.Locale;

/**
 * A photo background owned by this phone: the bundled Orbit design or the local copy of the last photo.
 * Apply replaces the watch's custom photo after confirmation, through the existing RGB565 transfer.
 * It never selects a face and never claims what the watch displays.
 */
final class WallpaperFacePage implements MainActivity.Page {
    static final int ORBIT = 0, PHOTO = 1;
    private final int kind;
    /** Position markers are an explicit detail choice; the artwork shows clean by default. */
    private boolean markers;
    private String message;
    private boolean error;

    WallpaperFacePage(int kind) { this.kind = kind; }

    private String name() { return kind == ORBIT ? "Orbit" : "Your photo"; }

    private String label() { return kind == ORBIT ? "the Orbit background" : "your photo"; }

    private String sentKey() { return kind == ORBIT ? LocalFaces.ORBIT_SENT : LocalFaces.PHOTO_SENT; }

    private Bitmap image(MainActivity a) { return kind == ORBIT ? LocalFaces.orbit(a) : LocalFaces.lastPhoto(a, a.store); }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, name()));
        page.addView(Ui.subtitle(a, kind == ORBIT ? "Photo face background, made by P71 Link"
                : "Local copy of the last photo this phone sent"));
        Bitmap b = image(a);
        if (b == null) {
            page.addView(Ui.status(a, "This image is not available on this phone.", Ui.ERROR));
            return page;
        }
        int width = PhotoPage.DEFAULT_W, height = PhotoPage.DEFAULT_H;
        if (w.caps != null && w.caps.displayWidth > 0 && w.caps.displayHeight > 0) {
            width = w.caps.displayWidth;
            height = w.caps.displayHeight;
        }

        FaceArtView big = new FaceArtView(a);
        big.setArt(b, null);
        big.setMarkers(markers, w.layout);
        big.setMaxWidth(Ui.dp(a, 260));
        LinearLayout frame = Ui.card(a, Ui.HERO);
        frame.setGravity(Gravity.CENTER_HORIZONTAL);
        frame.addView(big);
        String markerText = !markers ? "Markers hidden. The image itself contains no time, date, battery or health values."
                : (w.layout != null ? "Markers use the positions stored on this watch." : "Markers show typical default positions for illustration; this watch's layout was not read.")
                + " The watch draws its own live values there; they are not readings.";
        android.widget.TextView caption = Ui.text(a, markerText, 13, Ui.TEXT2);
        caption.setPadding(0, Ui.dp(a, 10), 0, 0);
        frame.addView(caption);
        frame.setContentDescription(name() + " preview. " + markerText);
        Ui.add(page, frame, 0);
        Ui.add(page, Ui.button(a, markers ? "Hide markers" : "Show markers", false, v -> {
            markers = !markers;
            a.render();
        }), 10);

        View progress = FaceUi.transferProgress(a);
        if (progress != null) Ui.add(page, progress, 12);
        boolean supported = w.caps == null || w.caps.customWallpaper;
        boolean sized = b.getWidth() == width && b.getHeight() == height;
        if (!supported) page.addView(Ui.status(a, "This watch does not report custom photo support.", Ui.ERROR));
        else if (!sized) page.addView(Ui.status(a, String.format(Locale.ROOT,
                "This image is %d × %d. The watch reports %d × %d, so it is not sent.", b.getWidth(), b.getHeight(), width, height), Ui.ERROR));
        boolean usable = supported && sized && w.connected() && w.bulkAvailable() && !w.faceBusy();
        final int fw = width, fh = height;
        // The session is captured at the tap; the manager refuses the transfer if it changed during the dialog.
        Ui.add(page, FaceUi.action(a, "Apply to watch", true, usable ? v -> {
            final int session = w.sessionToken();
            a.confirm("Replace the photo face?",
                    "The current custom photo on the watch is replaced with " + name() + ". It cannot be read back or restored "
                            + "from the phone. This does not switch the watch to the photo face.", "Replace", () -> send(a, fw, fh, session));
        } : null), 12);
        if (!w.connected() || !w.bulkAvailable()) page.addView(Ui.status(a, "Connect the watch to apply.", Ui.TEXT2));
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (supported) {
            Ui.add(page, Ui.row(a, "Photo face layout", "Time, battery, and data positions", null,
                    w.connected() && w.bulkAvailable() ? v -> a.push(new LayoutPage()) : null), 12);
        }
        page.addView(Ui.note(a, "Apply replaces the photo background on the watch. The phone cannot read back or restore the old photo."));

        page.addView(Ui.section(a, "Details"));
        int bytes = 4 + width * height * 2;
        String sent = LocalFaces.sentDate(a.store, sentKey());
        String[][] rows = {
                {"Type", "Photo background, RGB565"},
                {"Size", String.format(Locale.getDefault(), "%d × %d · %.0f KB", width, height, bytes / 1024.0)},
                {"File on watch", BulkProtocol.WALLPAPER_NAME},
                {"Last sent", sent == null ? "Not sent from this phone" : sent + ", this phone"},
                {"Contents", kind == ORBIT ? "Original artwork with the aquryu logo. No time, date, battery or health values."
                        : "The cropped photo this phone sent"},
        };
        page.addView(FaceUi.details(a, rows));
        return page;
    }

    private void send(MainActivity a, int width, int height, int session) {
        WatchManager w = a.watch;
        if (w.faceBusy()) return;
        byte[] data;
        try {
            int[] px = LocalFaces.pixels(image(a), width, height);
            if (px == null) throw new IllegalArgumentException("The image size does not match the watch screen.");
            data = BulkProtocol.rgb565Wallpaper(width, height, px);
        } catch (IllegalArgumentException e) {
            message = Errors.message(e);
            error = true;
            a.render();
            return;
        }
        message = null;
        w.upload(ResourceTransfer.Kind.WALLPAPER, BulkProtocol.WALLPAPER_NAME, label(), session, data, (d, n) -> a.render(),
                new WatchManager.Result<Void>() {
                    @Override public void ok(Void v) {
                        LocalFaces.markSent(a.store, sentKey(), System.currentTimeMillis());
                        // A transfer acknowledgment, not proof of what the watch shows.
                        message = "The watch accepted " + label() + ". Check the watch: it appears on the custom photo face, "
                                + "which you may need to choose.";
                        error = false;
                        a.render();
                    }

                    @Override public void fail(String m) {
                        message = FaceUi.transferFailure(m, "image");
                        error = true;
                        a.render();
                    }
                });
        a.render();
    }
}
