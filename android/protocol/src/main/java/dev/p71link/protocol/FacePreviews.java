package dev.p71link.protocol;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact identity between an installed face name and catalog artwork. The watch lists file names,
 * never images, so artwork is shown only when one catalog entry for the same hardware has exactly
 * the same file name. List position, type ID, substrings and case-folding are never used.
 */
public final class FacePreviews {
    private FacePreviews() {}

    /** One catalog record: file name (basename of its file URL), allowed preview URL or null, hardware. */
    public static final class Entry {
        public final String name, previewUrl, hardware;

        public Entry(String name, String previewUrl, String hardware) {
            this.name = name;
            this.previewUrl = previewUrl;
            this.hardware = hardware;
        }
    }

    public enum Status {
        /** One exact catalog image for this file name. */
        MATCH,
        /** No catalog entry for the same hardware has this exact file name. */
        NO_MATCH,
        /** Same-name entries point to different images, so none is shown. */
        AMBIGUOUS,
        /** The exact entry has no allowed preview URL. */
        NO_IMAGE,
        /** The installed name is empty, so nothing can be matched. */
        UNNAMED,
        /** The connected watch reports a different model than the catalog hardware. */
        OTHER_HARDWARE
    }

    public static final class Match {
        public final Status status;
        /** Non-null only for MATCH. */
        public final String previewUrl;

        Match(Status status, String previewUrl) {
            this.status = status;
            this.previewUrl = previewUrl;
        }
    }

    private static final Match NO_MATCH = new Match(Status.NO_MATCH, null), AMBIGUOUS = new Match(Status.AMBIGUOUS, null),
            NO_IMAGE = new Match(Status.NO_IMAGE, null), UNNAMED = new Match(Status.UNNAMED, null),
            OTHER = new Match(Status.OTHER_HARDWARE, null);

    /** File name of a catalog file URL: the text after the last slash, or null when there is none. */
    public static String fileName(String url) {
        if (url == null) return null;
        String n = url.substring(url.lastIndexOf('/') + 1);
        return n.isEmpty() ? null : n;
    }

    /**
     * True unless the watch's firmware revision names a different model than the catalog hardware.
     * "M71J_1.00.27_231223" and "M71J_V1.0" share the model "M71J". An unread revision is not a mismatch:
     * the app binds one exact watch, and the catalog request and each entry already name the hardware.
     */
    public static boolean sameModel(String firmware, String hardware) {
        if (firmware == null || firmware.isEmpty()) return true;
        if (hardware == null) return false;
        int f = firmware.indexOf('_'), h = hardware.indexOf('_');
        if (f <= 0 || h <= 0) return false;
        return firmware.substring(0, f).equals(hardware.substring(0, h));
    }

    /** Immutable lookup built once per catalog list. */
    public static final class Index {
        private final Map<String, Match> byName;
        private final String hardware;

        public Index(List<Entry> catalog, String hardware) {
            this.hardware = hardware;
            Map<String, String> url = new HashMap<>();
            Map<String, Match> out = new HashMap<>();
            if (catalog != null && hardware != null) {
                for (Entry e : catalog) {
                    if (e == null || e.name == null || e.name.isEmpty() || !hardware.equals(e.hardware)) continue;
                    Match prev = out.get(e.name);
                    if (prev == null) {
                        out.put(e.name, e.previewUrl == null ? NO_IMAGE : new Match(Status.MATCH, e.previewUrl));
                        url.put(e.name, e.previewUrl);
                    } else if (prev.status != Status.AMBIGUOUS && !java.util.Objects.equals(url.get(e.name), e.previewUrl)) {
                        // Two entries with one name but different artwork: no image is trustworthy.
                        out.put(e.name, AMBIGUOUS);
                    }
                }
            }
            byName = Collections.unmodifiableMap(out);
        }

        public String hardware() { return hardware; }

        /** Artwork for an installed file name on a watch whose firmware revision is {@code firmware}. */
        public Match find(String installedName, String firmware) {
            if (!sameModel(firmware, hardware)) return OTHER;
            if (installedName == null || installedName.isEmpty()) return UNNAMED;
            Match m = byName.get(installedName);
            return m == null ? NO_MATCH : m;
        }
    }
}
