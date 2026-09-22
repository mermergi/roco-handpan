package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Polyphonic transcription coverage.
 *
 * <p>The cases that matter are the ones {@link PitchDetector} cannot do: two or three notes sounding
 * together. Its failure there is not a tuning problem - the period of a chord is not the period of
 * any of its notes - so these tests pin the behaviour that made the replacement worth writing.
 */
public class TestPoly {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " -> " + detail);
        }
    }

    static void section(String title) {
        System.out.println("=== " + title + " ===");
    }

    static final int SR = 22050;

    static double hz(int midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    /** A plucked/tuned tone with eight harmonics and a percussive attack. */
    static float[] tones(int[] midis, long[] startsMs, long durMs, int totalMs) {
        float[] buf = new float[SR * totalMs / 1000];
        for (int k = 0; k < midis.length; k++) {
            int start = (int) (startsMs[k] * SR / 1000);
            int len = (int) (durMs * SR / 1000);
            for (int i = 0; i < len && start + i < buf.length; i++) {
                double t = i / (double) SR;
                double env = Math.exp(-t / 0.8) * (1 - Math.exp(-t / 0.005));
                double v = 0;
                for (int h = 1; h <= 8; h++) {
                    double f = hz(midis[k]) * h;
                    if (f > SR * 0.45) break;
                    v += Math.sin(2 * Math.PI * f * t) / (h * Math.sqrt(h));
                }
                buf[start + i] += (float) (0.3 * env * v);
            }
        }
        return buf;
    }

    static List<RawNote> at(List<RawNote> notes, long startMs) {
        List<RawNote> out = new ArrayList<RawNote>();
        for (RawNote n : notes) if (Math.abs(n.startMs - startMs) <= 160) out.add(n);
        return out;
    }

    static boolean has(List<RawNote> notes, int midi) {
        for (RawNote n : notes) if (n.midi == midi) return true;
        return false;
    }

    static String describe(List<RawNote> notes) {
        StringBuilder sb = new StringBuilder();
        for (RawNote n : notes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(ScaleMapper.nameOf(n.midi)).append('@').append(n.startMs);
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    public static void main(String[] args) {
        section("FFT 自检");
        // 441Hz 正弦，1024 点 @22050Hz -> 频点间隔 21.5Hz，峰应在第 20/21 点
        float[] re = new float[1024];
        float[] im = new float[1024];
        for (int i = 0; i < 1024; i++) re[i] = (float) Math.sin(2 * Math.PI * 441.0 * i / SR);
        PolyPitchDetector.fft(re, im);
        int peak = 0;
        for (int i = 1; i < 512; i++) {
            if (Math.hypot(re[i], im[i]) > Math.hypot(re[peak], im[peak])) peak = i;
        }
        check("441Hz 正弦的峰值落在第 20/21 个频点", peak == 20 || peak == 21, "" + peak);

        section("单音");
        List<RawNote> a4 = PolyPitchDetector.detect(tones(new int[]{69}, new long[]{200}, 800, 1200), SR);
        check("单音 A4 能认出来", has(a4, 69), describe(a4));
        List<RawNote> c4 = PolyPitchDetector.detect(tones(new int[]{60}, new long[]{200}, 800, 1200), SR);
        check("单音 C4 能认出来", has(c4, 60), describe(c4));

        section("多音同时（单音检测器在这里会返回空）");
        List<RawNote> two = PolyPitchDetector.detect(
                tones(new int[]{60, 64}, new long[]{200, 200}, 800, 1200), SR);
        check("C4+E4 两个音都要出来", has(two, 60) && has(two, 64), describe(two));

        List<RawNote> three = PolyPitchDetector.detect(
                tones(new int[]{60, 64, 67}, new long[]{200, 200, 200}, 800, 1200), SR);
        check("C4+E4+G4 三和弦三个音都要出来",
                has(three, 60) && has(three, 64) && has(three, 67), describe(three));

        // 这是谐波求和的经典陷阱：C4 和 G4 正好是 C3 的 2、3 次谐波，
        // 不做「基频必须真的存在」的约束，整个三和弦会被报成一个低八度的 C3。
        check("三和弦不会被报成低八度的单音", !(three.size() == 1 && has(three, 48)), describe(three));

        section("时间位置");
        int[] mel = {69, 72, 76};
        long[] at = {200, 700, 1200};
        List<RawNote> seq = PolyPitchDetector.detect(tones(mel, at, 450, 2000), SR);
        boolean placed = has(seq, 69) && has(seq, 72) && has(seq, 76);
        check("三个音依次弹，音高都对", placed, describe(seq));
        if (placed) {
            long t69 = 0, t72 = 0, t76 = 0;
            for (RawNote n : seq) {
                if (n.midi == 69) t69 = n.startMs;
                if (n.midi == 72) t72 = n.startMs;
                if (n.midi == 76) t76 = n.startMs;
            }
            check("三个音的时间顺序正确且间隔合理", t69 < t72 && t72 < t76
                    && Math.abs(t69 - 200) <= 160 && Math.abs(t72 - 700) <= 160
                    && Math.abs(t76 - 1200) <= 160,
                    t69 + "/" + t72 + "/" + t76);
        }

        section("长音不会被切短");
        // 钢琴一个音要响一秒以上；背景扣除会把持续发声的部分减掉，
        // 不做「还在响就继续」的判断，每个音都会被切成 200ms 左右，整首听起来全是窟窿。
        float[] held = tones(new int[]{72}, new long[]{200}, 2200, 2800);
        List<RawNote> heldNotes = PolyPitchDetector.detect(held, SR);
        check("持续 2.2 秒的音认出来了", has(heldNotes, 72), describe(heldNotes));
        for (RawNote n : heldNotes) {
            if (n.midi != 72) continue;
            check("长音的时长接近真实的 2.2 秒（>1200ms）", n.durMs > 1200, n.durMs + "ms");
        }

        section("负例");
        check("静音 -> 0 个音", PolyPitchDetector.detect(new float[SR], SR).isEmpty(), "");
        check("空数组不崩", PolyPitchDetector.detect(new float[0], SR).isEmpty(), "");
        check("null 不崩", PolyPitchDetector.detect(null, SR).isEmpty(), "");
        java.util.Random rnd = new java.util.Random(42);
        float[] noise = new float[SR];
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (rnd.nextDouble() * 2 - 1) * 0.5f;
        List<RawNote> noiseNotes = PolyPitchDetector.detect(noise, SR);
        // 阈值是故意放松的：这个 APP 里漏掉一个音比多弹一个音更刺耳，
        // 所以宁可对白噪声敏感一点。这里只要求它别把噪声当成一首曲子。
        check("白噪声不会出一堆音", noiseNotes.size() <= 25, "" + noiseNotes.size());

        section("接进编曲流程：同时发声变成和弦");
        // 一段左手长音 + 右手旋律，正是钢琴曲的样子。留 300ms 前导，
        // 让背景估计先稳下来——真实音频开头也不会正好从第一个音开始。
        float[] piano = new float[SR * 4];
        float[] mel2 = tones(new int[]{76, 79, 83}, new long[]{300, 1300, 2300}, 900, 4000);
        float[] comp = tones(new int[]{48, 55}, new long[]{300, 300}, 3400, 4000);
        for (int i = 0; i < piano.length; i++) piano[i] = mel2[i] + comp[i] * 0.8f;
        List<RawNote> notes = PolyPitchDetector.detect(piano, SR);
        check("钢琴织体里旋律音在", has(notes, 76) && has(notes, 79) && has(notes, 83), describe(notes));
        check("左手伴奏也在（同时发声）",
                has(notes, 48) || has(notes, 50) || has(notes, 52), describe(notes));
        List<TapPlanner.Hit> hits = TapPlanner.plan(notes, 0, 60, true, 1f, 4);
        int chords = 0;
        int voices = 0;
        for (TapPlanner.Hit h : hits) {
            if (h.slots.length > 1) chords++;
            voices += h.slots.length;
        }
        check("转谱结果里出现了和弦", chords > 0, "和弦 " + chords + " 次 / " + hits.size() + " 次按键");
        check("按下键数多于按键次数", voices > hits.size(), voices + " 键 / " + hits.size() + " 次");

        System.out.println("TestPoly  结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
