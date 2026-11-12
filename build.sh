#!/usr/bin/env bash
# Builds a signed release APK into dist/.
#
#   ./build.sh                 # version from the latest git tag (v1.2.0 -> 1.2.0), or 1.0.0
#   ./build.sh 1.3.0           # explicit version
#   ./build.sh --new-key       # create a release keystore + android/keystore.properties, then stop
#
# Signing comes from android/keystore.properties (git-ignored):
#   storeFile=/absolute/path/to/release.jks
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
# This script refuses to build without it, so you never ship a debug-signed APK by accident.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$ROOT/android/keystore.properties"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
prop() { grep -E "^$1=" "$PROPS" | head -n1 | cut -d= -f2-; }

[ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-21-openjdk ] && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
command -v java >/dev/null || die "No JDK found. Run ./install_deps.sh first."

# --- Optional: make a new signing key ----------------------------------------
if [ "${1:-}" = "--new-key" ]; then
    [ -f "$PROPS" ] && die "$PROPS already exists. Move it away first if you really want a new key."
    KEYSTORE="${KEYSTORE:-$HOME/.android/crunch-o-meter-release.jks}"
    mkdir -p "$(dirname "$KEYSTORE")"
    read -rsp "New keystore password (6+ chars): " PW; echo
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias crunch-o-meter \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$PW" -keypass "$PW" -dname "CN=Henw, O=Crunch-o-meter"
    umask 077
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=crunch-o-meter\nkeyPassword=%s\n' \
        "$KEYSTORE" "$PW" "$PW" > "$PROPS"
    step "Key created"
    echo "Keystore: $KEYSTORE"
    echo "Back it up somewhere safe. Lose it and you can never ship an update that installs over this one."
    exit 0
fi

# --- Checks -----------------------------------------------------------------
[ -f "$PROPS" ] || die "No android/keystore.properties. Create it (see the top of this script) or run ./build.sh --new-key"
for k in storeFile storePassword keyAlias keyPassword; do
    [ -n "$(prop "$k")" ] || die "keystore.properties is missing $k"
done
STORE="$(prop storeFile)"
[ -f "$STORE" ] || die "Keystore not found: $STORE"
[ -f "$ROOT/android/local.properties" ] || [ -n "${ANDROID_HOME:-}" ] \
    || die "Android SDK not set up. Run ./install_deps.sh first."

# --- Version ----------------------------------------------------------------
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    VERSION="$(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)"
    VERSION="${VERSION:-1.0.0}"
fi
# Commit count only goes up, so every build can install over the previous one.
CODE="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 1)"

# --- Build ------------------------------------------------------------------
step "Building Crunch-o-meter $VERSION (versionCode $CODE)"
cd "$ROOT/android"
./gradlew --console=plain testDebugUnitTest assembleRelease \
    -Pcrunch.versionName="$VERSION" -Pcrunch.versionCode="$CODE"

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "Build finished but $APK is missing"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/crunch-o-meter-$VERSION.apk"
cp "$APK" "$OUT"

# --- Show who signed it -----------------------------------------------------
SDK="${ANDROID_HOME:-$(grep -E '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2-)}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1 || true)"
step "Done"
echo "$OUT"
if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$OUT" | grep -E 'certificate DN|SHA-256' | head -n2
    if "$APKSIGNER" verify --print-certs "$OUT" | grep -q 'CN=Android Debug'; then
        die "APK is debug-signed. Check keystore.properties."
    fi
fi
