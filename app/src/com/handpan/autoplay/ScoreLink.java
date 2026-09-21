package com.handpan.autoplay;

import java.net.URI;
import java.util.Locale;

/**
 * Turns a link the user pasted into something the app can actually import.
 *
 * <p>People do not hand each other files, they hand each other links. A score site's share button
 * copies a <em>page</em> URL (`/scores/&lt;id&gt;?via=copy`), which is HTML, not music. Importing
 * that needs two separate translations, and both are pure string work, so both live here and are
 * covered by tests:
 *
 * <ol>
 *   <li>{@link #resolve} maps a known score page onto the direct file endpoint behind it, and
 *       passes a plain file URL straight through.</li>
 *   <li>{@link #extensionFor} decides the file's kind from its <em>leading bytes</em> rather than
 *       from the URL. That matters because {@code .../api/scores/&lt;id&gt;/file} carries no
 *       extension at all, and because {@link SongLoader} dispatches on the file name: getting the
 *       extension wrong means the wrong parser runs on the bytes.</li>
 * </ol>
 *
 * <p>Only the site's public endpoints are used - the same ones its own web player calls when a
 * visitor opens the page - and one link imports exactly one score.
 */
public final class ScoreLink {

    /** Hosts whose /scores/&lt;id&gt; pages are backed by a direct file endpoint. */
    private static final String[] SCORE_HOSTS = {"rocomusic.cn"};

    /** Extensions the app can parse or decode, longest first so ".midi" is not cut to ".mid". */
    private static final String[] MEDIA_EXTENSIONS = {
            ".jianpu", ".midi", ".flac", ".opus", ".m4a", ".aac", ".ogg", ".mp3", ".wav", ".mid",
            ".smf", ".txt", ".jp",
    };

    private ScoreLink() {}

    /**
     * @return a URL that serves the score's bytes, or null when the app cannot handle the link.
     */
    public static String resolve(String pasted) {
        if (pasted == null) return null;
        String s = pasted.trim();
        if (!isHttp(s)) return null;

        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null) return null;
        host = host.toLowerCase(Locale.ROOT);

        if (isScoreHost(host)) {
            String id = scoreId(path);
            if (id != null) return "https://" + host + "/api/scores/" + id + "/file";
            if (path.endsWith("/file")) return "https://" + host + path;
        }
        return hasMediaExtension(path) ? s : null;
    }

    /** True when the link is a score page rather than a direct file. */
    public static boolean isScorePage(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            return host != null && isScoreHost(host.toLowerCase(Locale.ROOT))
                    && scoreId(uri.getPath()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pulls the score's name out of its page.
     *
     * <p>Prefers {@code og:title}, which is the bare title; the {@code <title>} tag appends the
     * site's own name and would end up in the user's song list.
     */
    public static String titleFromHtml(String html) {
        if (html == null) return null;
        String og = between(html, "property=\"og:title\" content=\"", "\"");
        if (og == null) og = between(html, "content=\"", "\" property=\"og:title\"");
        if (og == null) og = between(html, "<title>", "</title>");
        if (og == null) return null;
        og = og.trim();
        int dot = og.indexOf(" · ");
        if (dot > 0) og = og.substring(0, dot).trim();
        return og.length() == 0 ? null : og;
    }

    /**
     * File extension implied by the first bytes, falling back to the URL's own extension.
     *
     * @return an extension including the dot, or null when the kind cannot be determined.
     */
    public static String extensionFor(byte[] head, String url) {
        if (head != null) {
            if (tag(head, 0, "MThd")) return ".mid";
            if (tag(head, 0, "RIFF") && tag(head, 8, "WAVE")) return ".wav";
            if (tag(head, 0, "fLaC")) return ".flac";
            if (tag(head, 0, "OggS")) return ".ogg";
            if (tag(head, 0, "ID3")) return ".mp3";
            // Bare MPEG audio frame sync: 11 set bits.
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
                return ".mp3";
            }
            if (looksLikeText(head)) return ".txt";
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        for (String ext : MEDIA_EXTENSIONS) {
            if (lower.endsWith(ext) || lower.contains(ext + "?")) return ext;
        }
        return null;
    }

    /** A readable file name for the imported score, without the extension. */
    public static String fileName(String title, String url) {
        String base = title == null ? "" : title.trim();
        if (base.length() == 0 && url != null) {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int slash = path.lastIndexOf('/');
            base = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < base.length() && sb.length() < 48; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                lastSpace = false;
            } else if (c == '-' || c == '_') {
                sb.append(c);
                lastSpace = false;
            } else if (!lastSpace && sb.length() > 0) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? "song" : out;
    }

    // ------------------------------------------------------------------ internals

    private static boolean isHttp(String s) {
        return s.regionMatches(true, 0, "http://", 0, 7)
                || s.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean isScoreHost(String host) {
        for (String h : SCORE_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    /** The score id from a page path such as {@code /scores/<uuid>}, or null. */
    private static String scoreId(String path) {
        if (path == null) return null;
        int marker = path.lastIndexOf("/scores/");
        if (marker < 0) return null;
        String rest = path.substring(marker + "/scores/".length());
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.length() < 8 || rest.length() > 64) return null;
        if (rest.indexOf('/') >= 0) return null; // /api/scores/<id>/file is not a page
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != '-') return null;
        }
        return rest;
    }

    private static boolean hasMediaExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : MEDIA_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean tag(byte[] data, int offset, String text) {
        if (data == null || offset + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) {
            if ((data[offset + i] & 0xFF) != text.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Text sniff for 简谱 files, which have no magic number.
     *
     * <p>Deliberately strict: the whole prefix must be printable ASCII, whitespace or valid UTF-8
     * continuation bytes. Binary formats that reach here have already failed every earlier check,
     * so a false positive would need a file that is genuinely text.
     */
    private static boolean looksLikeText(byte[] head) {
        int n = Math.min(head.length, 512);
        if (n < 8) return false;
        int i = 0;
        while (i < n) {
            int b = head[i] & 0xFF;
            if (b == 9 || b == 10 || b == 13) {
                i++;
            } else if (b >= 32 && b <= 126) {
                i++;
            } else if (b >= 0xC2 && b <= 0xF4) {
                int extra = b < 0xE0 ? 1 : (b < 0xF0 ? 2 : 3);
                if (i + extra >= n) return true; // truncated by the prefix window, not by the file
                for (int k = 1; k <= extra; k++) {
                    if ((head[i + k] & 0xC0) != 0x80) return false;
                }
                i += extra + 1;
            } else {
                return false;
            }
        }
        return true;
    }

    private static String between(String haystack, String open, String close) {
        int a = haystack.indexOf(open);
        if (a < 0) return null;
        int b = haystack.indexOf(close, a + open.length());
        if (b < 0) return null;
        return haystack.substring(a + open.length(), b);
    }
}
