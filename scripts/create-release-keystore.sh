#!/usr/bin/env bash
# ============================================================
# Aegis Browser - one-time release keystore creation (local).
# Run this ONCE on any machine that has a JDK (keytool):
#
#     KEYSTORE_PASSWORD='YourStrongPassword1!' KEY_PASSWORD='YourStrongPassword1!' \
#         ./create-release-keystore.sh
#
# It creates ./aegis-release.keystore and prints:
#   1. the base64 blob -> GitHub Secret  ANDROID_KEYSTORE_BASE64
#   2. reminder values  -> GitHub Secrets ANDROID_KEYSTORE_PASSWORD,
#                          ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
#
# IMPORTANT
#   * Back this file up in a password manager. If you lose it you cannot
#     update the app on your phone - you would have to uninstall/reinstall.
#   * This keystore signs APKs for sideloading. It is NOT needed for the
#     Play Store, which re-signs uploads with Play App Signing.
# ============================================================
set -euo pipefail

: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD (min 6 chars)}"
: "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"
OUT="${1:-aegis-release.keystore}"

keytool -genkeypair -v \
  -keystore "$OUT" \
  -storetype PKCS12 \
  -alias aegis \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" \
  -dname "CN=Aegis Browser, OU=Aegis, O=Aegis, C=US" >/dev/null

echo
echo "Created $OUT"
echo
echo "Add these four GitHub Secrets (repo -> Settings -> Secrets and variables -> Actions):"
echo
echo "  ANDROID_KEYSTORE_BASE64 ="
base64 -w 70 "$OUT" | sed 's/^/      /'
echo
echo "  ANDROID_KEYSTORE_PASSWORD = (the value you typed)"
echo "  ANDROID_KEY_ALIAS         = aegis"
echo "  ANDROID_KEY_PASSWORD      = (usually the same as the keystore password)"
