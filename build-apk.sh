#!/data/user/0/app.dsh.mobile/files/engine/bin/bash
# Bubble Notifications — on-device APK build (no Gradle / Android SDK needed).
#
# Toolchain used (all preinstalled on this device):
#   * OpenJDK 17        -> $PREFIX/extensions/openjdk-17
#   * aapt / zipalign   -> $DSH_HOME/ff-build/toolchain/bin
#   * d8 / apksigner    -> $DSH_HOME/ff-build/toolchain/share/java
#   * android.jar       -> $HOME/android.jar
#   * framework res     -> /system/framework/framework-res.apk
#
# Usage:  bash build-apk.sh
# Output: dist/BubbleNotify-<version>-v<code>-debug-signed.apk
#         dist/BubbleNotify-<version>-v<code>-unsigned.apk
#
# Version code / name are read from build.gradle, so keep that file in sync with
# src/main/resources/META-INF/xposed/module.prop before building.
set -eu

SRC="$(cd "$(dirname "$0")" && pwd)"
EXT=/data/user/0/app.dsh.mobile/files/engine/extensions
JDK="$EXT/openjdk-17/lib/jvm/java-17-openjdk"
H="$DSH_HOME/ff-build"
# The bundled aapt/zipalign/d8/apksigner tree is installed under usr/; keep a
# fallback to the old layout so this script works with either toolchain package.
if [ -x "$H/toolchain/usr/bin/aapt" ]; then
    T="$H/toolchain/usr"
else
    T="$H/toolchain"
fi
export LD_LIBRARY_PATH="$JDK/lib:$JDK/lib/server:$EXT/openjdk-17/lib:$T/lib:$EXT/android-buildtools/lib:$EXT/python/lib"

AAPT="$T/bin/aapt"
ZIPALIGN="$T/bin/zipalign"
D8_JAR="$T/share/java/d8.jar"
SIGNER_JAR="$T/share/java/apksigner.jar"
PYTHON="$EXT/python/bin/python3"
ANDROID_JAR="$HOME/android.jar"
FRAMEWORK_RES=/system/framework/framework-res.apk
# Offline copies of the two Gradle dependencies (compileOnly libxposed API +
# pinyin4j) so this script needs no network and no Gradle cache.
LXP_JAR="$SRC/libs/libxposed-api-101.0.1.jar"
PINYIN_JAR="$SRC/libs/pinyin4j-2.5.1.jar"
for jar in "$LXP_JAR" "$PINYIN_JAR" "$D8_JAR" "$SIGNER_JAR"; do
    [ -f "$jar" ] || { echo "missing dependency: $jar"; exit 1; }
done

VERSION_CODE=$(sed -n "s/.*versionCode \([0-9][0-9]*\).*/\1/p" "$SRC/build.gradle" | head -1)
VERSION_NAME=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" "$SRC/build.gradle" | head -1)
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "cannot read version from build.gradle"; exit 1; }
echo "== building BubbleNotify v$VERSION_NAME ($VERSION_CODE)"

PREFIX_OUT="BubbleNotify-$VERSION_NAME-v$VERSION_CODE"
OUT="$SRC/build-ondevice"
DIST="$SRC/dist"
KS="$SRC/keystore/bubble-debug.jks"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/manifest" "$OUT/stage/META-INF/xposed" "$DIST"

echo "[1/7] aapt: compile resources + generate R.java + package resources"
# aapt (v1) requires the package attribute; AGP injects it during manifest merging.
sed 's|xmlns:android="http://schemas.android.com/apk/res/android"|xmlns:android="http://schemas.android.com/apk/res/android"\n    package="io.github.freeform.bubble"|' \
    "$SRC/src/main/AndroidManifest.xml" > "$OUT/manifest/AndroidManifest.xml"
"$AAPT" package -f -m -J "$OUT/gen" \
    -M "$OUT/manifest/AndroidManifest.xml" -S "$SRC/src/main/res" \
    -I "$FRAMEWORK_RES" --custom-package io.github.freeform.bubble \
    --min-sdk-version 35 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -F "$OUT/resources.apk" > "$OUT/aapt.log" 2>&1
"$AAPT" package -f \
    -M "$OUT/manifest/AndroidManifest.xml" -S "$SRC/src/main/res" \
    -I "$FRAMEWORK_RES" \
    --min-sdk-version 35 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -F "$OUT/base.apk" >> "$OUT/aapt.log" 2>&1

echo "[2/7] javac: compile $(find "$SRC/src/main/java" -name '*.java' | wc -l) sources"
find "$SRC/src/main/java" -name '*.java' > "$OUT/sources.txt"
echo "$OUT/gen/io/github/freeform/bubble/R.java" >> "$OUT/sources.txt"
"$JDK/bin/javac" -nowarn -source 17 -target 17 \
    -cp "$ANDROID_JAR:$LXP_JAR:$PINYIN_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/7] d8: dex"
# pinyin4j is an "implementation" dependency in build.gradle, so its classes must
# be inside the APK. The libxposed API is compileOnly (provided at runtime by the
# framework) and therefore stays on the compile classpath only.
"$JDK/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 35 \
    --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') "$PINYIN_JAR"

echo "[4/7] pack classes.dex + META-INF/xposed metadata + pinyin4j data"
cp "$SRC/src/main/resources/META-INF/xposed/java_init.list" "$OUT/stage/META-INF/xposed/"
cp "$SRC/src/main/resources/META-INF/xposed/module.prop" "$OUT/stage/META-INF/xposed/"
cp "$SRC/src/main/resources/META-INF/xposed/scope.list" "$OUT/stage/META-INF/xposed/"
cp "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"
# pinyin4j loads its dictionary from the classpath at runtime, so the jar's
# pinyindb/ resources must sit at the APK root (Gradle packages java resources
# automatically; a manual build has to do it explicitly).
"$PYTHON" - "$PINYIN_JAR" "$OUT/stage" <<'PY'
import os, sys, zipfile
jar, stage = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    for info in z.infolist():
        if info.is_dir() or not info.filename.startswith("pinyindb/"):
            continue
        target = os.path.join(stage, info.filename)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as out:
            out.write(z.read(info))
        print("  + " + info.filename)
PY
cp "$OUT/base.apk" "$OUT/unaligned.apk"
"$PYTHON" - "$OUT/unaligned.apk" "$OUT/stage" <<'PY'
import os, sys, zipfile
apk, stage = sys.argv[1], sys.argv[2]
# Append to the aapt-produced archive so resources/manifest keep their exact
# compression (resources.arsc must stay STORED and later 4-byte aligned).
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    existing = set(z.namelist())
    for root, _dirs, files in os.walk(stage):
        for name in sorted(files):
            path = os.path.join(root, name)
            arc = os.path.relpath(path, stage).replace(os.sep, "/")
            if arc in existing:
                raise SystemExit("duplicate entry: " + arc)
            z.write(path, arc)
            print("  + " + arc)
PY

echo "[5/7] zipalign"
"$ZIPALIGN" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
cp "$OUT/aligned.apk" "$DIST/$PREFIX_OUT-unsigned.apk"

echo "[6/7] sign"
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -storepass android -keypass android -alias bubbledebug \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Bubble Notify On-Device Debug, O=DSH Mobile, C=CN" > /dev/null 2>&1
    echo "  generated keystore $KS (store/key password: android)"
fi
"$JDK/bin/java" -jar "$SIGNER_JAR" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias bubbledebug \
    --out "$DIST/$PREFIX_OUT-debug-signed.apk" "$OUT/aligned.apk"

echo "[7/7] verify"
"$JDK/bin/java" -jar "$SIGNER_JAR" verify --print-certs \
    "$DIST/$PREFIX_OUT-debug-signed.apk"
"$AAPT" dump badging "$DIST/$PREFIX_OUT-debug-signed.apk" | sed -n '1,3p'
echo "== done"
ls -la "$DIST"
