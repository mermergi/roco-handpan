package com.handpan.autoplay;

/** Judgement windows, auto-miss and scoring for the practice mode. */
public class TestPractice {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    static PracticeSession session(long[] times, int[] slots) {
        return new PracticeSession(times, slots);
    }

    public static void main(String[] args) {
        System.out.println("=== 判定窗口 ===");
        long[] t = {1000, 2000, 3000, 4000};
        int[] s = {0, 1, 2, 3};
        PracticeSession a = session(t, s);
        check("正点 -> PERFECT", a.tap(0, 1000) == PracticeSession.PERFECT, "");
        check("晚 50ms -> PERFECT", a.tap(1, 2050) == PracticeSession.PERFECT, "");
        check("早 150ms -> GOOD", a.tap(2, 2850) == PracticeSession.GOOD, "");
        check("晚 330ms -> OK", a.tap(3, 4330) == PracticeSession.OK, "");
        check("全部判定完毕", a.isFinished(), "");
        check("perfect=2 good=1 ok=1 miss=0",
                a.perfect() == 2 && a.good() == 1 && a.ok() == 1 && a.miss() == 0,
                a.perfect() + "/" + a.good() + "/" + a.ok() + "/" + a.miss());

        System.out.println("=== 打空气不算分、也不算失误 ===");
        PracticeSession b = session(new long[]{1000}, new int[]{0});
        check("按错琴键 -> STRAY", b.tap(5, 1000) == PracticeSession.STRAY, "");
        check("STRAY 不消耗目标", !b.isFinished(), "");
        check("偏差过大 -> STRAY", b.tap(0, 1900) == PracticeSession.STRAY, "");
        check("窗口内仍可命中", b.tap(0, 1000) == PracticeSession.PERFECT, "");
        check("同一个目标不能重复得分", b.tap(0, 1000) == PracticeSession.STRAY, "");

        System.out.println("=== 漏拍自动记 Miss ===");
        PracticeSession c = session(new long[]{1000, 2000}, new int[]{0, 1});
        check("窗口未过不判", c.consumeMisses(1300) == 0, "" + c.consumeMisses(1300));
        check("过窗口判一次", c.consumeMisses(1400) == 1, "");
        check("不会重复判", c.consumeMisses(1900) == 0, "");
        check("miss=1", c.miss() == 1, "" + c.miss());
        check("未结束", !c.isFinished(), "");

        System.out.println("=== 同一琴键多次出现时取最近的一次 ===");
        PracticeSession d = session(new long[]{1000, 1200, 3000}, new int[]{0, 0, 0});
        check("t=1180 命中 1200 那次（GOOD/PERFECT 均可）",
                d.tap(0, 1180) != PracticeSession.STRAY, "");
        check("剩下两个仍待判", d.judgedCount() == 1, "" + d.judgedCount());
        check("t=1030 命中 1000 那次", d.tap(0, 1030) == PracticeSession.PERFECT, "");

        System.out.println("=== dueSlots：提前量决定哪些琴键要亮计时圈 ===");
        PracticeSession e = session(new long[]{1000, 1500, 9000}, new int[]{0, 1, 2});
        int[] due = e.dueSlots(950, 600);
        check("只包含 600ms 内到期的两个", due.length == 2, "" + due.length);
        int[] due2 = e.dueSlots(950, 100);
        check("提前量收窄到 100ms 就只剩一个", due2.length == 1, "" + due2.length);

        System.out.println("=== 准确率 ===");
        PracticeSession f = session(new long[]{0, 0, 0, 0}, new int[]{0, 1, 2, 3});
        f.tap(0, 0); f.tap(1, 0); f.tap(2, 0); f.tap(3, 0);
        check("全 PERFECT -> 100%", f.accuracyPercent() == 100, "" + f.accuracyPercent());
        PracticeSession g = session(new long[]{0, 0, 0, 0}, new int[]{0, 1, 2, 3});
        g.consumeMisses(100000);
        check("全 Miss -> 0%", g.accuracyPercent() == 0, "" + g.accuracyPercent());

        System.out.println("=== 边界 ===");
        PracticeSession h = session(new long[0], new int[0]);
        check("空谱面：一开始就结束", h.isFinished(), "");
        check("空谱面：准确率 0 不除零", h.accuracyPercent() == 0, "");
        check("空谱面：nextPendingSlot = -1", h.nextPendingSlot() == -1, "");
        PracticeSession i = session(null, null);
        check("null 输入不崩", i.total() == 0, "");
        PracticeSession j = session(new long[]{1000}, new int[]{0});
        check("nextPendingSlot 正确", j.nextPendingSlot() == 0, "" + j.nextPendingSlot());
        check("nextPendingTime 正确", j.nextPendingTime() == 1000, "" + j.nextPendingTime());

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
