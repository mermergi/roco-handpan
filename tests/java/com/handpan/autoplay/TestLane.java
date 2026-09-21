package com.handpan.autoplay;

import java.util.List;

/** The note lane must never draw two tiles on top of each other. */
public class TestLane {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    static boolean noOverlap(List<LaneLayout.Placement> placed, float itemW, float gap) {
        for (int i = 1; i < placed.size(); i++) {
            if (placed.get(i).x < placed.get(i - 1).x + itemW + gap - 0.01f) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        // 参数必须自洽：最远的一个方块刚好贴住右边界
        final float hitX = 18, usable = 160, itemW = 34, gap = 3;
        final float limit = hitX + usable + itemW;

        System.out.println("=== 核心：密集音符也不许重叠 ===");
        // 每 250ms 一个音，共 12 个，提前量 2800ms
        long[] dense = new long[12];
        for (int i = 0; i < 12; i++) dense[i] = i * 250L;
        final float maxGap = 6;
        List<LaneLayout.Placement> r = LaneLayout.place(dense, 0, 2800, hitX, usable, itemW, gap, maxGap, limit);
        check("密集时确实画不下全部", r.size() < dense.length, "" + r.size());
        check("画出来的都不重叠", noOverlap(r, itemW, gap), "");
        check("第一个还在 now 线上", !r.isEmpty() && Math.abs(r.get(0).x - hitX) < 0.01f,
                r.isEmpty() ? "空" : "" + r.get(0).x);
        check("全部在右边界内", allInside(r, itemW, limit), "");

        System.out.println("=== 稀疏时不许摊开 ===");
        long[] sparse = {0, 1400, 2800};
        List<LaneLayout.Placement> s2 = LaneLayout.place(sparse, 0, 2800, hitX, usable, itemW, gap, maxGap, limit);
        check("三个都能放下", s2.size() == 3, "" + s2.size());
        check("间隔不超过 maxGap（不再摊到远处）",
                s2.get(1).x <= s2.get(0).x + itemW + maxGap + 0.01f
                        && s2.get(2).x <= s2.get(1).x + itemW + maxGap + 0.01f,
                s2.get(0).x + "," + s2.get(1).x + "," + s2.get(2).x);
        check("稀疏时也不重叠", noOverlap(s2, itemW, gap), "");
        check("稀疏时最远的一个不会跑到右边界外", allInside(s2, itemW, limit), "");

        System.out.println("=== 边界 ===");
        check("空输入返回空", LaneLayout.place(new long[0], 0, 2800, hitX, usable, itemW, gap, maxGap, limit).isEmpty(), "");
        check("null 返回空", LaneLayout.place(null, 0, 2800, hitX, usable, itemW, gap, maxGap, limit).isEmpty(), "");
        check("宽度为 0 时只显示不下则返回空",
                LaneLayout.place(dense, 0, 2800, hitX, 0, itemW, gap, maxGap, hitX + itemW - 1).isEmpty(), "");
        // 已经过时间的音不许画到 now 线左边
        long[] past = {-5000, 0};
        List<LaneLayout.Placement> p2 = LaneLayout.place(past, 0, 2800, hitX, usable, itemW, gap, maxGap, limit);
        check("过期的音被夹在 now 线上", !p2.isEmpty() && p2.get(0).x >= hitX, "");
        // 只有一个音的窄条也要能画
        List<LaneLayout.Placement> one = LaneLayout.place(new long[]{0}, 0, 2800, hitX, usable, itemW, gap, maxGap, hitX + itemW + 1);
        check("勉强放得下一个", one.size() == 1, "" + one.size());

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static boolean allInside(List<LaneLayout.Placement> placed, float itemW, float limit) {
        for (LaneLayout.Placement p : placed) {
            if (p.x + itemW > limit + 0.01f) return false;
        }
        return true;
    }
}
