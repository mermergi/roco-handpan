package com.handpan.autoplay;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Core algorithm coverage: MIDI parsing, pitch detection, key and tempo detection, pad mapping,
 * chord grouping, snapshot storage, playback timing and playlist stepping.
 *
 * <p>All of this is Android-free, so it runs on the JVM in seconds. These are the areas where the
 * real bugs have been, and none of them are visible by ear.
 */
public class TestCore {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    static void section(String title) { System.out.println("=== " + title + " ==="); }

    static String fixtures() {
        String dir = System.getenv("HANDPAN_FIXTURES");
        return dir == null ? "build/fixtures" : dir;
    }

    static MidiParser.Result midi(String name) throws Exception {
        return MidiParser.parse(Files.readAllBytes(new File(fixtures(), name).toPath()));
    }

    static List<RawNote> notes(int[][] midis, long[] starts, long dur) {
        List<RawNote> list = new ArrayList<RawNote>();
        for (int i = 0; i < midis.length; i++) {
            for (int m : midis[i]) list.add(new RawNote(m, starts[i], dur));
        }
        return list;
    }

    static List<RawNote> seq(long gap, int count) {
        List<RawNote> list = new ArrayList<RawNote>();
        for (int i = 0; i < count; i++) list.add(new RawNote(60 + (i % 5), i * gap, gap));
        return list;
    }

    /** The nine pad pitches at do = C4, for failure messages. */
    static String describePads() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 9; s++) {
            if (s > 0) sb.append(' ');
            sb.append(PadMapper.LABEL[s]).append('=')
                    .append(ScaleMapper.nameOf(PadMapper.midiForSlot(s, 0, 60)));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        section("MIDI 解析");
        MidiParser.Result scale = midi("scale.mid");
        check("音阶 7 个音", scale.notes.size() == 7, "" + scale.notes.size());
        boolean pitches = scale.notes.size() == 7;
        int[] want = {60, 62, 64, 65, 67, 69, 71};
        if (pitches) for (int i = 0; i < 7; i++) if (scale.notes.get(i).midi != want[i]) pitches = false;
        check("音高 C D E F G A B", pitches, "");
        boolean times = scale.notes.size() == 7;
        if (times) for (int i = 0; i < 7; i++) {
            if (Math.abs(scale.notes.get(i).startMs - i * 500L) > 2) times = false;
            if (Math.abs(scale.notes.get(i).durMs - 500L) > 2) times = false;
        }
        check("时间 0/500/.../3000，每音 500ms", times, "");
        check("总时长 3500ms", Math.abs(scale.lengthMs - 3500) <= 2, "" + scale.lengthMs);

        MidiParser.Result tempo = midi("tempo.mid");
        check("tempo 变化：2 个音", tempo.notes.size() == 2, "" + tempo.notes.size());
        if (tempo.notes.size() == 2) {
            check("第 1 音 0ms +500ms", tempo.notes.get(0).startMs == 0
                    && Math.abs(tempo.notes.get(0).durMs - 500) <= 2, "");
            check("第 2 音 750ms 起（480tick@500ms + 480tick@250ms）",
                    Math.abs(tempo.notes.get(1).startMs - 750) <= 2, "" + tempo.notes.get(1).startMs);
            check("第 2 音时长 250ms", Math.abs(tempo.notes.get(1).durMs - 250) <= 3, "");
        }

        MidiParser.Result running = midi("running.mid");
        check("running status 解析出 2 个音", running.notes.size() == 2, "" + running.notes.size());
        if (running.notes.size() == 2) {
            check("running status 音高 60,62", running.notes.get(0).midi == 60
                    && running.notes.get(1).midi == 62, "");
        }

        MidiParser.Result multi = midi("multi.mid");
        check("format 1 双轨合并出 3 个音", multi.notes.size() == 3, "" + multi.notes.size());

        boolean threw = false;
        try {
            midi("truncated.mid");
        } catch (java.io.IOException e) {
            threw = true;
        } catch (Throwable t) {
            threw = false;
        }
        check("截断文件抛 IOException 而不是崩溃", threw, "");
        threw = false;
        try {
            MidiParser.parse("not a midi".getBytes("UTF-8"));
        } catch (java.io.IOException e) {
            threw = true;
        } catch (Throwable t) {
            threw = false;
        }
        check("非 MIDI 抛 IOException", threw, "");

        section("音高检测（YIN）");
        int sr = 44100;
        float[] pcm = new float[(int) (sr * 1.7)];
        double[] freqs = {440.0, 523.251, 659.255};
        for (int k = 0; k < 3; k++) {
            int start = (int) (sr * (0.2 + k * 0.5));
            int len = (int) (sr * 0.5);
            for (int i = 0; i < len && start + i < pcm.length; i++) {
                double fade = Math.min(1.0, Math.min(i / 441.0, (len - i) / 441.0));
                pcm[start + i] = (float) (0.6 * Math.sin(2 * Math.PI * freqs[k] * i / sr) * fade);
            }
        }
        List<RawNote> detected = PitchDetector.detect(pcm, sr, 65f, 2100f);
        check("检出 3 个音", detected.size() == 3, "" + detected.size());
        if (detected.size() == 3) {
            check("音高 69/72/76", detected.get(0).midi == 69 && detected.get(1).midi == 72
                    && detected.get(2).midi == 76, "");
            check("起始时间 200/700/1200ms（±80ms）",
                    Math.abs(detected.get(0).startMs - 200) <= 80
                            && Math.abs(detected.get(1).startMs - 700) <= 80
                            && Math.abs(detected.get(2).startMs - 1200) <= 80, "");
        }
        check("负例：静音 -> 0 个音",
                PitchDetector.detect(new float[sr], sr, 65f, 2100f).isEmpty(), "");
        float[] noise = new float[sr];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (rnd.nextDouble() * 2 - 1) * 0.5f;
        check("负例：白噪声 -> 0 个音",
                PitchDetector.detect(noise, sr, 65f, 2100f).isEmpty(), "");

        section("自动识调");
        int[] major = {0, 2, 4, 5, 7, 9, 11};
        int keyOk = 0;
        for (int root = 0; root < 12; root++) {
            List<RawNote> scaleNotes = new ArrayList<RawNote>();
            for (int d = 0; d < 7; d++) scaleNotes.add(new RawNote(60 + root + major[d], d * 500L, 500));
            if (KeyDetector.bestKeyIndex(scaleNotes) == root) keyOk++;
        }
        check("12 个调的完整音阶都能识别", keyOk == 12, keyOk + "/12");
        int pcOk = 0;
        for (int i = 0; i < 12; i++) if (ScaleMapper.rootPitchClass(KeyDetector.KEYS[i]) == i) pcOk++;
        check("调名与音级一致（12/12）", pcOk == 12, "" + pcOk);
        check("Bb 解析为音级 10", ScaleMapper.rootPitchClass("Bb") == 10, "");

        // 回归：识调必须看整首编曲，不能只看旋律线。
        // 固件是 C 大调 I-V-vi-IV，旋律在 60+（不含 F、B），伴奏把 F 补上。
        MidiParser.Result arrangement = midi("arrangement.mid");
        List<RawNote> melodyOnly = new ArrayList<RawNote>();
        for (RawNote n : arrangement.notes) if (n.midi >= 60) melodyOnly.add(n);
        int song = KeyDetector.bestKeyIndexForSong(arrangement.notes);
        int melody = KeyDetector.bestKeyIndex(melodyOnly);
        check("整首编曲判调 = C（回归：只喂旋律线会判成 G）", song == 0,
                KeyDetector.KEYS[song] + "（应 C）");
        check("仅旋律线判调确实是错的（证明这条回归有意义）", melody != 0,
                KeyDetector.KEYS[melody]);

        section("自动识速");
        check("每 500ms -> 120 BPM", Math.abs(TempoEstimator.estimate(seq(500, 20)) - 120) <= 3, "");
        check("每 666ms -> 90 BPM", Math.abs(TempoEstimator.estimate(seq(666, 20)) - 90) <= 3, "");
        check("每 250ms 还原成 120 BPM", Math.abs(TempoEstimator.estimate(seq(250, 32)) - 120) <= 3, "");
        check("无节奏信息 -> 0", TempoEstimator.estimate(new ArrayList<RawNote>()) == 0, "");

        section("九键映射与和弦");
        // 音位表按真实琴键校：do = C4 时九个键就是 A2 E3 F3 G3 A3 B3 C4 D4 E4。
        // 老版本整张表高了一个八度，于是 E4 被判成中音3、A3 被判成低音6 —— 都是错的。
        int[] realPads = {60, 62, 64, 52, 53, 55, 57, 59, 45};
        boolean padsOk = true;
        for (int s = 0; s < 9; s++) {
            if (PadMapper.midiForSlot(s, 0, 60) != realPads[s]) padsOk = false;
        }
        check("do=C4 时九键音高 = C4 D4 E4 F3 G3 A3 B3 A2", padsOk, describePads());
        check("E4 -> 高音3", PadMapper.slotFor(64, 0, 60, true) == 2,
                "" + PadMapper.slotFor(64, 0, 60, true));
        check("E5 -> 高音3", PadMapper.slotFor(76, 0, 60, true) == 2,
                "" + PadMapper.slotFor(76, 0, 60, true));
        check("A3 -> 中音6", PadMapper.slotFor(57, 0, 60, true) == 6,
                "" + PadMapper.slotFor(57, 0, 60, true));
        check("A2 -> 低音6", PadMapper.slotFor(45, 0, 60, true) == 8,
                "" + PadMapper.slotFor(45, 0, 60, true));
        check("调外音吸附到最近音级", ScaleMapper.degreeFor(66, 0) == 4, "");

        section("选音区：离原始音高越近越好");
        // 三个八度的旋律（钢琴 MIDI 的常态）。老规则把 do 钉在最低音上，
        // 整条旋律跑到琴的上方，绝大多数音只能折回来，低音6 和中音3 一辈子用不到。
        List<RawNote> wide = new ArrayList<RawNote>();
        for (int i = 0; i < 36; i++) wide.add(new RawNote(48 + i, i * 250L, 250));
        int wideRoot = 0;
        int anchorTonic = PadMapper.anchorTonicFor(48, wideRoot);
        int chosen = PadMapper.tonicFor(wide, wideRoot);
        long anchorCost = PadMapper.displacement(wide, anchorTonic);
        long chosenCost = PadMapper.displacement(wide, chosen);
        check("三个八度的旋律会换一个音区（不再钉在最低音上）", chosen != anchorTonic,
                "锚点 " + anchorTonic + " -> 选中 " + chosen);
        check("换完之后每个音离原音高更近", chosenCost < anchorCost,
                "总偏移 " + anchorCost + " -> " + chosenCost);
        int[] wideHist = new int[9];
        for (int i = 0; i < wide.size(); i++) {
            int s = PadMapper.slotFor(wide.get(i).midi, wideRoot, chosen, true);
            if (s >= 0) wideHist[s]++;
        }
        check("低音6 用上了", wideHist[8] > 0, "" + wideHist[8]);
        check("中音3 用上了", wideHist[3] > 0, "" + wideHist[3]);
        int placed = 0;
        for (int i = 0; i < 9; i++) placed += wideHist[i];
        check("换音区后每个音都仍有键位", placed == wide.size(), placed + "/" + wide.size());

        // 音区搜索依赖 nearestStep 的窗口近似，跟暴力扫描必须完全一致。
        int stepMismatch = 0;
        for (int tonic = 15; tonic <= 123; tonic += 3) {
            for (int midi = 0; midi <= 127; midi += 1) {
                if (PadMapper.nearestStep(midi, tonic) != PadMapper.nearestStepByScan(midi, tonic)) {
                    stepMismatch++;
                }
            }
        }
        check("窗口版音级定位与暴力扫描完全一致", stepMismatch == 0, stepMismatch + " 处不一致");

        // 平局时保持老锚点：一个八度的曲子怎么放都不完美，但不能再无谓地挪。
        List<RawNote> narrow = new ArrayList<RawNote>();
        int[] scaleSteps = {0, 2, 4, 5, 7, 9, 11, 12};
        for (int i = 0; i < scaleSteps.length; i++) {
            narrow.add(new RawNote(60 + scaleSteps[i], i * 500L, 500));
        }
        check("音区搜索不会把总偏移弄得更大",
                PadMapper.displacement(narrow, PadMapper.tonicFor(narrow, 0))
                        <= PadMapper.displacement(narrow, PadMapper.anchorTonicFor(60, 0)),
                PadMapper.tonicFor(narrow, 0) + "");

        List<TapPlanner.Hit> single = TapPlanner.plan(notes(new int[][]{{60}}, new long[]{0}, 500),
                0, 60, true, 1f, 4);
        check("单击计划：单个音也要有输出（曾因整数溢出恒为空）",
                single.size() == 1 && single.get(0).slots.length == 1, "" + single.size());
        List<TapPlanner.Hit> chord = TapPlanner.plan(notes(new int[][]{{60, 64, 67}}, new long[]{0}, 500),
                0, 60, true, 1f, 4);
        check("do-mi-sol -> 一次三指", chord.size() == 1 && chord.get(0).slots.length == 3, "");
        int[] big = {60, 62, 64, 65, 67, 69, 71};
        check("和弦上限 4 生效", TapPlanner.plan(notes(new int[][]{big}, new long[]{0}, 500),
                0, 60, true, 1f, 4).get(0).slots.length == 4, "");
        check("和弦上限 9 生效", TapPlanner.plan(notes(new int[][]{big}, new long[]{0}, 500),
                0, 60, true, 1f, 9).get(0).slots.length == 7, "");
        check("和弦上限 0 按 1 处理", TapPlanner.plan(notes(new int[][]{big}, new long[]{0}, 500),
                0, 60, true, 1f, 0).get(0).slots.length == 1, "");
        check("空/null 不崩", TapPlanner.plan(null, 0, 60, true, 1f, 4).isEmpty(), "");

        section("播放计时（暂停/继续）");
        ScheduleClock clock = new ScheduleClock();
        check("未开始 elapsed=0", clock.elapsed(5000) == 0, "");
        clock.start(1000);
        check("开始后 500ms", clock.elapsed(1500) == 500, "");
        clock.pause(1500);
        check("暂停后时间冻结", clock.elapsed(3601500) == 500, "" + clock.elapsed(3601500));
        clock.resume(11500);
        check("继续后从冻结点接续", clock.elapsed(11700) == 700, "" + clock.elapsed(11700));
        ScheduleClock drift = new ScheduleClock();
        drift.start(0);
        long played = 0, now = 0;
        for (int i = 0; i < 5; i++) {
            now += 100; drift.pause(now); now += 9999; drift.resume(now); played += 100;
        }
        check("连续 5 次暂停/继续不漂移", drift.elapsed(now) == played, "" + drift.elapsed(now));

        section("曲目切换索引");
        List<String> uris = new ArrayList<String>();
        for (String u : new String[]{"A", "B", "C", "D"}) uris.add(u);
        check("查找 B 在 1", PlaylistNavigator.indexOf(uris, "B") == 1, "");
        check("末首的下一首回到开头", PlaylistNavigator.step(3, 1, 4) == 0, "");
        check("首首的上一首回到末尾", PlaylistNavigator.step(0, -1, 4) == 3, "");
        check("当前曲目不在列表时下一首取第 1 首", PlaylistNavigator.step(-1, 1, 4) == 0, "");
        check("空列表返回 -1", PlaylistNavigator.step(0, 1, 0) == -1, "");
        boolean neverSelf = true;
        for (int i = 0; i < 50; i++) if (PlaylistNavigator.randomOther(2, 4, i) == 2) neverSelf = false;
        check("随机播放 50 次都不会返回自己", neverSelf, "");

        section("存档格式");
        java.io.StringWriter out = new java.io.StringWriter();
        List<RawNote> stored = new ArrayList<RawNote>();
        for (int i = 0; i < 300; i++) stored.add(new RawNote(60 + (i % 12), i * 125L, 120L));
        SnapshotCodec.write(out, "告白气球.mid", "MIDI", 215582L, "B", 120, true, 4, 1L, stored);
        SnapshotCodec.Data restored = SnapshotCodec.read(
                new java.io.BufferedReader(new java.io.StringReader(out.toString())));
        check("存档往返：300 个音符一致", restored != null && restored.notes.size() == 300, "");
        boolean sameNotes = restored != null && restored.notes.size() == stored.size();
        if (sameNotes) for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).midi != restored.notes.get(i).midi
                    || stored.get(i).startMs != restored.notes.get(i).startMs) sameNotes = false;
        }
        check("存档往返：每个音符都对", sameNotes, "");
        check("存档：元数据恢复", restored != null && "B".equals(restored.key) && restored.bpm == 120, "");
        check("存档：版本不符 -> null",
                SnapshotCodec.read(new java.io.BufferedReader(new java.io.StringReader("version=99\nnotes=0\n"))) == null, "");
        check("存档：缺少 notes= -> null",
                SnapshotCodec.read(new java.io.BufferedReader(new java.io.StringReader("version=1\nname=x\n"))) == null, "");

        System.out.println();
        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
