#!/data/data/com.termux/files/usr/bin/bash
# Runs the pure-Java test suite.
#
# Only modules that do not touch Android can be covered here; anything needing Context, Handler or
# MediaCodec has to be exercised on a device. The value of these tests is that the parsing, mapping,
# timing and storage logic - where the real bugs have been - is verifiable without a phone.
#
#   ./tests/run.sh            run everything
#   ./tests/run.sh TestPads   run one suite
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/com/handpan/autoplay"
OUT="$ROOT/build/tests"
WORK="$ROOT/build/testsrc"

# Modules with no Android imports.
PURE="RawNote ScaleMapper MidiParser PitchDetector PadMapper KeyDetector TapPlanner \
TempoEstimator SnapshotCodec ScheduleClock PlaylistNavigator \
RecordingCodec PracticeSession PadHitTester PadGeometry SpeedClock LaneLayout ScoreLink "

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/com/handpan/autoplay" "$OUT"
for name in $PURE; do
  cp "$SRC/$name.java" "$WORK/com/handpan/autoplay/" || exit 1
done
cp "$ROOT"/tests/java/com/handpan/autoplay/*.java "$WORK/com/handpan/autoplay/" 2>/dev/null || true

python3 "$ROOT/tests/gen_fixtures.py" "$ROOT/build/fixtures" >/dev/null || { echo "固件生成失败"; exit 1; }

export HANDPAN_FIXTURES="$ROOT/build/fixtures"

FILTER="${1:-}"
SUITES=$(cd "$WORK/com/handpan/autoplay" && ls Test*.java 2>/dev/null | sed 's/\.java$//')
if [ -z "$SUITES" ]; then
  echo "没有找到测试文件"
  exit 1
fi

if ! javac -encoding UTF-8 -d "$OUT" -sourcepath "$WORK" "$WORK"/com/handpan/autoplay/*.java 2>&1; then
  echo "编译失败"
  exit 1
fi

PASS=0
FAIL=0
FAILED=""
for suite in $SUITES; do
  if [ -n "$FILTER" ] && [ "$suite" != "$FILTER" ]; then continue; fi
  line=$(java -cp "$OUT" "com.handpan.autoplay.$suite" 2>&1 | tail -1)
  echo "$suite  $line"
  n=$(printf '%s' "$line" | sed -n 's/.*PASS=\([0-9]*\).*/\1/p')
  m=$(printf '%s' "$line" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')
  PASS=$((PASS + ${n:-0}))
  FAIL=$((FAIL + ${m:-0}))
  [ -n "${m:-}" ] && [ "$m" != "0" ] && FAILED="$FAILED $suite"
done

echo
echo "合计: PASS=$PASS FAIL=$FAIL"
if [ -n "$FAILED" ]; then
  echo "失败:$FAILED"
  exit 1
fi
