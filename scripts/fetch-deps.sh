#!/usr/bin/env bash
# Downloads the pinned FFmpegKit AAR + smart-exception runtime jars and extracts
# the parts the build needs. Every artifact is verified against a fixed SHA-256.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FF="$ROOT/third_party/ffmpegkit"
mkdir -p "$FF"

AAR="$FF/ffmpeg-kit-video-6.0-2.aar"
AAR_SHA="a280ef2f2e3725cf0749708abf16aedd1caf271f12cc12f4824d5d779ef7dc95"
SE_JAVA="$FF/smart-exception.jar"
SE_JAVA_SHA="5b96aaa5f191dedbef72fb0c38f1a2b01807920afc0d92a75a2acd6e0cc7703c"
SE_COMMON="$FF/smart-exception-common.jar"
SE_COMMON_SHA="1cad0fb4dfa01755a014331b5ed199281d2c3fab5aca5c9d7abd0b41d0ec3f7b"

CENTRAL_ALIYUN="https://maven.aliyun.com/repository/central"
CENTRAL_HUAWEI="https://repo.huaweicloud.com/repository/maven"

verify() { # file sha
  echo "$2  $1" | sha256sum -c - >/dev/null 2>&1
}

download() { # dest sha relative-path
  local dest="$1" sha="$2" rel="$3"
  if [ -f "$dest" ] && verify "$dest" "$sha"; then
    echo "  ok  $(basename "$dest")"
    return 0
  fi
  local base
  for base in "$CENTRAL_ALIYUN" "$CENTRAL_HUAWEI"; do
    echo "  get $base/$rel"
    if curl -fsSL --retry 2 --max-time 600 -o "$dest.tmp" "$base/$rel"; then
      if verify "$dest.tmp" "$sha"; then
        mv "$dest.tmp" "$dest"
        echo "  ok  $(basename "$dest")"
        return 0
      fi
      echo "  checksum mismatch from $base" >&2
    fi
    rm -f "$dest.tmp"
  done
  echo "Could not download a verified $(basename "$dest")" >&2
  echo "Place it manually at $dest and re-run." >&2
  return 1
}

echo "Fetching pinned dependencies into third_party/ffmpegkit"
download "$AAR" "$AAR_SHA" \
  "com/arthenica/ffmpeg-kit-video/6.0-2/ffmpeg-kit-video-6.0-2.aar"
download "$SE_JAVA" "$SE_JAVA_SHA" \
  "com/arthenica/smart-exception-java/0.2.1/smart-exception-java-0.2.1.jar"
download "$SE_COMMON" "$SE_COMMON_SHA" \
  "com/arthenica/smart-exception-common/0.2.1/smart-exception-common-0.2.1.jar"

echo "Extracting AAR"
unzip -o -q "$AAR" classes.jar "jni/arm64-v8a/*" "jni/x86_64/*" -d "$FF"

test -f "$FF/classes.jar"
test -f "$FF/jni/arm64-v8a/libffmpegkit.so"
test -f "$FF/jni/x86_64/libffmpegkit.so"
echo "Dependencies ready"