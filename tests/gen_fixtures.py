#!/usr/bin/env python3
"""Generates the MIDI fixtures the core tests read. Written byte by byte, no network, no libraries."""
import struct, sys, os

def vlq(n):
    out = [n & 0x7F]; n >>= 7
    while n:
        out.insert(0, (n & 0x7F) | 0x80); n >>= 7
    return bytes(out)

def track(events):
    body = b""; last = 0
    for tick, data in events:
        body += vlq(tick - last) + data; last = tick
    body += vlq(0) + b"\xFF\x2F\x00"
    return b"MTrk" + struct.pack(">I", len(body)) + body

def header(fmt, ntrk, div):
    return b"MThd" + struct.pack(">IHHH", 6, fmt, ntrk, div)

DIV = 480
TEMPO120 = b"\xFF\x51\x03" + struct.pack(">I", 500000)[1:]

def write(path, data):
    with open(path, "wb") as f:
        f.write(data)

def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    # 音阶：C D E F G A B，每音 480 tick（120BPM 下 500ms）
    ev = [(0, TEMPO120)]
    for i, note in enumerate([60, 62, 64, 65, 67, 69, 71]):
        ev.append((i * DIV, bytes([0x90, note, 0x40])))
        ev.append((i * DIV + DIV, bytes([0x80, note, 0x40])))
    ev.sort(key=lambda e: e[0])
    write(os.path.join(outdir, "scale.mid"), header(0, 1, DIV) + track(ev))

    # 960 tick 处 tempo 减半
    ev = [(0, TEMPO120), (0, bytes([0x90, 60, 0x40])), (DIV, bytes([0x80, 60, 0x40])),
          (DIV, b"\xFF\x51\x03" + struct.pack(">I", 250000)[1:]),
          (DIV * 2, bytes([0x90, 64, 0x40])), (DIV * 3, bytes([0x80, 64, 0x40]))]
    ev.sort(key=lambda e: e[0])
    write(os.path.join(outdir, "tempo.mid"), header(0, 1, DIV) + track(ev))

    # running status：省略重复的状态字节
    ev = [(0, bytes([0x90, 0x3C, 0x40])), (DIV, bytes([0x3C, 0x00])),
          (DIV, bytes([0x3E, 0x40])), (DIV * 2, bytes([0x3E, 0x00]))]
    write(os.path.join(outdir, "running.mid"), header(0, 1, DIV) + track(ev))

    # format 1 双轨：tempo 轨 + 旋律轨
    t0 = [(0, TEMPO120)]
    t1 = []
    for i, note in enumerate([60, 64, 67]):
        t1.append((i * DIV, bytes([0x90, note, 0x40])))
        t1.append((i * DIV + DIV, bytes([0x80, note, 0x40])))
    write(os.path.join(outdir, "multi.mid"), header(1, 2, DIV) + track(t0) + track(t1))

    # 截断文件
    good = header(0, 1, DIV) + track([(0, TEMPO120)])
    write(os.path.join(outdir, "truncated.mid"), good[:20])
    print("fixtures written to", outdir)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/fixtures")
