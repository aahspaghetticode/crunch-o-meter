<img src="docs/icon.png" width="96" align="right" alt="Crunch-o-meter icon">

# Crunch-o-meter

Check on your BOINC box from your phone. An Android app plus a small server that runs next to BOINC on Arch.

What you see in the app:

- **Tasks:** progress on every running and queued task, and what finished recently (waiting to report, reported, errored).
- **Gridcoin:** your magnitude, straight from the local wallet. If the wallet isn't running, an estimate from your RAC.
- **System:** CPU and GPU load with five-minute graphs, temperatures, clocks, power draw, VRAM, fan speed.
- **LACT:** your saved overclock, power limit and fan settings. Read-only.
- **Home screen widget:** running tasks, top task progress, magnitude, CPU and GPU. Transparent by default.

The phone only ever talks to the server, over Tailscale, with a token. BOINC, the wallet and LACT never see the network.

```
phone ──tailnet──▶ tailscale serve (HTTPS) ──▶ crunch-o-meter on 127.0.0.1:8765
                                                 ├─ BOINC GUI RPC        127.0.0.1:31416
                                                 ├─ Gridcoin wallet RPC  127.0.0.1:15715
                                                 ├─ NVML + psutil
                                                 └─ LACT config + daemon socket (read-only)
```

## Server

Unzip or clone into `~/crunch-o-meter`, then:

```bash
cd ~/crunch-o-meter/server
python -m venv .venv
.venv/bin/pip install .

mkdir -p ~/.config/crunch-o-meter
cp config.example.toml ~/.config/crunch-o-meter/config.toml
.venv/bin/crunch-o-meter gen-token        # paste it into [server] token
.venv/bin/crunch-o-meter check            # tries BOINC, the wallet, the GPU and LACT once
```

`check` tells you what's missing. Usually it's one of these:

| Needs | Fix |
|---|---|
| BOINC's RPC password (`/var/lib/boinc/gui_rpc_auth.cfg`) | `sudo usermod -aG boinc $USER`, or set `boinc.password` |
| LACT's daemon socket | be in a group listed under `daemon.admin_groups` in `/etc/lact/config.yaml` (wheel by default) |
| Gridcoin wallet RPC | `server=1`, `rpcuser=` and `rpcpassword=` in `~/.GridcoinResearch/gridcoinresearch.conf`. Leave `rpcallowip` out so it stays local |

Log out and back in after changing groups.

Run it as a service:

```bash
cp crunch-o-meter.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now crunch-o-meter
loginctl enable-linger "$USER"            # keeps it running when you're logged out
journalctl --user -u crunch-o-meter -f
```

### Put it on your tailnet

```bash
sudo tailscale serve --bg 8765
tailscale serve status                    # prints https://<host>.<tailnet>.ts.net
```

This needs HTTPS certificates switched on for the tailnet (Tailscale admin console, DNS page). See `tailscale serve --help`.

No `tailscale serve`? Set `bind_host` to the box's `tailscale ip -4` and use `http://<host>.<tailnet>.ts.net:8765` in the app. The app allows plain HTTP to `*.ts.net` names only.

### API

Everything except `/api/health` needs `Authorization: Bearer <token>`.

| Endpoint | Returns |
|---|---|
| `GET /api/summary` | all sections at once (what the app polls) |
| `GET /api/boinc`, `/gridcoin`, `/system`, `/lact` | one section |
| `GET /api/health` | `{"ok": true}`, no auth |

Each section looks like `{ok, error, updated_at, data}`. When a poll fails, `data` keeps the last good result and `error` says what broke.

### Gridcoin estimate

Only used when the wallet RPC can't be reached. Add a factor per project under `[gridcoin.mag_per_rac]`:

```
mag_per_rac        = (network magnitude / whitelisted projects) / project's total RAC
estimated magnitude = sum of (your RAC on project × mag_per_rac)
```

Project totals and the whitelist are on gridcoinstats.eu. The network magnitude total is on the Gridcoin wiki's magnitude page (115,000 under the Fern protocol; check it's still current). The factors drift as projects grow, so update them every so often. When the wallet is up, the app shows the real figure with the estimate underneath for comparison.

### Tests

```bash
.venv/bin/pip install -e '.[test]'
.venv/bin/pytest
```

The tests run against fake BOINC, wallet and LACT services. They also write `android/app/src/test/resources/sample_summary.json`, which the Android unit tests decode, so a change to the JSON on one side breaks the build on the other.

## Android app

AGP 9.4 (Kotlin built in), Gradle 9.6, Kotlin 2.4.21, Compose BOM 2026.08.00, Material 3, Glance 1.2, WorkManager 2.12, OkHttp 5, kotlinx.serialization. Android 8.0 and up; wallpaper colours on Android 12 and up.

1. Open `android/` in Android Studio and let it sync.
2. Run it on your phone.
3. In **Settings**, paste your `https://<host>.<tailnet>.ts.net` address and the token, tap **Test connection**, then **Save**.

It only polls while it's on screen. The token stays in app-private storage and is left out of backups.

### Widget

Long-press the home screen, add **Crunch-o-meter**, and pick what it shows: tasks, magnitude, CPU, GPU. You can also set background opacity (0% is fully transparent) and text tone (follow light/dark mode, always light, always dark). On Android 12+ you can skip the setup when adding it and change it later from the widget's long-press menu.

It refreshes every 15 minutes, which is the shortest interval Android allows in the background, and every minute while the app is open. Tap it to open the app.

### Release builds

On your PC (Arch):

```bash
./install_deps.sh        # JDK 21, Android SDK command-line tools, SDK platform 37
./build.sh --new-key     # only if you don't have a release keystore yet
./build.sh               # or ./build.sh 1.2.0
```

`build.sh` signs with `android/keystore.properties` and the password on the first line of `password.txt` in the repo root (both git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
keyAlias=...
```

If the keystore and key passwords differ, put `storePassword=` and `keyPassword=` lines in `keystore.properties` instead.

It refuses to build without that file, runs the unit tests, and writes `dist/crunch-o-meter-<version>.apk`. The version comes from the argument or the latest `v*` git tag; the version code is the commit count, so each build installs over the last. Back up the keystore: without it you can't ship an update that installs over a build signed with it.

### Donation reminder

After the third launch, the app shows a donation reminder at most once every four days, and only after a successful refresh. The addresses live in `data/Donations.kt`. While that list is empty, the reminder never shows.

The dev code `stfu-bro` turns the reminder off for good. Enter it from the reminder or from Settings. The reminder tells people they can find the code in the source, or donate and email the transaction hash to shutup@clowds.net to get it.

## GitHub Actions

- **`ci.yml`** runs on every push and pull request: server tests, then the Android unit tests, lint and a release build. The APK is attached to the run as an artifact (debug-signed).
- **`release.yml`** runs when you push a tag like `v1.2.0`. It builds a signed APK with the version from the tag and publishes it as a GitHub Release.

For signed releases, add these repository secrets: `CRUNCH_KEYSTORE_BASE64` (output of `base64 -w0 release.jks`), `CRUNCH_KEYSTORE_PASSWORD`, `CRUNCH_KEY_ALIAS`, `CRUNCH_KEY_PASSWORD`. Then:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

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
```

## Further reading

- BOINC GUI RPC: the "GUI RPC" page on the BOINC wiki, and `lib/gui_rpc_client.cpp` in the BOINC source.
- Gridcoin wallet commands: type `help` in the wallet's debug console.
- LACT's config file: `docs/CONFIG.md` in the LACT repository.
- NVML: NVIDIA's NVML API reference, "Device Queries".
- AGP 9 and built-in Kotlin: Android Developers, "Migrate to built-in Kotlin".

## License

MIT. See [LICENSE](LICENSE).
