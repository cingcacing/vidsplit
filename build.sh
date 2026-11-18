#!/usr/bin/env bash
set -euo pipefail

# Reproducible, dependency-free Android build:
#   aapt2 -> javac -> d8 -> zipalign -> apksigner
# No Gradle, no AndroidX, no Kotlin, no network.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
APP="$ROOT/app/src/main"
FF="$ROOT/third_party/ffmpegkit"
CP="$PLATFORM:$FF/classes.jar:$FF/smart-exception.jar:$FF/smart-exception-common.jar"

if [ ! -f "$FF/classes.jar" ] || [ ! -f "$FF/smart-exception.jar" ] \
   || [ ! -f "$FF/smart-exception-common.jar" ]; then
  "$ROOT/scripts/fetch-deps.sh"
fi
OUT="$ROOT/build"
MIN_SDK=29
TARGET_SDK=34
ABIS="${ABIS:-arm64-v8a x86_64}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM"; do
  [ -e "$f" ] || { echo "Missing build tool: $f" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "[1/6] Compiling resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"
mkdir -p "$OUT/flat"
unzip -q "$OUT/res/resources.zip" -d "$OUT/flat"

echo "[2/6] Linking resources + manifest"
"$BT/aapt2" link \
  -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  "$OUT"/flat/*.flat \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code 7 \
  --version-name 3.4.0

echo "[3/6] Compiling Java"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -classpath "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] Dexing"
"$BT/d8" --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$FF/classes.jar" "$FF/smart-exception.jar" "$FF/smart-exception-common.jar"

echo "[5/6] Packaging"
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-with-dex.apk" *.dex)
mkdir -p "$OUT/nativelib/lib"
for abi in $ABIS; do
  cp -r "$FF/jni/$abi" "$OUT/nativelib/lib/"
done
(cd "$OUT/nativelib" && zip -q -0 -r "$OUT/app-with-dex.apk" lib)
"$BT/zipalign" -f -p 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"

echo "[6/6] Signing"
KS="$ROOT/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Video Splitter Debug,O=OpenSplit,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$ROOT/VideoSplitterClean.apk" \
  "$OUT/app-aligned.apk"

echo
echo "Built: $ROOT/VideoSplitterClean.apk"
"$BT/apksigner" verify --print-certs "$ROOT/VideoSplitterClean.apk" | head -5
ls -lh "$ROOT/VideoSplitterClean.apk"