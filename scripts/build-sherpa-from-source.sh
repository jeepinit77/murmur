#!/usr/bin/env bash
# Builds sherpa-onnx's JNI library (TTS only, arm64-v8a) from source and vendors it into the
# app, for environments that can't download the prebuilt AAR from GitHub releases
# (scripts/fetch-sherpa-onnx.sh is the normal path and is what CI uses).
#
# Every third-party dependency is fetched with `git` at the exact tag/commit sherpa-onnx pins
# in its cmake/*.cmake files, and handed to CMake via FETCHCONTENT_SOURCE_DIR_<NAME>, so no
# tarball downloads are needed.
#
# Needs: ANDROID_HOME with ndk;27.2.12479018 and cmake;3.22.1 installed, git, curl, unzip.
# Outputs (git-ignored):
#   app/src/main/jniLibs/arm64-v8a/{libsherpa-onnx-jni.so,libonnxruntime.so}
#   app/src/main/java/com/k2fsa/sherpa/onnx/{Tts.kt,...}
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="${SHERPA_VERSION:-$(grep '^sherpaOnnxVersion=' "$ROOT/gradle.properties" | cut -d= -f2)}"
ORT_VERSION="${ORT_VERSION:-1.32.0}"
WORK="${WORK:-$HOME/build/sherpa-src}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK="${ANDROID_NDK:-$ANDROID_HOME/ndk/27.2.12479018}"
CMAKE_BIN="${CMAKE_BIN:-$ANDROID_HOME/cmake/3.22.1/bin}"
export PATH="$CMAKE_BIN:$PATH"

mkdir -p "$WORK/deps"
cd "$WORK"

if [ ! -d sherpa-onnx/.git ]; then
  GIT_LFS_SKIP_SMUDGE=1 git clone -q --depth 1 --branch "v$SHERPA_VERSION" https://github.com/k2-fsa/sherpa-onnx
fi
SRC="$WORK/sherpa-onnx"

# --- ONNX Runtime: official Microsoft build from Maven Central -----------------------------
ORT="$WORK/onnxruntime-$ORT_VERSION"
if [ ! -f "$ORT/jni/arm64-v8a/libonnxruntime.so" ]; then
  mkdir -p "$ORT"
  curl -fsSL -o "$ORT.aar" \
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"
  unzip -q -o "$ORT.aar" 'jni/arm64-v8a/*' 'headers/*' -d "$ORT"
  rm "$ORT.aar"
fi

# --- Third-party sources, at the versions sherpa-onnx pins ---------------------------------
# fetch <cmake-file (relative to sherpa's cmake/ dir, or absolute)> <FetchContent name>
fetch() {
  local file="$1" name="$2" url repo ref dir
  [[ "$file" = /* ]] || file="$SRC/cmake/$file"
  url=$(grep -m1 -oE 'https://(github\.com|gitlab\.com)/[^"]+' "$file")
  case "$url" in
    https://github.com/*/archive/refs/tags/*)
      repo="${url%%/archive/*}"; ref="${url##*/refs/tags/}"; ref="${ref%.tar.gz}"; ref="${ref%.zip}" ;;
    https://github.com/*/archive/*)
      repo="${url%%/archive/*}"; ref="${url##*/archive/}"; ref="${ref%.tar.gz}"; ref="${ref%.zip}" ;;
    https://gitlab.com/*/-/archive/*)
      repo="${url%%/-/archive/*}"; ref="${url#*/-/archive/}"; ref="${ref%%/*}" ;;
    *) echo "Don't know how to fetch $url" >&2; exit 1 ;;
  esac
  dir="$WORK/deps/$name"
  if [ ! -d "$dir/.git" ]; then
    echo "Fetching $name: $repo @ $ref"
    git init -q "$dir"
    git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
    git -C "$dir" checkout -q FETCH_HEAD
  fi
  local upper
  upper=$(echo "$name" | tr '[:lower:]' '[:upper:]')
  CMAKE_DEPS+=("-DFETCHCONTENT_SOURCE_DIR_${upper}=$dir")
}

CMAKE_DEPS=()
fetch kaldi-native-fbank.cmake   kaldi_native_fbank
fetch kaldi-decoder.cmake        kaldi_decoder
fetch eigen.cmake                eigen
fetch openfst.cmake              openfst
fetch simple-sentencepiece.cmake simple-sentencepiece
fetch json.cmake                 json
fetch espeak-ng-for-piper.cmake  espeak_ng
fetch piper-phonemize.cmake      piper_phonemize
# Pulled in by the dependencies' own cmake files:
fetch "$WORK/deps/kaldi_native_fbank/cmake/kissfft.cmake" kissfft
fetch "$WORK/deps/kaldi_decoder/cmake/kaldifst.cmake"     kaldifst

# --- Configure & build -----------------------------------------------------------------------
BUILD="$WORK/build-android-arm64-v8a"
mkdir -p "$BUILD"
export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT/jni/arm64-v8a"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT/headers"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_ENABLE_TTS=ON \
  -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DSHERPA_ONNX_ENABLE_C_API=OFF \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
  -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_CHECK=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
  -DSHERPA_ONNX_ENABLE_RKNN=OFF \
  -DSHERPA_ONNX_ENABLE_QNN=OFF \
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
  -DBUILD_PIPER_PHONMIZE_EXE=OFF \
  -DBUILD_PIPER_PHONMIZE_TESTS=OFF \
  -DBUILD_ESPEAK_NG_EXE=OFF \
  -DBUILD_ESPEAK_NG_TESTS=OFF \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
  "${CMAKE_DEPS[@]}"
cmake --build "$BUILD" --target sherpa-onnx-jni -j"$(nproc)"

# --- Vendor into the app ---------------------------------------------------------------------
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI"
cp "$(find "$BUILD" -name libsherpa-onnx-jni.so | head -1)" "$JNI/"
cp "$ORT/jni/arm64-v8a/libonnxruntime.so" "$JNI/"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$JNI/libsherpa-onnx-jni.so"

KT="$ROOT/app/src/main/java/com/k2fsa/sherpa/onnx"
mkdir -p "$KT"
cp "$SRC/sherpa-onnx/kotlin-api/Tts.kt" "$KT/"
echo "$SHERPA_VERSION" > "$KT/.sherpa-version"
ls -la "$JNI" "$KT"
