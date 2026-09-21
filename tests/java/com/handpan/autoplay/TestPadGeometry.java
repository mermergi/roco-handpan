package com.handpan.autoplay;

/** Estimating the pad radius from the calibrated spacing. */
public class TestPadGeometry {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        // 间距 56px 的一排键 -> 半径约 56/2.6 ≈ 21.5px
        float[][] even = new float[9][];
        for (int i = 0; i < 9; i++) even[i] = new float[]{100 + i * 56, 200};
        float r = PadGeometry.estimatePadRadius(even, 22f, 12f, 40f);
        check("间距 56 -> 半径约 21.5", Math.abs(r - 21.5f) < 0.6f, "" + r);

        // 更密的屏：间距 30 -> 11.5，低于下限 12 被抬高
        float[][] dense = new float[9][];
        for (int i = 0; i < 9; i++) dense[i] = new float[]{100 + i * 30, 200};
        check("过密时被下限托住", PadGeometry.estimatePadRadius(dense, 22f, 12f, 40f) == 12f, "");

        // 很疏：间距 200 -> 77，被上限压到 40
        float[][] sparse = new float[9][];
        for (int i = 0; i < 9; i++) sparse[i] = new float[]{100 + i * 200, 200};
        check("过疏时被上限压住", PadGeometry.estimatePadRadius(sparse, 22f, 12f, 40f) == 40f, "");

        check("只有一个键时用回退值",
                PadGeometry.estimatePadRadius(new float[][]{new float[]{1, 1}, null},
                        22f, 12f, 40f) == 22f, "");
        check("全为 null 时用回退值",
                PadGeometry.estimatePadRadius(new float[][]{null, null}, 22f, 12f, 40f) == 22f, "");
        check("null 数组用回退值", PadGeometry.estimatePadRadius(null, 22f, 12f, 40f) == 22f, "");
        check("两个键完全重合时用回退值",
                PadGeometry.estimatePadRadius(new float[][]{new float[]{5, 5}, new float[]{5, 5}},
                        22f, 12f, 40f) == 22f, "");

        // 真实布局：上排 3 个间距 56，中排 5 个间距 65，上下排相距 96
        float[][] real = new float[9][];
        real[0] = new float[]{320, 180}; real[1] = new float[]{376, 180}; real[2] = new float[]{432, 180};
        for (int i = 0; i < 5; i++) real[3 + i] = new float[]{100 + i * 65, 468};
        real[8] = new float[]{500, 756};
        float rr = PadGeometry.estimatePadRadius(real, 22f, 12f, 40f);
        check("真实布局算出的半径在合理区间（18~24）", rr >= 18f && rr <= 24f, "" + rr);

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
