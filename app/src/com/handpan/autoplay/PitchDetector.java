package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 纯 Java 的单音（旋律）音高检测器。
 *
 * <p>算法：分帧 -> YIN（差值函数 / CMND + 绝对阈值 + 抛物线插值）-> RMS 门限判静音
 * -> 基频转 MIDI -> 中值平滑 -> 合并同音高段 -> 丢弃过短段与孤立八度跳变。</p>
 *
 * <p>复杂度：每帧一次 FFT 自相关，整体 O(帧数 × 帧长 log 帧长)，与样本总长度近似线性；
 * 不随音频时长出现 O(n^2) 行为。无状态、可重入、不使用线程、不依赖任何 Android/第三方类。</p>
 */
public final class PitchDetector {

    /** 目标帧长（秒）：40ms，落在要求的 20–50ms 区间内。 */
    private static final double FRAME_SECONDS = 0.040;
    private static final int MIN_FRAME_SIZE = 512;
    private static final int MAX_FRAME_SIZE = 8192;
    /** hop = frameSize / HOP_DIVISOR，2048 -> 512（约 11.6ms @44.1kHz）。 */
    private static final int HOP_DIVISOR = 4;

    /** YIN 绝对阈值：取第一个低于此值的谷值。 */
    private static final double YIN_THRESHOLD = 0.15;
    /** 找不到低于阈值的谷值时，全局最小值必须低于此值才认为是浊音（抗白噪声）。 */
    private static final double YIN_FALLBACK_MAX = 0.30;

    /** 相对峰值 -40dB 的 RMS 门限（样本已做峰值归一化，故峰值恒为 1）。 */
    private static final double RELATIVE_RMS_GATE = 0.01;
    /** 全局 RMS 中位数的 1/8。 */
    private static final double MEDIAN_GATE_DIVISOR = 8.0;
    private static final double ABSOLUTE_FLOOR = 1e-6;

    /** MIDI 中值平滑窗口（帧数，奇数）。 */
    private static final int MEDIAN_WINDOW = 5;
    /** 短于此长度的段视为噪声丢弃。 */
    private static final long MIN_SEGMENT_MS = 80L;
    /** 短于此长度且与两侧相差约一个八度的孤立段被丢弃。 */
    private static final long OCTAVE_OUTLIER_MS = 150L;

    private static final double INV_LOG2 = 1.0 / Math.log(2.0);

    private PitchDetector() {
    }

    /**
     * 从单声道 PCM 浮点样本中提取音符序列（单音旋律）。
     *
     * @param pcm        样本值范围建议 -1.0..1.0；若传 -32768..32767 的整数浮点也应尽量能用（内部做峰值归一化）
     * @param sampleRate 采样率，如 22050 / 44100
     * @param minHz      最低检测音高，如 65（C2）
     * @param maxHz      最高检测音高，如 2000（B6）
     * @return 按 startMs 升序的音符；检测不到就返回空 list，不要返回 null
     */
    public static List<RawNote> detect(float[] pcm, int sampleRate, float minHz, float maxHz) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) {
            return Collections.emptyList();
        }
        if (!(minHz > 0f) || !(maxHz > minHz)) {
            return Collections.emptyList();
        }

        final int frameSize = chooseFrameSize(sampleRate);
        final int hop = Math.max(1, frameSize / HOP_DIVISOR);
        if (pcm.length < frameSize) {
            return Collections.emptyList();
        }

        int tauMin = (int) Math.floor(sampleRate / (double) maxHz);
        if (tauMin < 2) {
            tauMin = 2;
        }
        int tauMax = (int) Math.ceil(sampleRate / (double) minHz);
        final int tauLimit = frameSize / 2;
        if (tauMax > tauLimit) {
            tauMax = tauLimit;
        }
        if (tauMax <= tauMin) {
            return Collections.emptyList();
        }
        int tauHi = tauMax + 1;
        if (tauHi > frameSize - 1) {
            tauHi = frameSize - 1;
        }

        // 峰值归一化：同时兼容 -1..1 和 -32768..32767 两种输入尺度。
        float peak = 0f;
        for (int i = 0; i < pcm.length; i++) {
            final float a = pcm[i] < 0f ? -pcm[i] : pcm[i];
            if (a > peak) {
                peak = a;
            }
        }
        if (peak < ABSOLUTE_FLOOR) {
            return Collections.emptyList();
        }
        final float scale = 1f / peak;

        final int numFrames = 1 + (pcm.length - frameSize) / hop;

        // 每帧 RMS，用于静音门限（O(n) 一遍，内存 O(帧数)）。
        final float[] rms = new float[numFrames];
        for (int f = 0; f < numFrames; f++) {
            final int off = f * hop;
            double sum = 0.0;
            for (int j = 0; j < frameSize; j++) {
                final double v = pcm[off + j] * scale;
                sum += v * v;
            }
            rms[f] = (float) Math.sqrt(sum / frameSize);
        }

        final float[] sortedRms = rms.clone();
        Arrays.sort(sortedRms);
        double gate = sortedRms[numFrames / 2] / MEDIAN_GATE_DIVISOR;
        if (gate < RELATIVE_RMS_GATE) {
            gate = RELATIVE_RMS_GATE;
        }

        final int[] midiArr = new int[numFrames];
        Arrays.fill(midiArr, -1);
        final boolean[] voiced = new boolean[numFrames];

        final Analyzer an = new Analyzer(frameSize, tauHi);
        final double[] frame = new double[frameSize];

        for (int f = 0; f < numFrames; f++) {
            if (rms[f] < gate) {
                continue; // 静音 / 太弱
            }
            final int off = f * hop;
            for (int j = 0; j < frameSize; j++) {
                frame[j] = pcm[off + j] * scale;
            }
            an.difference(frame);
            final int tau = an.pick(tauMin, tauMax);
            if (tau < 0) {
                continue; // 非周期性（噪声）
            }
            final double tauRef = an.refine(tau, tauHi);
            final double f0 = sampleRate / tauRef;
            if (f0 < minHz * 0.9 || f0 > maxHz * 1.1) {
                continue;
            }
            int midi = (int) Math.round(69.0 + 12.0 * (Math.log(f0 / 440.0) * INV_LOG2));
            if (midi < 0) {
                midi = 0;
            } else if (midi > 127) {
                midi = 127;
            }
            midiArr[f] = midi;
            voiced[f] = true;
        }

        final int[] smoothMidi = new int[numFrames];
        final boolean[] smoothVoiced = new boolean[numFrames];
        smooth(midiArr, voiced, numFrames, smoothMidi, smoothVoiced);

        return merge(smoothMidi, smoothVoiced, numFrames, hop, sampleRate);
    }

    /** 帧长按采样率自适应到约 40ms，并限制在 [MIN_FRAME_SIZE, MAX_FRAME_SIZE]。 */
    private static int chooseFrameSize(int sampleRate) {
        final int target = (int) Math.round(sampleRate * FRAME_SECONDS);
        int f = MIN_FRAME_SIZE;
        while (f < target && f < MAX_FRAME_SIZE) {
            f <<= 1;
        }
        if (f > MAX_FRAME_SIZE) {
            f = MAX_FRAME_SIZE;
        }
        if (f < MIN_FRAME_SIZE) {
            f = MIN_FRAME_SIZE;
        }
        return f;
    }

    /** 对 MIDI 序列做窗口 5 的中值平滑；可桥接被 1–2 帧静音打断的同音高。 */
    private static void smooth(int[] midi, boolean[] voiced, int numFrames,
                               int[] outMidi, boolean[] outVoiced) {
        final int w = MEDIAN_WINDOW / 2;
        final int[] tmp = new int[MEDIAN_WINDOW];
        for (int i = 0; i < numFrames; i++) {
            int cnt = 0;
            int lo = i - w;
            if (lo < 0) {
                lo = 0;
            }
            int hi = i + w;
            if (hi >= numFrames) {
                hi = numFrames - 1;
            }
            for (int j = lo; j <= hi; j++) {
                if (voiced[j] && cnt < MEDIAN_WINDOW) {
                    tmp[cnt++] = midi[j];
                }
            }
            if (cnt == 0 || (!voiced[i] && cnt < 3)) {
                outMidi[i] = -1;
                outVoiced[i] = false;
                continue;
            }
            // 小数组插入排序（窗口 <= 5），避免每帧分配。
            for (int a = 1; a < cnt; a++) {
                final int v = tmp[a];
                int b = a - 1;
                while (b >= 0 && tmp[b] > v) {
                    tmp[b + 1] = tmp[b];
                    b--;
                }
                tmp[b + 1] = v;
            }
            outMidi[i] = tmp[cnt / 2];
            outVoiced[i] = true;
        }
    }

    /** 合并相邻同音高帧成段，过滤短段与孤立八度跳变。 */
    private static List<RawNote> merge(int[] midi, boolean[] voiced, int numFrames,
                                       int hop, int sampleRate) {
        final ArrayList<RawNote> raw = new ArrayList<RawNote>();
        int i = 0;
        while (i < numFrames) {
            if (!voiced[i]) {
                i++;
                continue;
            }
            final int m = midi[i];
            final int start = i;
            int end = i;
            i++;
            while (i < numFrames && voiced[i] && midi[i] == m) {
                end = i;
                i++;
            }
            final long startMs = (long) start * hop * 1000L / sampleRate;
            final long endMs = (long) (end + 1) * hop * 1000L / sampleRate;
            long durMs = endMs - startMs;
            if (durMs < 1L) {
                durMs = 1L;
            }
            raw.add(new RawNote(m, startMs, durMs));
        }

        final ArrayList<RawNote> kept = new ArrayList<RawNote>();
        for (int k = 0; k < raw.size(); k++) {
            if (raw.get(k).durMs >= MIN_SEGMENT_MS) {
                kept.add(raw.get(k));
            }
        }

        final ArrayList<RawNote> result = new ArrayList<RawNote>();
        for (int k = 0; k < kept.size(); k++) {
            final RawNote cur = kept.get(k);
            if (cur.durMs < OCTAVE_OUTLIER_MS && k > 0 && k < kept.size() - 1) {
                final RawNote prev = kept.get(k - 1);
                final RawNote next = kept.get(k + 1);
                if (Math.abs(prev.midi - next.midi) <= 2
                        && Math.abs(cur.midi - prev.midi) >= 11) {
                    continue; // 孤立八度跳变
                }
            }
            result.add(cur);
        }
        return result;
    }

    /**
     * 单帧 YIN 分析器的缓冲容器：FFT 表与差分数组在多次调用间复用，
     * 每次 detect() 只分配一次，避免逐帧分配。
     */
    private static final class Analyzer {
        private final int frameSize;
        private final int fftSize;
        private final int[] rev;
        private final double[] cosT;
        private final double[] sinT;
        private final double[] re;
        private final double[] im;
        private final double[] pre;      // 平方前缀和，长度 frameSize+1
        private final double[] dPrime;   // CMND，长度 tauHi+1

        Analyzer(int frameSize, int tauHi) {
            this.frameSize = frameSize;
            int fs = 1;
            while (fs < 2 * frameSize) {
                fs <<= 1;
            }
            this.fftSize = fs;
            final int bits = Integer.numberOfTrailingZeros(fs);
            this.rev = new int[fs];
            for (int i = 0; i < fs; i++) {
                rev[i] = Integer.reverse(i) >>> (32 - bits);
            }
            this.cosT = new double[fs / 2];
            this.sinT = new double[fs / 2];
            for (int k = 0; k < fs / 2; k++) {
                final double ang = 2.0 * Math.PI * k / fs;
                cosT[k] = Math.cos(ang);
                sinT[k] = Math.sin(ang);
            }
            this.re = new double[fs];
            this.im = new double[fs];
            this.pre = new double[frameSize + 1];
            this.dPrime = new double[tauHi + 1];
        }

        /** 迭代基 2 FFT；inverse 时除以 n。 */
        private void fft(boolean inverse) {
            final int n = fftSize;
            for (int i = 0; i < n; i++) {
                final int j = rev[i];
                if (j > i) {
                    double t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                final int half = len >> 1;
                final int step = n / len;
                for (int start = 0; start < n; start += len) {
                    int idx = 0;
                    for (int k = 0; k < half; k++) {
                        final double wr = cosT[idx];
                        final double wi = inverse ? sinT[idx] : -sinT[idx];
                        final int i1 = start + k;
                        final int i2 = i1 + half;
                        final double xr = re[i2] * wr - im[i2] * wi;
                        final double xi = re[i2] * wi + im[i2] * wr;
                        re[i2] = re[i1] - xr;
                        im[i2] = im[i1] - xi;
                        re[i1] += xr;
                        im[i1] += xi;
                        idx += step;
                    }
                }
            }
            if (inverse) {
                final double inv = 1.0 / n;
                for (int i = 0; i < n; i++) {
                    re[i] *= inv;
                    im[i] *= inv;
                }
            }
        }

        /**
         * 计算差值函数 d(tau) 与累积均值归一化 CMND：
         * d(tau) = sum_{j} (x[j]-x[j+tau])^2 = P(0..F-tau-1) + P(tau..F-1) - 2*r(tau)，
         * 其中 r(tau) 由 FFT 线性自相关一次算出（O(F log F)）。
         */
        void difference(double[] x) {
            final int n = fftSize;
            for (int i = 0; i < n; i++) {
                re[i] = 0.0;
                im[i] = 0.0;
            }
            System.arraycopy(x, 0, re, 0, frameSize);
            fft(false);
            for (int i = 0; i < n; i++) {
                final double a = re[i];
                final double b = im[i];
                re[i] = a * a + b * b;
                im[i] = 0.0;
            }
            fft(true); // 此时 re[tau] = 线性自相关 r(tau)

            double p = 0.0;
            pre[0] = 0.0;
            for (int i = 0; i < frameSize; i++) {
                p += x[i] * x[i];
                pre[i + 1] = p;
            }

            dPrime[0] = 1.0;
            final double total = pre[frameSize];
            double cum = 0.0;
            for (int tau = 1; tau < dPrime.length; tau++) {
                double d = pre[frameSize - tau] + (total - pre[tau]) - 2.0 * re[tau];
                if (d < 0.0) {
                    d = 0.0;
                }
                cum += d;
                dPrime[tau] = cum > 1e-20 ? (d * tau / cum) : 1.0;
            }
        }

        /** 取第一个低于阈值的谷值；否则用全局最小值兜底（并受 YIN_FALLBACK_MAX 限制）。 */
        int pick(int tauMin, int tauMax) {
            int best = -1;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (dPrime[tau] < YIN_THRESHOLD) {
                    best = tau;
                    break;
                }
            }
            if (best >= 0) {
                while (best + 1 <= tauMax && dPrime[best + 1] < dPrime[best]) {
                    best++;
                }
                return best;
            }
            double minVal = Double.MAX_VALUE;
            int minTau = -1;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                final double v = dPrime[tau];
                if (v < minVal) {
                    minVal = v;
                    minTau = tau;
                }
            }
            if (minTau < 0 || minVal > YIN_FALLBACK_MAX) {
                return -1;
            }
            return minTau;
        }

        /** 抛物线插值精修 lag，避免音高系统性偏低。 */
        double refine(int tau, int tauHi) {
            if (tau > 1 && tau < tauHi) {
                final double s0 = dPrime[tau - 1];
                final double s1 = dPrime[tau];
                final double s2 = dPrime[tau + 1];
                final double denom = s0 - 2.0 * s1 + s2;
                if (denom > 1e-12 || denom < -1e-12) {
                    final double delta = 0.5 * (s0 - s2) / denom;
                    if (delta > -0.5 && delta < 0.5) {
                        return tau + delta;
                    }
                }
            }
            return tau;
        }
    }
}
