package com.handpan.autoplay;

import java.util.List;

/** Grouping upcoming presses: chords share a number, order follows the queue. */
public class TestGroups {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        // 0ms: 两个键同时；500ms: 一个；1000ms: 一个
        long[] times = {0, 0, 500, 1000};
        int[] slots = {0, 2, 5, 7};
        PracticeSession s = new PracticeSession(times, slots);

        List<PracticeSession.Group> g = s.upcomingGroups(0, 1500, 10);
        check("同时按下的两个键合成一组", g.size() == 3, "" + g.size());
        check("第一组有两个键", g.get(0).slots.length == 2, "" + g.get(0).slots.length);
        check("第一组编号为 1", g.get(0).order == 1, "" + g.get(0).order);
        check("第二组编号为 2", g.get(1).order == 2, "" + g.get(1).order);
        check("第三组编号为 3", g.get(2).order == 3, "" + g.get(2).order);
        check("分组时间正确", g.get(0).timeMs == 0 && g.get(1).timeMs == 500 && g.get(2).timeMs == 1000, "");

        // 组在 0 / 500 / 1000ms；提前量 400ms 时只有 t=0 那组到期
        check("提前量 400ms 只显示第 1 组", s.upcomingGroups(0, 400, 10).size() == 1,
                "" + s.upcomingGroups(0, 400, 10).size());
        check("提前量 600ms 显示前 2 组", s.upcomingGroups(0, 600, 10).size() == 2,
                "" + s.upcomingGroups(0, 600, 10).size());
        check("maxGroups 生效", s.upcomingGroups(0, 5000, 2).size() == 2, "");

        System.out.println("=== 编号跟着队列走，不跟整首歌 ===");
        PracticeSession t = new PracticeSession(new long[]{0, 500, 1000}, new int[]{0, 1, 2});
        t.tap(0, 0);
        List<PracticeSession.Group> g2 = t.upcomingGroups(0, 2000, 10);
        check("已判定过的不再编号", g2.size() == 2, "" + g2.size());
        check("剩下的第一个变成 1 号", g2.get(0).order == 1, "" + g2.get(0).order);

        System.out.println("=== 同一个键重复出现在不同组时不能被吃掉 ===");
        PracticeSession u = new PracticeSession(new long[]{0, 0, 500}, new int[]{3, 3, 3});
        List<PracticeSession.Group> g3 = u.upcomingGroups(0, 1000, 10);
        check("同组同键只算一次", g3.get(0).slots.length == 1, "" + g3.get(0).slots.length);
        check("下一组仍带这个键", g3.get(1).slots.length == 1 && g3.get(1).slots[0] == 3, "");

        System.out.println("=== 过期的组不再显示 ===");
        PracticeSession v = new PracticeSession(new long[]{0, 5000}, new int[]{0, 1});
        List<PracticeSession.Group> g4 = v.upcomingGroups(4000, 1500, 10);
        check("早过窗口的组被丢掉", g4.size() == 1 && g4.get(0).timeMs == 5000,
                g4.isEmpty() ? "空" : "" + g4.get(0).timeMs);

        check("空谱面返回空列表", new PracticeSession(new long[0], new int[0])
                .upcomingGroups(0, 1000, 5).isEmpty(), "");

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
