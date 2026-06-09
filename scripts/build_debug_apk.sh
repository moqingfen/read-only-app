#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DIR="$ROOT_DIR/.local-toolchain/java/amazon-corretto-17.jdk/Contents/Home"
SDK_DIR="$ROOT_DIR/.local-toolchain/android-sdk"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
OUT_DIR="$ROOT_DIR/build/manual-debug"
APK_OUT_DIR="$ROOT_DIR/app/build/outputs/apk/debug"
APK_FILE="$APK_OUT_DIR/app-debug.apk"
NAMED_APK_FILE="$APK_OUT_DIR/read-only-vivo-x-fold5-debug.apk"
KEYSTORE="$ROOT_DIR/.android-home/debug.keystore"
MANIFEST_FOR_AAPT="$OUT_DIR/AndroidManifest.xml"

export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_USER_HOME="$ROOT_DIR/.android-home"
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS_DIR:$SDK_DIR/platform-tools:$SDK_DIR/cmdline-tools/latest/bin:/usr/bin:/bin:/usr/sbin:/sbin"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/res" "$OUT_DIR/generated" "$OUT_DIR/classes" "$OUT_DIR/dex" "$APK_OUT_DIR" "$(dirname "$KEYSTORE")"

awk '
  !done && /<manifest[[:space:]]/ {
    sub(/<manifest[[:space:]]*/, "<manifest package=\"com.nothingreader.app\" ")
    done = 1
  }
  { print }
' "$ROOT_DIR/app/src/main/AndroidManifest.xml" > "$MANIFEST_FOR_AAPT"

"$BUILD_TOOLS_DIR/aapt2" compile --dir "$ROOT_DIR/app/src/main/res" -o "$OUT_DIR/compiled-res.zip"

"$BUILD_TOOLS_DIR/aapt2" link \
  -o "$OUT_DIR/base-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST_FOR_AAPT" \
  --java "$OUT_DIR/generated" \
  --custom-package com.nothingreader.app \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 0.1.0 \
  "$OUT_DIR/compiled-res.zip"

find "$ROOT_DIR/app/src/main/java" "$OUT_DIR/generated" -name '*.java' | sort > "$OUT_DIR/sources.list"
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -source 17 \
  -target 17 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT_DIR/classes" \
  @"$OUT_DIR/sources.list"

find "$OUT_DIR/classes" -name '*.class' | sort > "$OUT_DIR/classes.list"
"$BUILD_TOOLS_DIR/d8" \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT_DIR/dex" \
  @"$OUT_DIR/classes.list"

cp "$OUT_DIR/base-unsigned.apk" "$OUT_DIR/base-with-dex.apk"
"$JAVA_HOME/bin/jar" uf "$OUT_DIR/base-with-dex.apk" -C "$OUT_DIR/dex" classes.dex

"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$OUT_DIR/base-with-dex.apk" "$OUT_DIR/app-debug-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    >/dev/null
fi

"$BUILD_TOOLS_DIR/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK_FILE" \
  "$OUT_DIR/app-debug-aligned.apk"

cp "$APK_FILE" "$NAMED_APK_FILE"

"$BUILD_TOOLS_DIR/apksigner" verify --verbose "$APK_FILE"
"$BUILD_TOOLS_DIR/aapt" dump badging "$APK_FILE" | sed -n '1,8p'

echo "APK: $APK_FILE"
echo "Named APK: $NAMED_APK_FILE"
