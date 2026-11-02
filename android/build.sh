#!/bin/sh
# Reproducible APK build with the platform tools only: aapt2, javac, d8, zipalign, apksigner.
# No Gradle, no third-party runtime libraries. Output: build/p71link-debug.apk (development build).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CACHE=${ANDROID_TOOL_CACHE:-/home/node/.cache/telegram-info-android}
JDK=${JDK:-$CACHE/jdk/amazon-corretto-21.0.12.12.1-linux-x64}
SDK=${ANDROID_SDK:-$CACHE/sdk}
BT=$SDK/build-tools/36.0.0
PLATFORM=$SDK/platforms/android-36/android.jar
OUT=$HERE/build
WORK=$OUT/work
KEYDIR=$HERE/.local
VERSION_CODE=${VERSION_CODE:-4}
VERSION_NAME=${VERSION_NAME:-0.3.0-dev}

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

for tool in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM" "$JDK/bin/javac"; do
  [ -e "$tool" ] || { echo "Missing build tool: $tool" >&2; exit 1; }
done

rm -rf "$WORK" && mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex"

# 1. Resources.
"$BT/aapt2" compile --dir "$HERE/app/src/main/res" -o "$WORK/res/compiled.zip"
"$BT/aapt2" link -o "$WORK/base.apk" -I "$PLATFORM" \
  --manifest "$HERE/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 33 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" "$WORK/res/compiled.zip"

# 2. Java: protocol core plus Android app, compiled against the API 36 platform.
find "$HERE/protocol/src/main/java" "$HERE/app/src/main/java" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JDK/bin/javac" --release 17 -Xlint:-options -encoding UTF-8 -classpath "$PLATFORM" \
  -d "$WORK/classes" @"$WORK/sources.txt"

# 3. Dex.
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 33 --lib "$PLATFORM" --output "$WORK/dex" @"$WORK/classes.txt"

# 4. Package, align, sign.
cp "$WORK/base.apk" "$WORK/unsigned.apk"
python3 -I -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    info = zipfile.ZipInfo("classes.dex", date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, open(sys.argv[2], "rb").read())
' "$WORK/unsigned.apk" "$WORK/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"
if [ ! -f "$KEYDIR/p71link-dev.keystore" ]; then
  # Task-owned development key. The password stays in an ignored file and is never printed.
  umask 077
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$KEYDIR/keystore.pass"
  "$JDK/bin/keytool" -genkeypair -noprompt -keystore "$KEYDIR/p71link-dev.keystore" -storetype PKCS12 \
    -alias p71link -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=P71 Link development build" \
    -storepass:file "$KEYDIR/keystore.pass" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYDIR/p71link-dev.keystore" --ks-key-alias p71link \
  --ks-pass "file:$KEYDIR/keystore.pass" \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/p71link-debug.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$OUT/p71link-debug.apk"
rm -f "$OUT/p71link-debug.apk.idsig"

SIZE=$(stat -c %s "$OUT/p71link-debug.apk")
SHA=$(sha256sum "$OUT/p71link-debug.apk" | cut -d' ' -f1)
echo "APK $OUT/p71link-debug.apk"
echo "SIZE $SIZE"
echo "SHA256 $SHA"
