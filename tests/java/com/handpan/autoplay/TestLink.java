package com.handpan.autoplay;

/**
 * Link-import coverage: turning a pasted URL into a download URL, naming the file, and picking its
 * type from the leading bytes.
 *
 * <p>These are the parts that break silently. A score page URL that resolves to HTML would make the
 * audio decoder chew on a web page; a file typed as {@code .mid} when it is really text would send
 * 简谱 to the MIDI parser and produce "no playable notes" with no hint why.
 */
public class TestLink {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " -> " + detail);
        }
    }

    static void eq(String name, String got, String want) {
        check(name, want == null ? got == null : want.equals(got), "得到 " + got + "，应为 " + want);
    }

    static void section(String title) {
        System.out.println("=== " + title + " ===");
    }

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    static byte[] text(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new byte[0];
        }
    }

    public static void main(String[] args) {
        final String id = "1dcb32dc-3421-4163-98cd-a1f9ea2cdf9b";
        final String api = "https://rocomusic.cn/api/scores/" + id + "/file";

        section("分享链接 -> 直链");
        eq("分享页（带 ?via=copy）", ScoreLink.resolve("https://rocomusic.cn/scores/" + id + "?via=copy"), api);
        eq("分享页（无参数）", ScoreLink.resolve("https://rocomusic.cn/scores/" + id), api);
        eq("分享页（结尾斜杠）", ScoreLink.resolve("https://rocomusic.cn/scores/" + id + "/"), api);
        eq("已经是接口直链", ScoreLink.resolve(api), api);
        eq("带 www 前缀", ScoreLink.resolve("https://www.rocomusic.cn/scores/" + id),
                "https://www.rocomusic.cn/api/scores/" + id + "/file");
        eq("前后有空格也能认", ScoreLink.resolve("  https://rocomusic.cn/scores/" + id + "  "), api);
        eq("大写 HTTP", ScoreLink.resolve("HTTPS://rocomusic.cn/scores/" + id), api);

        section("普通直链");
        eq("mid 直链原样通过", ScoreLink.resolve("https://example.com/a/Lemon.mid"),
                "https://example.com/a/Lemon.mid");
        eq("大写扩展名", ScoreLink.resolve("https://example.com/a/Lemon.MIDI"),
                "https://example.com/a/Lemon.MIDI");
        eq("简谱直链", ScoreLink.resolve("https://example.com/a/x.txt"), "https://example.com/a/x.txt");

        section("认不出来的链接");
        eq("不是链接", ScoreLink.resolve("midi 文件在哪"), null);
        eq("空串", ScoreLink.resolve(""), null);
        eq("null", ScoreLink.resolve(null), null);
        eq("非 http 协议", ScoreLink.resolve("ftp://example.com/a.mid"), null);
        eq("没有扩展名的普通网页", ScoreLink.resolve("https://example.com/song"), null);
        eq("曲谱站的非曲谱页", ScoreLink.resolve("https://rocomusic.cn/library"), null);
        eq("扩展名只是子串不算数", ScoreLink.resolve("https://example.com/midi"), null);

        section("是不是曲谱页");
        check("分享页是", ScoreLink.isScorePage("https://rocomusic.cn/scores/" + id + "?via=copy"), "");
        check("接口直链不是", !ScoreLink.isScorePage(api), "");
        check("普通网页不是", !ScoreLink.isScorePage("https://example.com/a.mid"), "");

        section("从网页里取歌名");
        eq("og:title", ScoreLink.titleFromHtml(
                "<meta property=\"og:title\" content=\"Lemon 米津玄师\"/><meta name=\"x\"/>"),
                "Lemon 米津玄师");
        eq("og:title 属性顺序反过来", ScoreLink.titleFromHtml(
                "<meta content=\"Lemon 米津玄师\" property=\"og:title\"/>"), "Lemon 米津玄师");
        eq("退回 <title> 并去掉站名", ScoreLink.titleFromHtml(
                "<html><head><title>Lemon 米津玄师 · RocoMusic 洛克乐谱</title></head></html>"),
                "Lemon 米津玄师");
        eq("都没有就是 null", ScoreLink.titleFromHtml("<html><body>hi</body></html>"), null);
        eq("null 输入", ScoreLink.titleFromHtml(null), null);

        section("按文件头判断类型");
        eq("MThd -> mid", ScoreLink.extensionFor(bytes('M', 'T', 'h', 'd', 0, 0, 0, 6), null), ".mid");
        eq("RIFF/WAVE -> wav",
                ScoreLink.extensionFor(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'), null),
                ".wav");
        eq("ID3 -> mp3", ScoreLink.extensionFor(bytes('I', 'D', '3', 3, 0, 0, 0, 0), null), ".mp3");
        eq("裸 MP3 帧同步", ScoreLink.extensionFor(bytes(0xFF, 0xFB, 0x90, 0x00, 0, 0, 0, 0), null), ".mp3");
        eq("fLaC -> flac", ScoreLink.extensionFor(bytes('f', 'L', 'a', 'C', 0, 0, 0, 0), null), ".flac");
        eq("OggS -> ogg", ScoreLink.extensionFor(bytes('O', 'g', 'g', 'S', 0, 0, 0, 0), null), ".ogg");
        eq("简谱文本 -> txt", ScoreLink.extensionFor(text("1 2 3 5 | 6 5 3 -"), null), ".txt");
        eq("中文注释的简谱也是 txt",
                ScoreLink.extensionFor(text("# 天空之城\n1 2 3 5 6 5 3"), null), ".txt");
        eq("认不出的二进制 -> null",
                ScoreLink.extensionFor(bytes(0x00, 0x01, 0x02, 0x03, 0xFF, 0xFE, 0x7F, 0x10), null), null);
        eq("文件头认不出时退回 URL 扩展名",
                ScoreLink.extensionFor(bytes(0x00, 0x01, 0x02, 0x03, 0xFF, 0xFE, 0x7F, 0x10),
                        "https://example.com/a/b.wav"), ".wav");
        eq("文件头认得出时优先文件头",
                ScoreLink.extensionFor(bytes('M', 'T', 'h', 'd', 0, 0, 0, 6), "https://x/a.mp3"), ".mid");
        eq("短到读不出 -> 退回 URL", ScoreLink.extensionFor(bytes(1, 2), "https://x/a.mid"), ".mid");

        section("文件命名");
        eq("用歌名", ScoreLink.fileName("Lemon 米津玄师", null), "Lemon 米津玄师");
        eq("没有歌名就用链接尾段", ScoreLink.fileName(null, "https://example.com/a/Lemon.mid"), "Lemon");
        eq("链接也读不出就 song", ScoreLink.fileName(null, null), "song");
        eq("去掉文件名里的非法字符", ScoreLink.fileName("a/b:c*d?e", null), "a b c d e");
        eq("连字符和下划线保留", ScoreLink.fileName("Lemon-2024_v2", null), "Lemon-2024_v2");
        eq("全是符号就 song", ScoreLink.fileName("///", null), "song");
        String longName = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        check("超长歌名截断到 48 字以内", ScoreLink.fileName(longName, null).length() <= 48,
                "" + ScoreLink.fileName(longName, null).length());

        System.out.println("TestLink  结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
