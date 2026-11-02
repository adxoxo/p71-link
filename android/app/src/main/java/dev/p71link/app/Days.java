package dev.p71link.app;

import dev.p71link.protocol.Alarms;

/** Repeat-day picker. Bits follow the vendor editor: bit 0 Sunday ... bit 6 Saturday. */
final class Days {
    private static final int[] BITS = {1, 2, 3, 4, 5, 6, 0}; // Monday first on screen.
    private static final String[] NAMES = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    interface Done { void picked(int mask); }

    private Days() {}

    static void pick(MainActivity a, String title, int mask, Done done) {
        boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = (mask >> BITS[i] & 1) != 0;
        a.dialog().setTitle(title).setMultiChoiceItems(NAMES, checked, (d, which, on) -> checked[which] = on)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    int m = 0;
                    for (int i = 0; i < 7; i++) if (checked[i]) m |= 1 << BITS[i];
                    done.picked(m);
                }).show();
    }

    static String text(int mask) { return Alarms.repeatText(mask); }
}
