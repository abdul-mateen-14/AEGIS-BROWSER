#!/usr/bin/env bash
# Build AegisDiagnostics.apk - a tiny standalone triage app (no Chromium build).
#
# Prereqs: JDK 17 (javac), Android build-tools 35 (aapt2 / d8 / zipalign /
# apksigner), platforms/android-35/android.jar.
#
# Usage:
#   BT=/path/build-tools/35.0.0 ANDROID_JAR=/path/platforms/android-35/android.jar \
#   KEYSTORE=/path/diag.keystore KEYSTORE_PASS=... bash build.sh [out.apk]
#
# The signing key is NOT the release key and must not be: this is a separate
# package (org.aegis.diagnostics). Generate one locally with:
#   keytool -genkeypair -keystore diag.keystore -alias aegis-diag \
#     -keyalg RSA -keysize 2048 -validity 10000 \
#     -storepass <pass> -keypass <pass> \
#     -dname "CN=Aegis Diagnostics, OU=Diagnostics, O=Aegis, C=US"
# Keep the keystore stable across builds so the app can update in place;
# if you lose it, uninstall the old diagnostics app first.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-AegisDiagnostics.apk}"
BT="${BT:?set BT to the build-tools dir (must contain aapt2, zipalign, lib/d8.jar, lib/apksigner.jar)}"
AJ="${ANDROID_JAR:?set ANDROID_JAR to platforms/android-35/android.jar}"
KS="${KEYSTORE:?set KEYSTORE to the diagnostics keystore}"
KS_PASS="${KEYSTORE_PASS:?set KEYSTORE_PASS}"

rm -rf build
mkdir -p build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0 \
  build/res.zip

javac -source 11 -target 11 -classpath "$AJ" \
  -d build/classes java/org/aegis/diagnostics/DiagActivity.java 2>/dev/null

java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 29 --output build/dex build/classes/org/aegis/diagnostics/*.class

(cd build/dex && zip -q "$OLDPWD/build/base.apk" classes.dex)

"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias aegis-diag --ks-pass "env:KEYSTORE_PASS" \
  --key-pass "env:KEYSTORE_PASS" \
  --out "$OUT" build/aligned.apk
java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT"
echo "BUILT: $(pwd)/$OUT"
