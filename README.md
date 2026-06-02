# JellyfinTif

Android TV Input Framework (TIF) provider for Jellyfin Live TV. Adds Jellyfin channels and EPG to the system **Live Channels** app on Android TV — no separate launcher UI.

## Requirements

- Android TV device (leanback)
- Jellyfin server with Live TV configured
- **Quick Connect enabled** on the server (Dashboard → Quick Connect)

## Setup

1. Install the APK on your Android TV.
2. Open **Settings → Device Preferences → Live TV** (or **Channels → Channel sources**).
3. Add **Jellyfin Live TV** as an input source.
4. Enter your server URL and complete **Quick Connect** authorization.
5. Wait for channel and guide sync to finish.

## Building locally

Requires a **full system JDK** (17+; CI uses 21). Cursor/VS Code must not supply the build JDK — `gradlew` ignores IDE-bundled `JAVA_HOME` and pins the Gradle daemon via `gradle/gradle-daemon-jvm.properties`.

Optional: point the Java extension at your JDK before opening the project:

```bash
export JELLYFINTIF_JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # adjust for your OS
```

```bash
./gradlew assembleDebug
```

Release builds use git tags for versioning:

```bash
git tag v0.1.0
./gradlew assembleRelease
```

### Optional: FFmpeg decoder

Some TVs (notably Sony with MTK chipsets) need software decode for reliable TIF compositing. Place the Media3 FFmpeg extension AAR at:

```
app/libs/lib-decoder-ffmpeg-release.aar
```

Build from [wholphin-extensions](https://github.com/damontecres/wholphin-extensions) or use the published artifact documented in Wholphin.

### Local release signing

Add to `local.properties`:

```properties
release.signing.config=release
```

Then define a `release` signing config in `app/build.gradle.kts` or use Android Studio's signing UI.

## CI/CD

| Workflow | Trigger | Output |
|----------|---------|--------|
| [PR](.github/workflows/pr.yml) | Pull requests | Debug APK artifact + unit tests |
| [CI](.github/workflows/ci.yml) | Push to `main` | Debug APK artifact + unit tests |
| [Develop](.github/workflows/develop.yml) | Push to `main` | Pre-release at tag `develop` with signed release APK |
| [Release](.github/workflows/release.yml) | Push tag `v*` | GitHub Release with signed APKs |

### Creating a release

1. Configure repository secrets (see below).
2. Tag and push:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The [Release workflow](.github/workflows/release.yml) builds signed APKs and publishes a GitHub Release with auto-generated notes.

Every push to `main` also updates the **`develop`** pre-release with the latest signed build (when signing secrets are configured).

### Repository secrets

| Secret | Purpose |
|--------|---------|
| `SIGNING_KEY` | Base64-encoded release keystore (`.jks` / `.keystore`) |
| `KEY_ALIAS` | Key alias in the keystore |
| `KEY_PASSWORD` | Key password |
| `KEY_STORE_PASSWORD` | Keystore password |

Without signing secrets, release APKs are built unsigned (suitable for sideloading only).

## Debugging connection issues

Filter logcat to connection diagnostics (debug builds also log HTTP requests):

```bash
adb logcat -s Connection JellyfinTif
```

Log prefixes:
- `[setup]` — server discovery, Quick Connect, setup activity
- `[session]` — session save/restore
- `[sync]` — EPG/channel sync worker
- `[playback]` — tune and stream resolution
- `[http]` — HTTP method, host/path, status (debug builds only; no auth headers)

Secrets (access tokens, Quick Connect secrets) are never logged.

## Architecture

- **No launcher activity** — discovered only when adding a Live TV input (`SETUP_INPUT`).
- **Single session** — one server + one user persisted via DataStore.
- **Quick Connect only** — no password login or multi-user switching.

## License

Device profile code adapted from [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) (GPL-2.0). See `app/src/main/java/.../util/profile/`.

Unofficial Jellyfin client — not affiliated with the Jellyfin project.
