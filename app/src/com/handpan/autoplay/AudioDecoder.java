package com.handpan.autoplay;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodes any platform-supported audio file into mono float PCM for pitch analysis.
 *
 * <p>WAV is parsed by hand first because it is trivial and avoids codec quirks; everything else
 * (mp3, m4a/aac, ogg, flac) goes through {@link MediaExtractor} + {@link MediaCodec}.
 */
public final class AudioDecoder {

    /** Analysis rate. Enough for a handpan's range and far cheaper than 44.1kHz. */
    public static final int TARGET_RATE = 22050;

    /** Hard cap on decoded length, so a huge file cannot exhaust memory on a phone. */
    private static final int MAX_SECONDS = 300;

    private static final class Pcm {
        final float[] data;
        final int rate;

        Pcm(float[] data, int rate) {
            this.data = data;
            this.rate = rate;
        }
    }

    private AudioDecoder() {}

    /** @return mono float samples in [-1, 1] at {@link #TARGET_RATE}. */
    public static float[] decodeMono(Context ctx, Uri uri, String lowerName) throws IOException {
        Pcm pcm = null;
        if (lowerName != null && (lowerName.endsWith(".wav") || lowerName.endsWith(".wave"))) {
            try {
                pcm = readWav(ctx, uri);
            } catch (IOException e) {
                pcm = null; // corrupt header: let the platform decoder try
            }
        }
        if (pcm == null) pcm = decodeWithMediaCodec(ctx, uri);
        if (pcm == null || pcm.data.length == 0) {
            throw new IOException("没有解码出音频数据（格式不支持或文件损坏）");
        }
        if (pcm.rate == TARGET_RATE || pcm.rate <= 0) return pcm.data;
        return resample(pcm.data, pcm.rate, TARGET_RATE);
    }

    // ------------------------------------------------------------------ WAV

    private static Pcm readWav(Context ctx, Uri uri) throws IOException {
        byte[] all = readAll(ctx, uri, 64 * 1024 * 1024);
        if (all.length < 44) throw new IOException("WAV 文件太小");
        if (!tagEquals(all, 0, "RIFF") || !tagEquals(all, 8, "WAVE")) {
            throw new IOException("不是 RIFF/WAVE");
        }
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int format = 1;
        int dataOff = -1;
        int dataLen = 0;

        int p = 12;
        while (p + 8 <= all.length) {
            String id = new String(all, p, 4, "US-ASCII");
            int size = le32(all, p + 4);
            int body = p + 8;
            if (size < 0) break;
            if ("fmt ".equals(id) && body + 16 <= all.length) {
                format = le16(all, body);
                channels = le16(all, body + 2);
                rate = le32(all, body + 4);
                bits = le16(all, body + 14);
            } else if ("data".equals(id)) {
                dataOff = body;
                dataLen = Math.min(size, all.length - body);
                break;
            }
            if (size == 0) break;
            p = body + size + (size & 1);
        }
        if (dataOff < 0 || channels <= 0 || rate <= 0) throw new IOException("WAV 缺少 fmt/data 块");

        int bytesPerSample = Math.max(1, bits / 8);
        int frameBytes = bytesPerSample * channels;
        int frames = dataLen / frameBytes;
        frames = capFrames(frames, rate);

        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            int base = dataOff + f * frameBytes;
            for (int c = 0; c < channels; c++) {
                int o = base + c * bytesPerSample;
                float v;
                if (format == 3 && bits == 32) {
                    v = Float.intBitsToFloat(le32(all, o));
                } else if (bits == 16) {
                    v = (short) le16(all, o) / 32768f;
                } else if (bits == 8) {
                    v = (all[o] & 0xFF) / 128f - 1f;
                } else if (bits == 24) {
                    int raw = (all[o] & 0xFF) | ((all[o + 1] & 0xFF) << 8) | ((all[o + 2] & 0xFF) << 16);
                    if ((raw & 0x800000) != 0) raw |= 0xFF000000;
                    v = raw / 8388608f;
                } else if (bits == 32) {
                    v = le32(all, o) / 2147483648f;
                } else {
                    v = 0f;
                }
                sum += v;
            }
            out[f] = sum / channels;
        }
        return new Pcm(out, rate);
    }

    private static int capFrames(int frames, int rate) {
        long max = (long) rate * MAX_SECONDS;
        return frames > max ? (int) max : frames;
    }

    private static boolean tagEquals(byte[] b, int off, String tag) {
        if (off + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------------------ codec

    private static Pcm decodeWithMediaCodec(Context ctx, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) throw new IOException("文件里没有音频轨道");
            extractor.selectTrack(track);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_RATE;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (channels < 1) channels = 1;

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            Grower grower = new Grower(Math.min(rate * 10, 1 << 22));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long maxSamples = (long) rate * channels * MAX_SECONDS;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    if (info.size > 0 && grower.size() < maxSamples) {
                        ByteBuffer buf = codec.getOutputBuffer(outIndex);
                        if (buf != null) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int samples = sb.remaining();
                            int frames = samples / channels;
                            for (int f = 0; f < frames; f++) {
                                float sum = 0f;
                                for (int c = 0; c < channels; c++) {
                                    sum += sb.get() / 32768f;
                                }
                                grower.add(sum / channels);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int ch = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (ch > 0) channels = ch;
                    }
                }
            }

            codec.stop();
            codec.release();
            return new Pcm(grower.toArray(), rate);
        } finally {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static float[] resample(float[] in, int from, int to) {
        int n = (int) ((long) in.length * to / from);
        if (n <= 0) return new float[0];
        float[] out = new float[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = i0 + 1 < in.length ? i0 + 1 : in.length - 1;
            float frac = (float) (pos - i0);
            out[i] = in[i0] * (1f - frac) + in[i1] * frac;
        }
        return out;
    }

    /** Growable float array; avoids per-sample boxing and keeps memory flat. */
    private static final class Grower {
        private float[] buf;
        private int len;

        Grower(int cap) {
            buf = new float[Math.max(1024, cap)];
        }

        void add(float v) {
            if (len == buf.length) {
                int next = buf.length < (1 << 24) ? buf.length * 2 : buf.length + (1 << 24);
                float[] bigger = new float[next];
                System.arraycopy(buf, 0, bigger, 0, len);
                buf = bigger;
            }
            buf[len++] = v;
        }

        int size() {
            return len;
        }

        float[] toArray() {
            float[] out = new float[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    static byte[] readAll(Context ctx, Uri uri, int limit) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开文件");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            byte[] tmp = new byte[1 << 16];
            int n;
            int total = 0;
            while ((n = in.read(tmp)) > 0) {
                total += n;
                if (total > limit) throw new IOException("文件过大（超过 " + (limit / 1048576) + "MB）");
                bos.write(tmp, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
