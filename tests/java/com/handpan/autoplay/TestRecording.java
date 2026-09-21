package com.handpan.autoplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** The recorded-performance file format must survive round trips and reject junk. */
public class TestRecording {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    static String write(String name, List<RecordingCodec.Hit> hits) throws IOException {
        StringWriter sw = new StringWriter();
        RecordingCodec.write(sw, name, 1699999999999L, hits);
        return sw.toString();
    }

    static RecordingCodec.Data read(String text) throws IOException {
        return RecordingCodec.read(new BufferedReader(new StringReader(text)));
    }

    static List<RecordingCodec.Hit> hits(int n) {
        List<RecordingCodec.Hit> list = new ArrayList<RecordingCodec.Hit>();
        for (int i = 0; i < n; i++) list.add(new RecordingCodec.Hit(i % PadMapper.SLOTS, i * 250L));
        return list;
    }

    public static void main(String[] args) throws Exception {
        List<RecordingCodec.Hit> src = hits(300);
        RecordingCodec.Data data = read(write("我的弹奏", src));
        check("读取成功", data != null, "null");
        check("name 恢复", data != null && "我的弹奏".equals(data.name), data == null ? "-" : data.name);
        check("savedAt 恢复", data != null && data.savedAt == 1699999999999L, "");
        check("hits 数量 300", data != null && data.hits.size() == 300, data == null ? "-" : "" + data.hits.size());
        boolean same = true;
        if (data != null) {
            for (int i = 0; i < src.size(); i++) {
                if (src.get(i).slot != data.hits.get(i).slot || src.get(i).atMs != data.hits.get(i).atMs) {
                    same = false;
                    break;
                }
            }
        }
        check("每次按键的琴键与时刻都一致", same, "有偏差");
        check("lengthMs 取最后一次按下的时刻", data != null && data.lengthMs() == 299 * 250L,
                data == null ? "-" : "" + data.lengthMs());

        check("空录音往返不崩", read(write("空", new ArrayList<RecordingCodec.Hit>())).hits.isEmpty(), "");
        check("版本不符 -> null", read("version=99\nhits=0\n") == null, "");
        check("缺少 hits= 头 -> null", read("version=1\nname=x\n") == null, "");
        check("空内容 -> null", read("") == null, "");

        // 六行数据里只有三行合法：坏行、越界琴键(99)、负数时刻(-5) 都应被丢掉
        RecordingCodec.Data bad = read("version=1\nname=x\nhits=6\n3,0\n坏行\n5,250\n99,500\n1,-5\n7,750\n");
        check("坏行/越界琴键/负数时刻被跳过，保留 3 条", bad != null && bad.hits.size() == 3,
                bad == null ? "null" : "" + bad.hits.size());
        check("保留的确实是合法那三条",
                bad != null && bad.hits.size() == 3
                        && bad.hits.get(0).slot == 3 && bad.hits.get(1).slot == 5 && bad.hits.get(2).slot == 7,
                bad == null ? "null" : "内容不符");

        RecordingCodec.Data nl = read(write("a\nb\r\nc", hits(3)));
        check("名字里的换行被清洗且仍可读回", nl != null && nl.hits.size() == 3 && nl.name.indexOf('\n') < 0,
                nl == null ? "null" : nl.name);

        long t0 = System.currentTimeMillis();
        String big = write("长录音", hits(2000));
        RecordingCodec.Data bigData = read(big);
        check("2000 次按键往返正确", bigData != null && bigData.hits.size() == 2000,
                bigData == null ? "null" : "" + bigData.hits.size());
        System.out.println("      文本 " + big.length() / 1024 + " KB，往返 " + (System.currentTimeMillis() - t0) + " ms");

        System.out.println("结果: PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
