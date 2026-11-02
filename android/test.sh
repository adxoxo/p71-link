#!/bin/sh
# Offline protocol tests: codec fixtures, queue, transfer, and independent LZO decoding.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JDK=${JDK:-/home/node/.cache/telegram-info-android/jdk/amazon-corretto-21.0.12.12.1-linux-x64}
LZO=${LZO1X_CJS:-/tmp/colmi-android-preview/node_modules/lzo1x/dist/index.cjs}
OUT="$HERE/build/test"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
"$JDK/bin/javac" --release 17 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" \
  $(find "$HERE/protocol/src/main/java" "$HERE/protocol/src/test/java" -name '*.java')
"$JDK/bin/java" -cp "$OUT/classes" dev.p71link.protocol.ProtocolTests "$OUT/lzo-vectors.txt"
"$JDK/bin/java" -cp "$OUT/classes" dev.p71link.protocol.HeatIndexTests
if [ -f "$LZO" ]; then
  node "$HERE/tools/lzo-check.cjs" "$LZO" "$OUT/lzo-vectors.txt"
else
  echo "lzo1x decoder not found at $LZO; independent LZO check skipped" >&2
  exit 2
fi
