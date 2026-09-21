package com.handpan.autoplay;

/** Matching a touch coordinate onto a calibrated pad. */
public class TestHitTester {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        // 三个琴键：0 在 (100,100)，1 在 (100,300)，2 在 (500,500)
        float[][] pads = new float[9][];
        pads[0] = new float[]{100, 100};
        pads[1] = new float[]{100, 300};
        pads[2] = new float[]{500, 500};

        check("正中命中", PadHitTester.slotAt(pads, 100, 100, 50) == 0, "");
        check("范围内命中", PadHitTester.slotAt(pads, 130, 130, 50) == 0, "");
        check("超出范围 -> -1", PadHitTester.slotAt(pads, 200, 200, 50) == -1, "");
        check("附近另一个琴键", PadHitTester.slotAt(pads, 105, 290, 50) == 1, "");
        check("取最近的那个", PadHitTester.slotAt(pads, 100, 150, 200) == 0, "");
        check("未校准的琴键被跳过", PadHitTester.slotAt(pads, 0, 0, 500) == 0, "");
        check("null 不崩", PadHitTester.slotAt(null, 1, 1, 10) == -1, "");
        check("坐标为 null 的琴键不崩", PadHitTester.slotAt(new float[][]{null, new float[]{10, 10}}, 10, 10, 5) == 1, "");
        check("半径恰好相等算命中", PadHitTester.slotAt(new float[][]{{0, 0}}, 30, 40, 50) == 0, "");
        check("半径差一点点不算", PadHitTester.slotAt(new float[][]{{0, 0}}, 30, 40, 49) == -1, "");

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
