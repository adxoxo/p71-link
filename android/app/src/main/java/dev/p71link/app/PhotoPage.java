package dev.p71link.app;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.ResourceTransfer;

import java.io.IOException;

/** Crop, preview, and upload a photo as the custom face background. */
final class PhotoPage implements MainActivity.Page {
    /** Hardware parameters for M71J_V1.0, from the watch's capability report and its face parameters. */
    static final int DEFAULT_W = 240, DEFAULT_H = 284, RADIUS = 50;

    private final Uri uri;
    private Bitmap bitmap;
    private CropView crop;
    private String message;
    private boolean error, uploading, done;
    private int sent, total;

    PhotoPage(Uri uri) { this.uri = uri; }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Photo background"));
        page.addView(Ui.subtitle(a, "Drag and pinch to fit. The watch shows the area inside the frame."));
        int width = DEFAULT_W, height = DEFAULT_H;
        if (w.caps != null && w.caps.displayWidth > 0 && w.caps.displayHeight > 0) {
            width = w.caps.displayWidth;
            height = w.caps.displayHeight;
        }
        if (w.caps != null && !w.caps.customWallpaper) {
            page.addView(Ui.status(a, "This watch does not report custom photo support.", Ui.ERROR));
            return page;
        }
        if (bitmap == null) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(a.getContentResolver(), uri);
                bitmap = ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (max > 2048) {
                        float f = 2048f / max;
                        dec.setTargetSize(Math.round(info.getSize().getWidth() * f), Math.round(info.getSize().getHeight() * f));
                    }
                });
            } catch (IOException | RuntimeException e) {
                page.addView(Ui.status(a, "This photo could not be opened.", Ui.ERROR));
                return page;
            }
        }
        if (crop == null) crop = new CropView(a, bitmap, width, height, RADIUS);
        if (crop.getParent() != null) ((android.view.ViewGroup) crop.getParent()).removeView(crop);
        Ui.add(page, crop, 0);
        crop.setEnabled(!uploading);

        if (uploading) {
            page.addView(Ui.status(a, "Sending to the watch… " + sent + " of " + total, Ui.TEXT2));
            ProgressBar p = Ui.progress(a);
            p.setMax(Math.max(total, 1));
            p.setProgress(sent);
            Ui.add(page, p, 4);
            Ui.add(page, Ui.button(a, "Stop", false, v -> w.stopTransfer()), 8);
        } else if (!done) {
            final int fw = width, fh = height;
            Ui.add(page, Ui.button(a, "Send to watch", true, v -> {
                if (!w.connected() || !w.bulkAvailable()) {
                    message = "Connect the watch first.";
                    error = true;
                    a.render();
                    return;
                }
                a.confirm("Replace the photo face?", "The current custom photo on the watch is replaced. "
                        + "It cannot be read back or restored from the phone.", "Replace", () -> send(a, fw, fh));
            }), 16);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "Labels in brackets only mark where the watch draws its own time, battery, and data. They are not readings and are not sent."));
        return page;
    }

    private void send(MainActivity a, int width, int height) {
        byte[] data;
        try {
            data = BulkProtocol.rgb565Wallpaper(width, height, crop.render());
        } catch (IllegalArgumentException e) {
            message = Errors.message(e);
            error = true;
            a.render();
            return;
        }
        uploading = true;
        sent = 0;
        total = (data.length + ResourceTransfer.BLOCK - 1) / ResourceTransfer.BLOCK;
        message = null;
        a.render();
        a.watch.upload(ResourceTransfer.Kind.WALLPAPER, BulkProtocol.WALLPAPER_NAME, data, (d, n) -> {
            sent = d;
            total = n;
            a.render();
        }, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) {
                uploading = false;
                done = true;
                message = "The watch accepted the photo. Check the custom face on the watch.";
                error = false;
                a.render();
            }

            @Override public void fail(String m) {
                uploading = false;
                message = m + " The watch may keep part of the photo.";
                error = true;
                a.render();
            }
        });
    }
}
