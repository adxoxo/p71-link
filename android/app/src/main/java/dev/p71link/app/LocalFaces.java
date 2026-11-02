package dev.p71link.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

/**
 * Photo backgrounds this phone owns: the bundled Orbit design and a local copy of the last photo sent.
 * They are offline. Neither is ever linked to an installed watch slot: the watch reports no slot identity
 * for its photo background, and a transfer acknowledgment does not show what the watch displays.
 */
final class LocalFaces {
    static final String ORBIT_SENT = "face.orbit.sent", PHOTO_SENT = "face.photo.sent";
    private static final String PHOTO_FILE = "last-photo.png";

    private static Bitmap orbit, photo;
    private static long photoStamp;

    private LocalFaces() {}

    /** The bundled Orbit background at its native 240 x 284, or null if the resource is damaged. */
    static Bitmap orbit(Context c) {
        if (orbit == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeResource(c.getResources(), R.drawable.face_orbit_aquryu, o);
            if (b != null && b.getWidth() == FaceArtView.W && b.getHeight() == FaceArtView.H) orbit = b;
        }
        return orbit;
    }

    /** Native pixels flattened onto black so every pixel is opaque; null when the size is not exact. */
    static int[] pixels(Bitmap b, int width, int height) {
        if (b == null || b.getWidth() != width || b.getHeight() != height) return null;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.drawBitmap(b, 0, 0, null);
        int[] px = new int[width * height];
        out.getPixels(px, 0, width, 0, 0, width, height);
        out.recycle();
        for (int i = 0; i < px.length; i++) px[i] |= 0xff000000;
        return px;
    }

    /** The local copy of the last photo this phone sent, or null when there is none. */
    static Bitmap lastPhoto(Context c, Store store) {
        long stamp = sentAt(store, PHOTO_SENT);
        if (stamp == 0) return null;
        if (photo != null && photoStamp == stamp) return photo;
        File f = new File(new File(c.getFilesDir(), "faces"), PHOTO_FILE);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        // Only an image at a plausible watch size is accepted; this bounds the decode.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 1024 || bounds.outHeight > 1024) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        photo = BitmapFactory.decodeFile(f.getPath(), o);
        photoStamp = photo == null ? 0 : stamp;
        return photo;
    }

    /**
     * Keep a local copy of a photo after the watch accepted it. Written off the main thread; the record
     * that makes it visible is saved only after the file is complete.
     */
    static void savePhoto(Context c, Store store, int[] argb, int width, int height) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dir = new File(app.getFilesDir(), "faces");
            File tmp = new File(dir, PHOTO_FILE + ".tmp"), dst = new File(dir, PHOTO_FILE);
            Bitmap b = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder");
                b = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("not written");
                }
                if (!tmp.renameTo(dst)) throw new IOException("not moved");
                long now = System.currentTimeMillis();
                main.post(() -> markSent(store, PHOTO_SENT, now));
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete(); // The upload itself succeeded; only the local copy is missing.
            } finally {
                if (b != null) b.recycle();
            }
        }, "p71-photo-cache").start();
    }

    static void markSent(Store store, String key, long ms) { store.put(key, Long.toString(ms)); }

    static long sentAt(Store store, String key) {
        String v = store.get(key);
        if (v == null) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "2 Nov 2026" in the phone's locale, or null when this phone never sent it. */
    static String sentDate(Store store, String key) {
        long ms = sentAt(store, key);
        return ms == 0 ? null : DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(ms));
    }
}
