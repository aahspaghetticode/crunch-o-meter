#!/usr/bin/env bash
# Builds and installs the server on Arch Linux from packaging/aur/PKGBUILD, the same
# way the AUR package would. It installs the released version named in the PKGBUILD
# (pkgver), downloaded from GitHub, not the code in this checkout.
#
#   ./install-server.sh                 # build, then install with pacman (asks for sudo)
#   ./install-server.sh --noconfirm     # extra arguments go to makepkg
#
# The build runs in a temporary copy of packaging/aur/, so makepkg's src/, pkg/ and
# downloaded tarball don't end up in the repo.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v makepkg >/dev/null || die "makepkg not found. This script is for Arch Linux."
[ "$(id -u)" -ne 0 ] || die "Don't run this as root; makepkg refuses to. It asks for sudo when it installs."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$ROOT/packaging/aur/PKGBUILD" "$ROOT/packaging/aur/crunch-o-meter.install" "$WORK/"

VERSION="$(grep -E '^pkgver=' "$WORK/PKGBUILD" | cut -d= -f2)"
step "Building and installing crunch-o-meter $VERSION"
cd "$WORK"
makepkg --syncdeps --install --clean "$@"

step "Done"
# A unit in ~/.config overrides the packaged one in /usr/lib/systemd/user, and an older
# pip/venv install would keep running instead of this package.
USER_UNIT="$HOME/.config/systemd/user/crunch-o-meter.service"
if [ -f "$USER_UNIT" ] && ! grep -q '^ExecStart=/usr/bin/crunch-o-meter' "$USER_UNIT"; then
    printf '\033[33mwarning:\033[0m %s overrides the packaged unit and runs:\n  %s\n' \
        "$USER_UNIT" "$(grep -E '^ExecStart=' "$USER_UNIT")"
    echo "To run this package instead: rm \"$USER_UNIT\" && systemctl --user daemon-reload"
fi
echo "Start it with:  systemctl --user enable --now crunch-o-meter"
echo "After an update: systemctl --user restart crunch-o-meter"
