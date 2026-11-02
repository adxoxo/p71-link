package dev.p71link.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;

import dev.p71link.protocol.BulkProtocol;
import dev.p71link.protocol.FaceChoice;
import dev.p71link.protocol.ResourceTransfer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faces tab. "Your faces": designs on this phone and the watch's installed faces as cards in watch order.
 * "Discover": the bundled Orbit design, the optional online catalog and file import.
 */
final class FacesPage implements MainActivity.Page {
    private static final String[] VIEWS = {"Your faces", "Discover"};
    private int view;
    private String message;
    private boolean error;
    /** Session in which an automatic list read failed; no further automatic read in that session. */
    private long autoFailedFor = -1;
    private MainActivity shown;
    private final Runnable previews = () -> { if (shown != null) shown.render(); };

    @Override
    public void onShown(MainActivity a) {
        shown = a;
        FaceCatalog.listen(previews);
        WatchManager w = a.watch;
        if (w.connected() && w.faces == null && w.bulkAvailable() && !w.faceBusy() && autoFailedFor != w.sessionReadyAt) {
            load(a, true);
        }
    }

    @Override
    public void onHidden(MainActivity a) {
        FaceCatalog.unlisten(previews);
        shown = null;
    }

    private void load(MainActivity a, boolean automatic) {
        long session = a.watch.sessionReadyAt;
        a.watch.loadFaces(new WatchManager.Result<List<BulkProtocol.InstalledFace>>() {
            @Override public void ok(List<BulkProtocol.InstalledFace> v) { a.render(); }

            @Override public void fail(String m) {
                if (automatic) autoFailedFor = session;
                message = m;
                error = true;
                a.render();
            }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.header(a, "Faces", currentChip(a, w)));
        Ui.add(page, FaceUi.tabs(a, VIEWS, view, i -> {
            view = i;
            a.render();
        }), 12);
        if (view == 0) buildYours(a, page);
        else buildDiscover(a, page);
        return page;
    }

    private static View currentChip(MainActivity a, WatchManager w) {
        if (!w.connected()) return FaceUi.chip(a, "Not connected", false);
        if (w.faces == null || w.faceListOp.active()) return FaceUi.chip(a, "Current face not read", false);
        int row = BulkProtocol.currentRow(w.faces, w.faceIndex);
        if (row < 0) return FaceUi.chip(a, "Current face unknown", false);
        return FaceUi.chip(a, "Current: " + FaceUi.installedTitle(row), true);
    }

    private boolean canUpload(WatchManager w) { return w.connected() && w.bulkAvailable() && !w.faceBusy(); }

    private void buildYours(MainActivity a, LinearLayout page) {
        WatchManager w = a.watch;
        View progress = FaceUi.transferProgress(a);
        if (progress != null) Ui.add(page, progress, 8);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        else if (progress == null) lastTransfer(a, page);

        page.addView(Ui.section(a, "From this phone"));
        List<View> mine = new ArrayList<>();
        mine.add(orbitCard(a));
        Bitmap photo = LocalFaces.lastPhoto(a, a.store);
        if (photo != null) {
            FaceArtView art = new FaceArtView(a);
            art.setArt(photo, null);
            String sub = "Sent from this phone · " + LocalFaces.sentDate(a.store, LocalFaces.PHOTO_SENT);
            mine.add(FaceUi.card(a, art, "Your photo", sub, "Your photo, local copy. " + sub + ". Opens details.",
                    v -> a.push(new WallpaperFacePage(WallpaperFacePage.PHOTO))));
        }
        mine.add(newPhotoCard(a));
        page.addView(FaceUi.grid(a, mine));

        List<BulkProtocol.InstalledFace> faces = w.faces;
        page.addView(Ui.section(a, "On your watch" + (faces == null ? "" : " · " + faces.size() + (faces.size() == 1 ? " face" : " faces"))));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to see its faces.", Ui.TEXT2));
        } else if (!w.bulkAvailable()) {
            page.addView(Ui.status(a, "This connection does not offer the face channel. Reconnect and try again.", Ui.ERROR));
        } else if (w.faceListOp.active()) {
            page.addView(Ui.status(a, "Reading faces from the watch…", Ui.TEXT2));
        } else if (faces == null) {
            page.addView(Ui.status(a, "Not read yet.", Ui.TEXT2));
        } else if (faces.isEmpty()) {
            page.addView(Ui.status(a, "The watch reported no installed faces.", Ui.TEXT2));
        } else {
            Ui.add(page, FaceUi.catalogControl(a, faces), 0);
            int current = BulkProtocol.currentRow(faces, w.faceIndex);
            List<View> cells = new ArrayList<>();
            for (int i = 0; i < faces.size(); i++) {
                final FaceChoice choice = FaceChoice.of(faces, i);
                FaceUi.Art art = FaceUi.installedArt(w, faces.get(i).name);
                FaceArtView v = new FaceArtView(a);
                v.setArt(art.bitmap, art.placeholder);
                v.setCurrent(i == current);
                String title = FaceUi.installedTitle(i);
                String sub = (art.catalog ? "Catalog preview" : "On watch") + " · #" + (i + 1);
                String description = title + (i == current ? ", current face" : "") + ". "
                        + (art.catalog ? "Catalog preview image, not live data." : art.placeholder + ".") + " Opens details.";
                cells.add(FaceUi.card(a, v, title, sub, description, x -> a.push(new InstalledFacePage(choice))));
            }
            Ui.add(page, FaceUi.grid(a, cells), 12);
        }
        if (w.connected() && w.bulkAvailable() && !w.faceBusy()) {
            Ui.add(page, Ui.button(a, faces == null ? "Read faces" : "Read faces again", false, v -> {
                message = null;
                load(a, false);
                a.render();
            }), 14);
        }
        page.addView(Ui.note(a, "In watch order. Current comes from the watch's face number."));
    }

    private void buildDiscover(MainActivity a, LinearLayout page) {
        WatchManager w = a.watch;
        View progress = FaceUi.transferProgress(a);
        if (progress != null) Ui.add(page, progress, 8);
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.section(a, "Made by P71 Link"));
        List<View> cells = new ArrayList<>();
        cells.add(orbitCard(a));
        cells.add(newPhotoCard(a));
        page.addView(FaceUi.grid(a, cells));

        page.addView(Ui.section(a, "More faces"));
        LinearLayout more = Ui.group(a);
        Ui.addToGroup(more, Ui.item(a, R.drawable.ic_globe, "Browse watch faces", "Free " + FaceCatalog.HARDWARE + " designs · uses the internet",
                null, v -> a.push(new CatalogPage())));
        boolean canUse = canUpload(w);
        Ui.addToGroup(more, Ui.item(a, R.drawable.ic_upload, "Import face file", canUse ? "A compatible .di file" : "Connect the watch first",
                null, canUse ? v -> pickFile(a) : null));
        if (w.caps == null || w.caps.customWallpaper) {
            boolean linked = w.connected() && w.bulkAvailable();
            Ui.addToGroup(more, Ui.item(a, R.drawable.ic_photo, "Photo face layout", linked ? "Time, battery and data positions" : "Connect the watch first",
                    null, linked ? v -> a.push(new LayoutPage()) : null));
        }
        page.addView(more);
        page.addView(Ui.note(a, "Uploads cannot be undone from the phone. Catalog order is not watch order."));
    }

    private void lastTransfer(MainActivity a, LinearLayout page) {
        WatchManager w = a.watch;
        if (w.faceUpload.lastLabel == null) return;
        if (w.faceUpload.lastError == null) {
            page.addView(Ui.status(a, "The watch accepted " + w.faceUpload.lastLabel + ". Check the watch.", Ui.TEXT2));
        } else {
            page.addView(Ui.status(a, "Sending " + w.faceUpload.lastLabel + " did not finish. " + w.faceUpload.lastError, Ui.ERROR));
        }
    }

    private static View orbitCard(MainActivity a) {
        FaceArtView art = new FaceArtView(a);
        Bitmap b = LocalFaces.orbit(a);
        art.setArt(b, "Image unavailable");
        String date = LocalFaces.sentDate(a.store, LocalFaces.ORBIT_SENT);
        String sub = date == null ? "Bundled design · not sent yet" : "Sent from this phone · " + date;
        return FaceUi.card(a, art, "Orbit", sub, "Orbit, photo background made by P71 Link. " + sub + ". Opens details.",
                v -> a.push(new WallpaperFacePage(WallpaperFacePage.ORBIT)));
    }

    private View newPhotoCard(MainActivity a) {
        WatchManager w = a.watch;
        FaceArtView art = new FaceArtView(a);
        art.setAdd("Photo background");
        boolean supported = w.caps == null || w.caps.customWallpaper;
        boolean usable = supported && canUpload(w);
        String sub = !supported ? "This watch does not report photo support"
                : usable ? "Choose a photo and crop it" : "Connect the watch first";
        return FaceUi.card(a, art, "New photo face", sub, "New photo face. " + sub + ".", usable ? v -> pickPhoto(a) : null);
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
        final int session = a.watch.sessionToken();
        a.confirm("Send this face?", name + " (" + size + ")\n\nP71 Link cannot check what is inside this file. "
                        + "Use only faces made for " + (a.watch.deviceName == null ? "this watch" : a.watch.deviceName)
                        + ". The upload cannot be undone from the phone.", "Send",
                () -> send(a, ResourceTransfer.Kind.VENDOR_FACE, name, session, data,
                        "The watch accepted the face file. Check the watch; you may need to choose it."));
    }

    void send(MainActivity a, ResourceTransfer.Kind kind, String name, int session, byte[] data, String success) {
        if (a.watch.faceBusy()) {
            message = "Wait for the current face action to finish.";
            error = true;
            a.render();
            return;
        }
        message = null;
        a.watch.upload(kind, name, "the face file", session, data, (d, n) -> a.render(), new WatchManager.Result<Void>() {
            @Override public void ok(Void v) {
                message = success;
                error = false;
                // The transfer owner already forgot the list and index and reads them once again.
                a.render();
            }

            @Override public void fail(String m) {
                message = FaceUi.transferFailure(m, "file");
                error = true;
                a.render();
            }
        });
        a.render();
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
