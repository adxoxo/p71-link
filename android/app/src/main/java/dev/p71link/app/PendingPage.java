package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

/** An honest placeholder for a feature that this build does not provide yet. */
final class PendingPage implements MainActivity.Page {
    private final String title, text;

    PendingPage(String title, String text) {
        this.title = title;
        this.text = text;
    }

    @Override
    public View build(MainActivity a) {
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, title));
        page.addView(Ui.status(a, text, Ui.TEXT2));
        return page;
    }
}
