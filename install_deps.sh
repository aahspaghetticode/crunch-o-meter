#!/usr/bin/env bash
# Installs what you need to build the Crunch-o-meter APK on Arch Linux:
# a JDK, the Android SDK command-line tools, and the SDK packages the app compiles against.
#
#   ./install_deps.sh
#
# Override where the SDK goes with ANDROID_HOME=/some/path ./install_deps.sh
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
PLATFORM="platforms;android-37"     # must match compileSdk in android/app/build.gradle.kts
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# --- JDK ---------------------------------------------------------------------
step "JDK"
if command -v pacman >/dev/null; then
    sudo pacman -S --needed --noconfirm jdk21-openjdk unzip curl
else
    command -v java >/dev/null || die "Not Arch: install JDK 17 or newer, unzip and curl yourself, then rerun."
fi
if [ -d /usr/lib/jvm/java-21-openjdk ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
fi
java -version 2>&1 | head -n1

# --- Android command-line tools ---------------------------------------------
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
step "Android SDK command-line tools in $SDK"
if [ -x "$SDKMANAGER" ]; then
    echo "already installed"
else
    # Google bumps the build number in the file name, so read the current one off the download page.
    ZIP_NAME="$(curl -fsSL https://developer.android.com/studio \
        | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | head -n1 || true)"
    [ -n "$ZIP_NAME" ] || die "Couldn't find the command-line tools download. Get the Linux zip from
       https://developer.android.com/studio#command-line-tools-only
       and unzip it so that $SDK/cmdline-tools/latest/bin/sdkmanager exists, then rerun."
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    echo "downloading $ZIP_NAME"
    curl -fL --progress-bar -o "$TMP/tools.zip" "https://dl.google.com/android/repository/$ZIP_NAME"
    unzip -q "$TMP/tools.zip" -d "$TMP"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

# --- SDK packages -----------------------------------------------------------
step "SDK packages"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" --install "platform-tools" "$PLATFORM"
# Gradle downloads the exact build-tools version it wants on the first build.

# --- Tell Gradle where the SDK is --------------------------------------------
echo "sdk.dir=$SDK" > "$ROOT/android/local.properties"

step "Done"
cat <<EOF
SDK: $SDK  (written to android/local.properties)

Add these to your ~/.bashrc so other tools find them:
  export ANDROID_HOME="$SDK"
  export JAVA_HOME=${JAVA_HOME:-<your JDK>}
  export PATH="\$PATH:\$ANDROID_HOME/platform-tools"

Next: ./build.sh
EOF
