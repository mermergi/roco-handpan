package com.handpan.autoplay;

/**
 * The song currently loaded, shared between screens.
 *
 * <p>The app is split across three activities (play, songs, settings) and there is no fragment or
 * ViewModel layer to hold state, so the loaded song lives here. A process-wide static is safe in this
 * case: everything runs in one process, the accessibility service already keeps that process alive
 * for playback, and the value is only ever a parsed note list.
 *
 * <p>{@link #version()} increments on every change so a screen resuming from the background can tell
 * whether it still shows current data.
 */
public final class Session {

    private static String sUri;
    private static SongLoader.Song sSong;
    private static long sVersion;

    private Session() {}

    public static synchronized void set(String uri, SongLoader.Song song) {
        sUri = uri;
        sSong = song;
        sVersion++;
    }

    public static synchronized void clear() {
        sUri = null;
        sSong = null;
        sVersion++;
    }

    /** Source document URI, or null when nothing is loaded. */
    public static synchronized String uri() {
        return sUri;
    }

    /** The parsed song, or null when nothing is loaded. */
    public static synchronized SongLoader.Song song() {
        return sSong;
    }

    public static synchronized long version() {
        return sVersion;
    }
}
