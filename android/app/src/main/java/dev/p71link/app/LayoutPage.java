package dev.p71link.app;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import dev.p71link.protocol.BulkProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Position and colour of the time, battery, and data items on the custom photo face, as stored on
 * the watch. Each change writes all three items and reads them back. The photo itself is not changed.
 */
final class LayoutPage implements MainActivity.Page {
    private static final String[] NAMES = {"", "Time", "Battery", "Data"};
    private static final String[] COLOUR_NAMES = {"White", "Black", "Lime", "Yellow", "Red", "Blue", "Grey"};
    private static final int[] COLOURS = {0xFFFFFF, 0x000000, 0xC9F57B, 0xFFD54F, 0xFF5252, 0x4FC3F7, 0x9E9E9E};
    private boolean loading, busy;
    private String message;
    private boolean error;

    @Override
    public void onShown(MainActivity a) { if (a.watch.connected() && a.watch.layout == null) load(a); }

    private void load(MainActivity a) {
        loading = true;
        a.watch.loadLayout(new WatchManager.Result<List<BulkProtocol.LayoutEntry>>() {
            @Override public void ok(List<BulkProtocol.LayoutEntry> v) { loading = false; a.render(); }
            @Override public void fail(String m) { loading = false; message = m; error = true; a.render(); }
        });
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Photo face layout"));
        page.addView(Ui.subtitle(a, "Where the watch draws the time, battery, and data on your photo face."));
        if (!w.connected()) {
            page.addView(Ui.status(a, "Connect the watch to read its layout.", Ui.TEXT2));
            return page;
        }
        if (loading) page.addView(Ui.status(a, "Reading from the watch…", Ui.TEXT2));
        List<BulkProtocol.LayoutEntry> l = w.layout;
        if (l != null) {
            boolean editable = !busy && !loading && !w.needsFreshRead;
            for (int i = 0; i < l.size(); i++) {
                final int index = i;
                BulkProtocol.LayoutEntry e = l.get(i);
                String sub = "Position " + e.x + ", " + e.y + " · colour " + colourName(e.rgb);
                Ui.add(page, Ui.row(a, NAMES[e.type], sub, null, editable ? v -> edit(a, l, index) : null), i == 0 ? 0 : 10);
            }
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        if (!loading) Ui.add(page, Ui.button(a, "Read again", false, v -> load(a)), 16);
        page.addView(Ui.note(a, "Positions are screen pixels from the top-left corner. The watch screen is "
                + width(w) + " × " + height(w) + "."));
        return page;
    }

    private static int width(WatchManager w) { return w.caps != null && w.caps.displayWidth > 0 ? w.caps.displayWidth : PhotoPage.DEFAULT_W; }

    private static int height(WatchManager w) { return w.caps != null && w.caps.displayHeight > 0 ? w.caps.displayHeight : PhotoPage.DEFAULT_H; }

    private static String colourName(int rgb) {
        for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i] == rgb) return COLOUR_NAMES[i];
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    private void edit(MainActivity a, List<BulkProtocol.LayoutEntry> l, int index) {
        BulkProtocol.LayoutEntry e = l.get(index);
        a.dialog().setTitle(NAMES[e.type]).setItems(new String[]{"Change colour", "Change position"}, (d, which) -> {
            if (which == 0) {
                a.dialog().setTitle("Colour").setItems(COLOUR_NAMES, (d2, c) ->
                        save(a, replace(l, index, new BulkProtocol.LayoutEntry(e.type, e.x, e.y, COLOURS[c])))).show();
            } else {
                EditText x = new EditText(a), y = new EditText(a);
                x.setInputType(InputType.TYPE_CLASS_NUMBER);
                y.setInputType(InputType.TYPE_CLASS_NUMBER);
                x.setText(String.valueOf(e.x));
                y.setText(String.valueOf(e.y));
                x.setHint("Across (0 to " + (width(a.watch) - 1) + ")");
                y.setHint("Down (0 to " + (height(a.watch) - 1) + ")");
                LinearLayout box = Ui.column(a);
                int p = Ui.dp(a, 20);
                box.setPadding(p, p / 2, p, 0);
                box.addView(x);
                box.addView(y);
                a.dialog().setTitle("Position").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d3, w3) -> {
                    int nx, ny;
                    try {
                        nx = Integer.parseInt(x.getText().toString().trim());
                        ny = Integer.parseInt(y.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        nx = -1;
                        ny = -1;
                    }
                    if (nx < 0 || ny < 0 || nx >= width(a.watch) || ny >= height(a.watch)) {
                        message = "The position must be on the watch screen.";
                        error = true;
                        a.render();
                        return;
                    }
                    save(a, replace(l, index, new BulkProtocol.LayoutEntry(e.type, nx, ny, e.rgb)));
                }).show();
            }
        }).show();
    }

    private static List<BulkProtocol.LayoutEntry> replace(List<BulkProtocol.LayoutEntry> l, int i, BulkProtocol.LayoutEntry e) {
        List<BulkProtocol.LayoutEntry> out = new ArrayList<>(l);
        out.set(i, e);
        return out;
    }

    private void save(MainActivity a, List<BulkProtocol.LayoutEntry> wanted) {
        busy = true;
        message = "Saving…";
        error = false;
        a.render();
        a.watch.saveLayout(wanted, new WatchManager.Result<List<BulkProtocol.LayoutEntry>>() {
            @Override public void ok(List<BulkProtocol.LayoutEntry> v) { busy = false; message = "Saved on the watch."; a.render(); }
            @Override public void fail(String m) { busy = false; message = m; error = true; a.render(); }
        });
    }
}
