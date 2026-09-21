package com.handpan.autoplay;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads a pasted link into app storage so it can be imported like any picked file.
 *
 * <p>Once the bytes are on disk the rest of the pipeline is untouched: the file is handed to
 * {@link SongLoader} through an ordinary {@code file://} URI, so saving, the song library, playback
 * and practice all work without knowing the song came from the network.
 *
 * <p>The file's <em>extension</em> is chosen from its leading bytes rather than from the URL, because
 * the score endpoints the app talks to serve files with no extension at all. {@link SongLoader}
 * dispatches on that extension, so guessing it wrongly would send MIDI bytes to the audio decoder.
 */
public final class UrlImporter {

    public interface Callback {
        void onReady(Uri uri, String name);

        void onError(String message);
    }

    /** Refuses absurd downloads; the biggest real score here is a couple of hundred kilobytes. */
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    /** For the page fetch that only supplies a song title. */
    private static final int MAX_PAGE_BYTES = 512 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** Some hosts serve an empty body to unknown clients; this is the client they expect. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UrlImporter() {}

    public static void fetch(final Context context, final String pasted, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String direct = ScoreLink.resolve(pasted);
                    if (direct == null) {
                        fail(callback, "这个链接用不了。\n"
                                + "支持两种：曲谱站里那一页的分享链接，或者直接指向 "
                                + "mid / midi / smf / wav / mp3 / txt 文件的链接。");
                        return;
                    }

                    // Best effort only: a missing title costs nothing but a duller file name.
                    String title = null;
                    if (ScoreLink.isScorePage(pasted)) {
                        title = safeTitle(pasted);
                    }

                    byte[] data = download(direct, MAX_BYTES);
                    if (data.length == 0) {
                        fail(callback, "下载回来是空文件。");
                        return;
                    }

                    String extension = ScoreLink.extensionFor(data, direct);
                    if (extension == null) {
                        fail(callback, "下载回来了 " + data.length + " 字节，但认不出这是什么文件。");
                        return;
                    }

                    String name = ScoreLink.fileName(title, direct) + extension;
                    Uri uri = write(app, name, data);
                    final Uri done = uri;
                    final String shown = name;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onReady(done, shown);
                        }
                    });
                } catch (final Throwable error) {
                    fail(callback, "下载失败：" + describe(error));
                }
            }
        }, "url-import").start();
    }

    // ------------------------------------------------------------------ internals

    private static Uri write(Context app, String name, byte[] data) throws IOException {
        File dir = new File(app.getFilesDir(), "imports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("建不了目录 " + dir.getAbsolutePath());
        }
        File out = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(data);
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        return Uri.fromFile(out);
    }

    private static String safeTitle(String pageUrl) {
        try {
            byte[] page = download(pageUrl, MAX_PAGE_BYTES);
            return ScoreLink.titleFromHtml(new String(page, "UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] download(String url, int limit) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("服务器返回 HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
                byte[] buffer = new byte[1 << 16];
                int n;
                int total = 0;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) {
                        throw new IOException("文件超过 " + (limit / 1024 / 1024) + "MB，不下载");
                    }
                    bos.write(buffer, 0, n);
                }
                return bos.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void fail(Callback callback, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : " " + message);
    }
}
