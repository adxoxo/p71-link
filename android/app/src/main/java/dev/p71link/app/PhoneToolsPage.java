package dev.p71link.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

/** Actions the watch can start on this phone. */
final class PhoneToolsPage implements MainActivity.Page {
    @Override
    public View build(MainActivity a) {
        PhoneTools t = P71App.get().tools;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Phone tools"));
        page.addView(Ui.subtitle(a, "Actions the watch can start on this phone while it is connected."));

        String sub = t.ringing() ? "Ringing now" : t.lastFindPhone == 0 ? "Start it from the watch" : "Last used " + HealthPage.when(t.lastFindPhone);
        Ui.add(page, Ui.row(a, "Find phone", sub, null, null), 0);
        if (t.ringing()) Ui.add(page, Ui.button(a, "Stop ringing", true, v -> t.stopFindPhone()), 10);
        else Ui.add(page, Ui.button(a, "Test the ring on this phone", false, v -> t.startFindPhone()), 10);

        Switch music = Ui.toggle(a, a.store.musicControl(), true, "Music controls from the watch");
        music.setOnCheckedChangeListener((b, on) -> { a.store.setMusicControl(on); a.render(); });
        String last = t.lastMusicCommand == null ? "Play, pause, skip, and volume for the player in use"
                : "Last: " + t.lastMusicCommand + " · " + HealthPage.when(t.lastMusicAt);
        Ui.add(page, Ui.row(a, "Music controls", last, music, null), 16);

        page.addView(Ui.note(a, "The phone rings for at most 10 seconds. A music button press is passed to Android; "
                + "whether a player reacts depends on that player. The watch is not sent track details."));
        return page;
    }
}
