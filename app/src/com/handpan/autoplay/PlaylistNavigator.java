package com.handpan.autoplay;

import java.util.List;

/**
 * Works out which song comes next when stepping through the library.
 *
 * <p>Pure index arithmetic, split out so it can be tested: wraparound in both directions, an unknown
 * current song, and a stale index are all easy to get subtly wrong, and the old inline version had no
 * coverage at all.
 */
public final class PlaylistNavigator {

    private PlaylistNavigator() {}

    /** @return position of {@code uri} in {@code uris}, or -1 when it is not there. */
    public static int indexOf(List<String> uris, String uri) {
        if (uris == null || uri == null) return -1;
        for (int i = 0; i < uris.size(); i++) {
            if (uri.equals(uris.get(i))) return i;
        }
        return -1;
    }

    /**
     * Steps {@code delta} places from {@code index}, wrapping around in both directions.
     *
     * @param index current position, or -1 when unknown
     * @return a valid index, or -1 when the list is empty
     */
    public static int step(int index, int delta, int size) {
        if (size <= 0) return -1;
        if (index < 0 || index >= size) {
            // The current song is not in the list (deleted, or replaced). "Next" then means the
            // first entry and "previous" the last, rather than stepping past the first one.
            return delta >= 0 ? 0 : size - 1;
        }
        return Math.floorMod(index + delta, size);
    }

    /**
     * Picks a random position other than {@code index}.
     *
     * @return the chosen index, or -1 when there is nothing else to choose
     */
    public static int randomOther(int index, int size, int randomInt) {
        if (size <= 1) return -1;
        int pick = Math.floorMod(randomInt, size);
        if (pick == index) pick = (pick + 1) % size;
        return pick;
    }
}
