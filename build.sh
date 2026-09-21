#!/data/data/com.termux/files/usr/bin/bash
# Builds and signs the handpan autoplay APK entirely on-device (Termux, no Android Studio).
#
# Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> inject classes.dex -> apksigner.
# There is no `zip` binary on this device, so dex injection uses python zipfile.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${HANDPAN_SDK:-$HOME/.local/share/android-sdk}"
ANDROID_JAR="$SDK/android.jar"
KEYSTORE="$SDK/handpan.keystore"
BUILD="$ROOT/build"
APK="$ROOT/handpan-autoplay.apk"

MANIFEST="$ROOT/app/AndroidManifest.xml"
RES="$ROOT/app/res"
SRC="$ROOT/app/src"

log() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- prerequisites
if [ ! -f "$ANDROID_JAR" ]; then
  log "android.jar 缺失，自动下载 platform-34（约 64MB）"
  python3 - "$SDK" <<'PY'
import sys, os, urllib.request, zipfile
sdk = sys.argv[1]
os.makedirs(sdk, exist_ok=True)
url = "https://dl.google.com/android/repository/platform-34-ext12_r01.zip"
zp = os.path.join(sdk, "platform-34.zip")
if not os.path.exists(zp):
    urllib.request.urlretrieve(url, zp)
with zipfile.ZipFile(zp) as z:
    name = [n for n in z.namelist() if n.endswith("android.jar")][0]
    with z.open(name) as src, open(os.path.join(sdk, "android.jar"), "wb") as out:
        out.write(src.read())
os.remove(zp)
print("android.jar ready")
PY
fi

for tool in aapt2 javac d8 apksigner keytool python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少工具: $tool"; exit 1; }
done

if [ ! -f "$KEYSTORE" ]; then
  log "生成签名密钥"
  keytool -genkeypair -keystore "$KEYSTORE" -alias handpan \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Handpan Autoplay, O=Local, C=CN" >/dev/null 2>&1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,classes,dex}

# ---------------------------------------------------------------- resources
log "aapt2 compile"
aapt2 compile --dir "$RES" -o "$BUILD/res.zip"

log "aapt2 link"
aapt2 link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 32 \
  --version-code 1 \
  --version-name 1.0 \
  --auto-add-overlay

# ---------------------------------------------------------------- java
log "javac"
SOURCES=$(find "$SRC" "$BUILD/gen" -name '*.java')
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  $SOURCES 2>&1 | grep -v -e "bootstrap class path" -e "source value 8" -e "target value 8" \
                        -e "To suppress warnings" -e "^[0-9]* warnings\?$" || true

# javac writes warnings to stdout; fail loudly if no class files came out.
if [ -z "$(find "$BUILD/classes" -name '*.class' -print -quit)" ]; then
  echo "编译失败：没有产生任何 class 文件"
  exit 1
fi

log "d8"
d8 --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

# ---------------------------------------------------------------- package + sign
log "打包（等价于 zipalign：resources.arsc 不压缩且 4 字节对齐）"
python3 "$ROOT/pack.py" build \
  --base "$BUILD/base.apk" \
  --dex "$BUILD/dex/classes.dex" \
  --out "$BUILD/package-unsigned.apk"

log "签名"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$BUILD/package-unsigned.apk"
apksigner verify "$APK"

log "打包合规校验"
python3 "$ROOT/pack.py" verify --apk "$APK"

log "完成"
ls -la "$APK"
echo
echo "安装：termux-open $APK"
