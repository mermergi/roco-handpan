package com.handpan.autoplay;

/** The virtual practice clock: speed scaling, cycling and pathologically long gaps. */
public class TestSpeed {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        SpeedClock c = new SpeedClock();
        check("未开始 now=0", c.now() == 0, "" + c.now());
        check("未开始时 advance 不动", c.advance(5000) == 0, "" + c.advance(5000));

        c.start(1000);
        check("1.0x：过 500ms 虚拟时间也是 500", c.advance(1500) == 500, "" + c.now());
        check("1.0x：再过 500ms -> 1000", c.advance(2000) == 1000, "" + c.now());

        System.out.println("=== 倍速 ===");
        // 此刻虚拟时间 1000
        c.setSpeed(2.0f);
        check("2.0x：真实过 500ms -> 虚拟 +1000 = 2000", c.advance(2500) == 2000, "" + c.now());
        c.setSpeed(0.5f);
        check("0.5x：真实过 500ms -> 虚拟 +250 = 2250", c.advance(3000) == 2250, "" + c.now());

        System.out.println("=== 循环切换 ===");
        SpeedClock d = new SpeedClock();
        d.setSpeed(1.0f);
        d.cycleSpeed();
        check("1.0 -> 1.25", Math.abs(d.speed() - 1.25f) < 0.001f, "" + d.speed());
        d.setSpeed(2.0f);
        d.cycleSpeed();
        check("最后一档回到第一档", Math.abs(d.speed() - 0.5f) < 0.001f, "" + d.speed());
        SpeedClock e = new SpeedClock();
        e.setSpeed(9.9f);
        e.cycleSpeed();
        check("异常速度值回落到 1.0", Math.abs(e.speed() - 1.0f) < 0.001f, "" + e.speed());

        System.out.println("=== 异常输入 ===");
        SpeedClock f = new SpeedClock();
        f.start(1000);
        f.setSpeed(-3f);
        check("负速度被忽略", Math.abs(f.speed() - 1.0f) < 0.001f, "" + f.speed());
        f.setSpeed(0f);
        check("零速度被忽略", Math.abs(f.speed() - 1.0f) < 0.001f, "" + f.speed());
        check("时间倒退不产生负增长", f.advance(500) == 0, "" + f.now());
        check("超大间隔被截到 1000ms", f.advance(600000) == 1000, "" + f.now());
        f.stop();
        check("停止后不再推进", f.advance(700000) == 1000, "" + f.now());

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
