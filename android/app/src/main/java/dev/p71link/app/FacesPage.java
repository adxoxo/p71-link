package dev.p71link.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.ResourceTransfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Faces tab: installed faces, custom photo, and compatible face files. */
final class FacesPage implements MainActivity.Page {
    private String message;
    private boolean error, loading, requested;
    private int sent, total;
    private boolean uploading;

    @Override
    public void onShown(MainActivity a) {
        WatchManager w = a.watch;
        if (w.connected() && w.faces == null && !loading && !requested && w.bulkAvailable()) load(a);
    }

    private void load(MainActivity a) {
        loading = true;
        requested = true;
        a.watch.loadFaces(new WatchManager.Result<List<BulkProtocol.InstalledFace>>() {
            @Override public void ok(List<BulkProtocol.InstalledFace> v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Faces"));
        page.addView(Ui.subtitle(a, "Make it yours."));

        LinearLayout current = Ui.card(a, Ui.HERO);
        current.addView(Ui.text(a, "Current face", 14, Ui.ACCENT));
        String name;
        if (!w.connected()) name = "Connect to see your faces";
        else if (loading) name = "Reading faces from the watch…";
        else if (w.faces == null) name = w.bulkAvailable() ? "Not read yet" : "This connection cannot list faces";
        else if (w.faces.isEmpty()) name = "The watch reported no installed faces";
        else name = selectedName(w);
        current.addView(Ui.text(a, name, 20, Ui.TEXT));
        if (w.faces != null) current.addView(Ui.text(a, w.faces.size() + " installed", 13, Ui.TEXT2));
        Ui.add(page, current, 0);

        boolean canUse = w.connected() && w.bulkAvailable() && !uploading;
        Ui.add(page, Ui.button(a, "Choose built-in face", true, v -> {
            if (!w.connected()) a.toast("Connect the watch first.");
            else a.push(new InstalledFacesPage());
        }), 16);
        Ui.add(page, Ui.row(a, "Photo background", "Choose a photo and crop it", null, canUse ? v -> pickPhoto(a) : null), 14);
        if (w.caps == null || w.caps.customWallpaper) {
            Ui.add(page, Ui.row(a, "Photo face layout", "Time, battery, and data positions", null,
                    canUse ? v -> a.push(new LayoutPage()) : null), 10);
        }
        Ui.add(page, Ui.row(a, "Browse watch faces", "Compatible designs for your watch", null,
                v -> a.push(new CatalogPage())), 10);
        Ui.add(page, Ui.row(a, "Import face file", "Use a compatible local file", null, canUse ? v -> pickFile(a) : null), 10);

        if (uploading) {
            page.addView(Ui.status(a, "Sending to the watch… " + sent + " of " + total, Ui.TEXT2));
            ProgressBar p = Ui.progress(a);
            p.setMax(Math.max(total, 1));
            p.setProgress(sent);
            Ui.add(page, p, 4);
            Ui.add(page, Ui.button(a, "Stop", false, v -> w.stopTransfer()), 8);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (w.connected() && w.faces != null && !loading) {
            Ui.add(page, Ui.button(a, "Read faces again", false, v -> load(a)), 10);
        }
        page.addView(Ui.note(a, "Preview before you apply a face. A file upload cannot be undone from the phone."));
        return page;
    }

    /** Name of the current face from the 75 index only; unknown when it was not read or is invalid. */
    static String selectedName(WatchManager w) {
        int row = BulkProtocol.currentRow(w.faces, w.faceIndex);
        return row < 0 ? "Current face unknown" : pretty(w.faces.get(row).name);
    }

    static String pretty(String n) {
        if (n == null || n.isEmpty()) return "Unnamed face";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void pickPhoto(MainActivity a) {
        Intent i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        i.setType("image/*");
        a.startActivityForResult(i, MainActivity.PICK_PHOTO);
    }

    private void pickFile(MainActivity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startActivityForResult(i, MainActivity.PICK_FACE_FILE);
    }

    @Override
    public void onActivityResult(MainActivity a, int request, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (request == MainActivity.PICK_PHOTO) {
            a.push(new PhotoPage(data.getData()));
        } else if (request == MainActivity.PICK_FACE_FILE) {
            importFile(a, data.getData());
        }
    }

    private void importFile(MainActivity a, Uri uri) {
        String name = displayName(a, uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".di")) {
            message = "Choose a watch face file that ends in .di and was made for this watch.";
            error = true;
            return;
        }
        byte[] data;
        try {
            data = read(a, uri, BulkProtocol.MAX_FACE_FILE);
        } catch (IOException e) {
            message = e.getMessage() == null ? "The file could not be read." : e.getMessage();
            error = true;
            return;
        }
        if (data.length == 0) {
            message = "The file is empty.";
            error = true;
            return;
        }
        String size = String.format(Locale.ROOT, "%.1f KB", data.length / 1024.0);
        a.confirm("Send this face?", name + " (" + size + ")\n\nP71 Link cannot check what is inside this file. "
                        + "Use only faces made for " + (a.watch.deviceName == null ? "this watch" : a.watch.deviceName)
                        + ". The upload cannot be undone from the phone.", "Send",
                () -> send(a, ResourceTransfer.Kind.VENDOR_FACE, name, data,
                        "The watch accepted the face file. Check the watch; you may need to choose it."));
    }

    void send(MainActivity a, ResourceTransfer.Kind kind, String name, byte[] data, String success) {
        uploading = true;
        sent = 0;
        total = (data.length + ResourceTransfer.BLOCK - 1) / ResourceTransfer.BLOCK;
        message = null;
        a.render();
        a.watch.upload(kind, name, data, (d, n) -> {
            sent = d;
            total = n;
            a.render();
        }, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) {
                uploading = false;
                message = success;
                error = false;
                load(a);
                a.render();
            }

            @Override public void fail(String m) {
                uploading = false;
                message = m + " The watch may keep part of the file.";
                error = true;
                a.render();
            }
        });
    }

    static String displayName(MainActivity a, Uri uri) {
        try (Cursor c = a.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        return uri.getLastPathSegment();
    }

    static byte[] read(MainActivity a, Uri uri, int max) throws IOException {
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("The file could not be opened.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("The file is larger than a watch face can be.");
            }
            return out.toByteArray();
        }
    }
}
