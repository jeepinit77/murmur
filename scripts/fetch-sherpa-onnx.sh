#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android AAR (ONNX Runtime + espeak-ng + Kotlin API)
# into app/libs/sherpa-onnx.aar. Version comes from gradle.properties (sherpaOnnxVersion)
# or the first argument.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:-$(grep '^sherpaOnnxVersion=' gradle.properties | cut -d= -f2)}"
OUT=app/libs/sherpa-onnx.aar
mkdir -p app/libs
if [[ -f "$OUT" && -f app/libs/.sherpa-version && "$(cat app/libs/.sherpa-version)" == "$VERSION" ]]; then
  echo "sherpa-onnx $VERSION already present"; exit 0
fi
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
echo "Downloading $URL"
curl -fL --retry 4 -o "$OUT.tmp" "$URL"
unzip -l "$OUT.tmp" | grep 'jni/arm64-v8a/' >/dev/null || { echo "Unexpected AAR layout"; exit 1; }
mv "$OUT.tmp" "$OUT"
echo "$VERSION" > app/libs/.sherpa-version
sha256sum "$OUT"
