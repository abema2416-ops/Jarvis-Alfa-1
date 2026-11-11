#!/usr/bin/env bash
# Сборка «Джарвис-1 Альфа»: движок llama.cpp под Android + APK без Gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$PWD"
OUT="$WORK/build"
ENG="$WORK/engine-cache"
LLAMA_SHA="b8abe41a5081728f9f4c824ef810902a11ab7ffd"
STAMP="$LLAMA_SHA-flags2"
VERSION_NAME="0.2"
VERSION_CODE="${GITHUB_RUN_NUMBER:-1}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -d "$SDK" ] || { echo "ОШИБКА: не найден Android SDK"; exit 1; }
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
[ -d "$NDK" ] || NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
[ -d "$NDK" ] || { echo "ОШИБКА: не найден Android NDK"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* | grep -v -i rc | sort -V | tail -1)"
if [ -f "$SDK/platforms/android-34/android.jar" ]; then PLAT="$SDK/platforms/android-34/android.jar"
else PLAT="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"; fi
echo "== SDK: $SDK"; echo "== NDK: $NDK"; echo "== build-tools: $BT"; echo "== platform: $PLAT"

# ---------- 1. движок ----------
mkdir -p "$ENG"
if [ "$(cat "$ENG/stamp" 2>/dev/null || true)" != "$STAMP" ] || [ ! -s "$ENG/libllama_server.so" ]; then
  echo "== Собираю движок llama.cpp ($LLAMA_SHA)"
  rm -rf "$WORK/llama-src" "$WORK/llama-build"
  mkdir -p "$WORK/llama-src"
  ( cd "$WORK/llama-src" && git init -q && git remote add origin https://github.com/ggml-org/llama.cpp \
    && git fetch -q --depth 1 origin "$LLAMA_SHA" && git checkout -q FETCH_HEAD )
  cmake -S "$WORK/llama-src" -B "$WORK/llama-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF
  cmake --build "$WORK/llama-build" --target llama-server -j"$(nproc)"
  BIN="$(find "$WORK/llama-build" -type f -name llama-server | head -1)"
  [ -n "$BIN" ] || { echo "ОШИБКА: llama-server не собрался"; exit 1; }
  cp "$BIN" "$ENG/libllama_server.so"
  "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip "$ENG/libllama_server.so" || true
  echo "$STAMP" > "$ENG/stamp"
else
  echo "== Движок взят из кэша"
fi
ls -la "$ENG/libllama_server.so"
file "$ENG/libllama_server.so" || true

# ---------- 2. APK ----------
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/apk/lib/arm64-v8a"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" --manifest "$ROOT/app/AndroidManifest.xml" \
  -A "$ROOT/app/assets" --min-sdk-version 28 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"
find "$ROOT/app/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -nowarn -classpath "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$PLAT" --output "$OUT/apk" @"$OUT/classes.txt"
cp "$ENG/libllama_server.so" "$OUT/apk/lib/arm64-v8a/libllama_server.so"
( cd "$OUT/apk" && zip -q -r "$OUT/base.apk" classes.dex lib )
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$ROOT/keystore/jarvis.keystore" --ks-key-alias jarvis \
  --ks-pass pass:jarvis1alpha --key-pass pass:jarvis1alpha --out "$OUT/Jarvis.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/Jarvis.apk" | head -5
ls -la "$OUT/Jarvis.apk"
echo "== ГОТОВО: $OUT/Jarvis.apk"
