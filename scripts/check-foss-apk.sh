#!/usr/bin/env bash
# Fails if an APK carries anything proprietary: ML Kit, Google Play services
# or Firebase classes in its dex files, or their components in its manifest.
# The foss flavor promises it has none; the release workflow holds it to that.
#
#   scripts/check-foss-apk.sh app/android/build/outputs/apk/foss/release/android-foss-release.apk
#
# Needs unzip and the Android SDK's build-tools (aapt2), found through
# ANDROID_HOME. Dex files keep class names as plain strings ("Lcom/google/mlkit/…;"),
# so a search of the bytes finds a class R8 kept under its own name; ML Kit and
# Play services are reached by name and are never renamed away entirely.
set -euo pipefail

apk="${1:?usage: check-foss-apk.sh <apk>}"
forbidden='com/google/mlkit|com/google/android/gms|com/google/firebase'
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

unzip -q -o "$apk" 'classes*.dex' -d "$work"
found=0
for dex in "$work"/classes*.dex; do
  hits="$(grep -a -o -E "L($forbidden)/[A-Za-z0-9_/\$]*;" "$dex" | sort -u || true)"
  if [[ -n "$hits" ]]; then
    echo "$(basename "$dex") has proprietary classes:"
    echo "$hits" | head -20
    found=1
  fi
done

aapt2="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/aapt2 | sort -V | tail -1)"
manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
hits="$(echo "$manifest" | grep -E "${forbidden//\//\\.}" || true)"
if [[ -n "$hits" ]]; then
  echo "AndroidManifest.xml names proprietary components:"
  echo "$hits" | head -20
  found=1
fi

# Native libraries and other files ML Kit would bring along.
hits="$(unzip -Z1 "$apk" | grep -i -E 'mlkit|translate_jni|libbarhopper|play-services|firebase' || true)"
if [[ -n "$hits" ]]; then
  echo "The APK has proprietary files:"
  echo "$hits" | head -20
  found=1
fi

if [[ "$found" -ne 0 ]]; then
  echo "FAILED: $apk is not free of ML Kit / Play services / Firebase"
  exit 1
fi
echo "OK: no ML Kit, Play services or Firebase in $(basename "$apk")"
