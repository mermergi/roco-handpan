package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses plain numbered musical notation (jianpu / 简谱) text into notes.
 *
 * <p>Accepted tokens, separated by whitespace:
 * <ul>
 *   <li>{@code 1}..{@code 7} - a scale degree, one beat by default</li>
 *   <li>{@code -} alone, or a trailing run of {@code -} on a digit, extends by one beat each</li>
 *   <li>trailing {@code _} halves the value; {@code 1_} is an eighth note, {@code 1__} a sixteenth</li>
 *   <li>{@code 0} is a rest (or the zero pad when the user enables it)</li>
 *   <li>{@code %} starts a comment that runs to the end of the line; {@code |} is ignored</li>
 *   <li>a dot after the digit is ignored (dotted / octave markers are not modelled)</li>
 * </ul>
 *
 * <p>Because the target instrument is a single-octave major-scale instrument, octave dots carry no
 * information we can use: all degrees map into one octave around C4.
 */
public final class JianpuParser {

    private JianpuParser() {}

    /** Base octave used for the numeric degrees: C4 = 60 for degree 1 in C. */
    private static final int BASE_MIDI = 60;

    public static List<RawNote> parse(String text, int bpm) {
        List<RawNote> out = new ArrayList<RawNote>();
        if (text == null) return out;
        double beatMs = 60000.0 / Math.max(20, bpm);
        long cursor = 0;

        String[] lines = text.split("\\r?\\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int comment = line.indexOf('%');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.replace("|", " ");
            String[] raw = line.trim().split("\\s+");
            for (int ti = 0; ti < raw.length; ti++) {
                String tok = raw[ti];
                if (tok.length() == 0) continue;

                if (tok.equals("-")) {
                    // Explicit sustain for the previous beat.
                    if (!out.isEmpty()) {
                        RawNote last = out.remove(out.size() - 1);
                        out.add(new RawNote(last.midi, last.startMs, last.durMs + Math.round(beatMs)));
                    }
                    cursor += Math.round(beatMs);
                    continue;
                }

                char head = tok.charAt(0);
                if (head < '0' || head > '9') continue; // unsupported glyph: skip, never guess

                int digit = head - '0';
                int dashes = 0;
                int underscores = 0;
                boolean sawUnderscore = false;
                for (int i = 1; i < tok.length(); i++) {
                    char ch = tok.charAt(i);
                    if (ch == '-') {
                        if (sawUnderscore) break;
                        dashes++;
                    } else if (ch == '_') {
                        sawUnderscore = true;
                        underscores++;
                    }
                    // anything else (dots, octave marks) is ignored
                }

                double beats = 1.0 + dashes;
                for (int i = 0; i < underscores; i++) beats /= 2.0;
                long durMs = Math.max(1L, Math.round(beats * beatMs));

                if (digit == 0) {
                    // Rest, or the optional zero pad. A pad hit needs a pitch, so only the
                    // rest interpretation is produced here; the mapper decides about pad 0.
                    cursor += durMs;
                    continue;
                }

                int midi = ScaleMapper.midiForDegree(digit, 0, BASE_MIDI);
                if (midi > 0) {
                    out.add(new RawNote(midi, cursor, durMs));
                }
                cursor += durMs;
            }
        }
        return out;
    }
}
