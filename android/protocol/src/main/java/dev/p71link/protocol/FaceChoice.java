package dev.p71link.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An installed face the user picked from one face-list reading. Before a select is sent, the pick is
 * resolved against the list the app holds now. It stays valid only while that list has exactly the
 * same file names in the same watch order; otherwise the row could address a different face.
 * Delete-eligibility flags are not part of identity, and the current-face index is never consulted.
 */
public final class FaceChoice {
    public final int row;
    public final String name;
    private final List<String> order;

    private FaceChoice(int row, String name, List<String> order) {
        this.row = row;
        this.name = name;
        this.order = order;
    }

    /** Pick zero-based {@code row} of {@code list}; null when the row is not in the list. */
    public static FaceChoice of(List<BulkProtocol.InstalledFace> list, int row) {
        if (list == null || row < 0 || row >= list.size()) return null;
        return new FaceChoice(row, list.get(row).name, names(list));
    }

    /** Number of faces in the reading this pick came from. */
    public int listSize() { return order.size(); }

    /** The zero-based row to select in {@code current}, or -1 when the pick no longer matches it. */
    public int resolve(List<BulkProtocol.InstalledFace> current) {
        if (current == null || current.size() != order.size()) return -1;
        for (int i = 0; i < order.size(); i++) {
            if (!java.util.Objects.equals(current.get(i).name, order.get(i))) return -1;
        }
        return row;
    }

    private static List<String> names(List<BulkProtocol.InstalledFace> list) {
        List<String> out = new ArrayList<>(list.size());
        for (BulkProtocol.InstalledFace f : list) out.add(f.name);
        return Collections.unmodifiableList(out);
    }
}
