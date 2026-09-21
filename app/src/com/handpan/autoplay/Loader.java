package com.handpan.autoplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Parses a document off the main thread and reports back on it.
 *
 * <p>Shared by the songs screen (import, manual parse) and the play screen (loading the next song in
 * sequence or random mode), so the background-thread and error-handling rules live in one place.
 *
 * <p>The catch is {@link Throwable}, not {@link Exception}: decoding a multi-minute track can throw
 * {@code OutOfMemoryError}, and a background thread that dies silently would leave the UI showing
 * stale state with no hint of what happened.
 */
public final class Loader {

    public interface Callback {
        void onLoaded(Uri uri, SongLoader.Song song);

        void onError(Uri uri, Throwable error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Loader() {}

    public static void load(final Context context, final Uri uri, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SongLoader.Song song = SongLoader.load(app, uri);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onLoaded(uri, song);
                        }
                    });
                } catch (final Throwable error) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(uri, error);
                        }
                    });
                }
            }
        }, "song-load").start();
    }

    public static String describe(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.length() == 0
                ? "" : " " + message);
    }
}
