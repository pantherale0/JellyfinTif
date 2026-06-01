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

## Building

```bash
./gradlew assembleDebug
```

### Optional: FFmpeg decoder

Some TVs (notably Sony with MTK chipsets) need software decode for reliable TIF compositing. Place the Media3 FFmpeg extension AAR at:

```
app/libs/lib-decoder-ffmpeg-release.aar
```

Build from [wholphin-extensions](https://github.com/damontecres/wholphin-extensions) or use the published artifact documented in Wholphin.

## Architecture

- **No launcher activity** — discovered only when adding a Live TV input (`SETUP_INPUT`).
- **Single session** — one server + one user persisted via DataStore.
- **Quick Connect only** — no password login or multi-user switching.

## License

Device profile code adapted from [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) (GPL-2.0). See `app/src/main/java/.../util/profile/`.

Unofficial Jellyfin client — not affiliated with the Jellyfin project.
