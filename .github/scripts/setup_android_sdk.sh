#!/usr/bin/env bash
# Installs the Android SDK packages this project needs on a GitHub-hosted
# Ubuntu runner. Deliberately avoids third-party actions so failures point
# straight at the missing tool instead of an opaque action error.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
echo "Android SDK root: $SDK"
ls "$SDK" || true

SDKMANAGER="$(find "$SDK/cmdline-tools" -maxdepth 3 -type f -name sdkmanager 2>/dev/null | head -n1 || true)"
if [ -z "$SDKMANAGER" ]; then
  echo "::error::sdkmanager not found under $SDK/cmdline-tools"
  exit 1
fi
echo "sdkmanager: $SDKMANAGER"

# Accept licenses non-interactively (safe to re-run).
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

if ! "$SDKMANAGER" --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"; then
  echo "::error::sdkmanager failed to install Android SDK packages"
  exit 1
fi

# Point Gradle at the SDK explicitly so no local.properties guesswork is needed.
echo "sdk.dir=$SDK" > local.properties
echo "Installed platforms:"; ls "$SDK/platforms"
echo "Installed build-tools:"; ls "$SDK/build-tools"
