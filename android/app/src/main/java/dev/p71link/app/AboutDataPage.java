package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;

/** The limits of the health records, kept one tap away from the Health tab instead of under its actions. */
final class AboutDataPage implements MainActivity.Page {
    private static final String[][] POINTS = {
            {"From your watch only", "Records come from the watch when you read history. Nothing syncs on its own, and nothing is estimated."},
            {"Missing stays missing", "A sample the watch did not send shows as unavailable. A zero the watch sent shows as zero."},
            {"Distance and calories", "Kilometres and kilocalories follow the Pubu Wear display convention for these watch counters. "
                    + "They are not checked against physical measurements."},
            {"Sleep", "Sleep stages are the watch's own classification. Totals are added up on this phone."},
            {"Not medical", "These are not medical measurements. Blood oxygen and blood pressure are not offered."},
            {"Your copy", "Records stay on this phone. Export them from More, Data and device."},
    };

    @Override
    public View build(MainActivity a) {
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "About this data"));
        page.addView(Ui.subtitle(a, "What the health records are, and what they are not."));
        LinearLayout g = Ui.group(a);
        for (String[] p : POINTS) {
            LinearLayout item = Ui.column(a);
            int pad = Ui.dp(a, 16);
            item.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 12));
            item.addView(Ui.text(a, p[0], 16, Ui.TEXT));
            android.widget.TextView body = Ui.text(a, p[1], 14, Ui.TEXT2);
            body.setPadding(0, Ui.dp(a, 4), 0, 0);
            item.addView(body);
            item.setContentDescription(p[0] + ". " + p[1]);
            Ui.addToGroup(g, item, 16);
        }
        page.addView(g);
        return page;
    }
}
