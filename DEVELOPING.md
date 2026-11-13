# Developing Crunch-o-meter

Setup for using the app is in the [README](README.md). This file covers building, testing and the code itself.

## How the pieces connect

```
phone ──tailnet──▶ tailscale serve (HTTPS) ──▶ crunch-o-meter on 127.0.0.1:8765
                                                 ├─ BOINC GUI RPC        127.0.0.1:31416
                                                 ├─ Gridcoin wallet RPC  127.0.0.1:15715
                                                 ├─ NVML + psutil
                                                 └─ LACT config + daemon socket (read-only)
```

## Server

### API

Everything except `/api/health` needs `Authorization: Bearer <token>`.

| Endpoint | Returns |
|---|---|
| `GET /api/summary` | all sections at once (what the app polls) |
| `GET /api/boinc`, `/gridcoin`, `/system`, `/lact` | one section |
| `GET /api/health` | `{"ok": true}`, no auth |

Each section looks like `{ok, error, updated_at, data}`. When a poll fails, `data` keeps the last good result and `error` says what broke.

### Tests

```bash
cd server
.venv/bin/pip install -e '.[test]'
.venv/bin/pytest
```

The tests run against fake BOINC, wallet and LACT services. They also write `android/app/src/test/resources/sample_summary.json`, which the Android unit tests decode, so a change to the JSON on one side breaks the build on the other.

### Gridcoin estimate formula

```
mag_per_rac         = (network magnitude / whitelisted projects) / project's total RAC
estimated magnitude = sum of (your RAC on project × mag_per_rac)
```

Project totals and the whitelist are on gridcoinstats.eu. The network magnitude total is on the Gridcoin wiki's magnitude page (115,000 under the Fern protocol; check it's still current).

## Android app

AGP 9.4 (Kotlin built in), Gradle 9.6, Kotlin 2.4.21, Compose BOM 2026.08.00, Material 3, Glance 1.2, WorkManager 2.12, OkHttp 5, kotlinx.serialization. compileSdk 37, targetSdk 36, minSdk 26.

For day-to-day work, open `android/` in Android Studio.

### Release builds

On Arch:

```bash
./install_deps.sh        # JDK 21, Android SDK command-line tools, SDK platform 37
./build.sh --new-key     # only if you don't have a release keystore yet
./build.sh
```

`build.sh` signs with `android/keystore.properties` and the password on the first line of `password.txt` in the repo root (both git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
keyAlias=...
```

If the keystore and key passwords differ, put `storePassword=` and `keyPassword=` lines in `keystore.properties` instead.

It refuses to build without a key, runs the unit tests, and writes `dist/crunch-o-meter-<version>.apk`. Back up the keystore. Without it, you can't ship an update that installs over a build signed with it.

### Releasing a new version

1. In `android/app/build.gradle.kts`, set `versionName` (e.g. `"1.1.0"`) and raise `versionCode` by one. It must always go up, or phones won't install the update.
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: what changed, in plain words, under 500 characters.
3. Commit, then run `./build.sh`.
4. On GitHub, draft a release with the tag `v<versionName>` and attach `dist/crunch-o-meter-<versionName>.apk`.

IzzyOnDroid picks the APK up from the release. F-Droid finds the new version from the tag and the numbers in `build.gradle.kts`, which is why they're plain literals there.

### Store listing

`fastlane/metadata/android/en-US/` holds the title, descriptions, icon, screenshots and per-version changelogs that F-Droid and IzzyOnDroid display. Screenshots go in `images/phoneScreenshots/` as `1.png`, `2.png` and so on, in the order they should appear.

### AUR package

`packaging/aur/` holds the `PKGBUILD` and post-install message for the `crunch-o-meter` AUR package. It builds the server from the GitHub release tarball and installs the systemd user unit with `ExecStart=/usr/bin/crunch-o-meter`.

For each release: copy both files into your AUR clone, set `pkgver` (and reset `pkgrel=1`), then run `updpkgsums`, `makepkg -si`, `namcap PKGBUILD *.pkg.tar.zst` and `makepkg --printsrcinfo > .SRCINFO`, and push. See the ArchWiki's "AUR submission guidelines".

### Donation reminder

The reminder appears from the third launch, at most once every four days, and only after a successful refresh. `data/Donations.kt` holds the addresses, the timing and the code that turns it off. An empty address list disables it.

## GitHub Actions

- **`ci.yml`** runs on every push and pull request: server tests, then the Android unit tests, lint and a release build. The APK is attached to the run as an artifact (debug-signed). Releases are built locally with `build.sh` and uploaded by hand.

## Project layout

```
server/
  crunchometer/        boinc_rpc, gridcoin, system (CPU/NVML), lact, app (FastAPI), __main__
  tests/               fakes for BOINC, the wallet and LACT, plus API tests
  config.example.toml
  crunch-o-meter.service
android/app/src/main/java/com/henw/crunchometer/
  MainActivity.kt      scaffold, bottom nav, polling
  data/                JSON models, HTTP client, settings, donation config
  ui/                  view model, donation dialog, screens, shared components
  widget/              Glance widget, its settings screen, background refresh
install_deps.sh        build dependencies (Arch)
build.sh               signed release build
```

## Further reading

- BOINC GUI RPC: the "GUI RPC" page on the BOINC wiki, and `lib/gui_rpc_client.cpp` in the BOINC source.
- Gridcoin wallet commands: type `help` in the wallet's debug console.
- LACT's config file: `docs/CONFIG.md` in the LACT repository.
- NVML: NVIDIA's NVML API reference, "Device Queries".
- AGP 9 and built-in Kotlin: Android Developers, "Migrate to built-in Kotlin".
