package com.handpan.autoplay;

/**
 * A performance recorded on the pad board is stored as an ordinary note list, so playback, the song
 * library and snapshot saving all work unchanged. That only holds if pad -> note -> pad is exact, so
 * this checks every pad against every key and a spread of octaves.
 */
public class TestRoundTrip {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) {
        int[] roots = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        int[] tonics = {48, 55, 60, 62, 67, 71, 72};
        int bad = 0;
        int total = 0;
        for (int root : roots) {
            for (int tonic : tonics) {
                for (int slot = 0; slot < PadMapper.SLOTS; slot++) {
                    int midi = PadMapper.midiForSlot(slot, root, tonic);
                    int back = PadMapper.slotFor(midi, root, tonic, true);
                    total++;
                    if (back != slot) {
                        bad++;
                        if (bad <= 8) {
                            System.out.println("      失败 root=" + KeyDetector.KEYS[root]
                                    + " tonic=" + tonic + " slot=" + slot + "(" + PadMapper.LABEL[slot]
                                    + ") midi=" + midi + " -> slot=" + back);
                        }
                    }
                }
            }
        }
        check("琴键→音符→琴键 全部可精确往返（" + total + " 组）", bad == 0, "失败 " + bad + " 组");

        // 关闭八度感知时不同琴键会合并，只要求不返回 -1
        int mapped = 0;
        for (int slot = 0; slot < PadMapper.SLOTS; slot++) {
            if (PadMapper.slotFor(PadMapper.midiForSlot(slot, 0, 60), 0, 60, false) >= 0) mapped++;
        }
        check("关闭八度感知时仍能映射到琴键", mapped == PadMapper.SLOTS, "只有 " + mapped);

        check("越界 slot 返回 -1",
                PadMapper.midiForSlot(-1, 0, 60) == -1 && PadMapper.midiForSlot(99, 0, 60) == -1, "");

        // 同一琴键在不同调下音高应随之改变
        int cMajor = PadMapper.midiForSlot(0, 0, 60);
        int fMajor = PadMapper.midiForSlot(4, 5, 60);
        check("不同调下同一琴键音高不同", cMajor != fMajor, cMajor + " vs " + fMajor);

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
