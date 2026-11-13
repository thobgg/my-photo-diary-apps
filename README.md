# My Photo Diary — Apps

**The native clients for [My Photo Diary](https://github.com/thobgg/my-photo-diary): Android, Linux and Windows.**

> 🇩🇪 [Deutsche Fassung: README.de.md](README.de.md)

My Photo Diary (MPD) turns folders of photos on your own NAS into diaries.
The server keeps the albums; these apps read and write them through its
REST API. One code base (Kotlin, Compose Multiplatform), three targets:

- **Android** (`app/`): the everyday client — albums, timeline, memories,
  camera backup straight into the server's inbox, notifications without
  Google services, share and receive photos from other apps.
- **Desktop** (`desktop/`): Linux `.deb` and Windows `.exe`/`.msi`, with the
  same screens plus folders on this PC in the timeline.
- **Shared** (`shared/`): everything the two have in common — screens, logic,
  the API client.

The apps talk to your server only. There is no account with anyone, no
telemetry and no push service; the server address is entered once, or
scanned as a QR code from the server's "Connect app" page.

## Install

Ready-made packages: [bgg-home.de/mpd.apk](https://bgg-home.de/mpd.apk)
(Android), [bgg-home.de/mpd.deb](https://bgg-home.de/mpd.deb) (Linux),
[bgg-home.de/mpd-setup.exe](https://bgg-home.de/mpd-setup.exe) (Windows).
The apps need an MPD server, version 3.0 or newer.

## Build

JDK 21 and the Android SDK (for the Android target).

```bash
./gradlew :shared:testDebugUnitTest :shared:desktopTest   # unit tests
./gradlew assembleDebug                                    # Android APK
./gradlew :desktop:run                                     # desktop, straight from source
./gradlew :desktop:packageDeb                              # Linux package
```

Windows installers are built on Windows with `jpackage`; see
[`docs/BUILD-WINDOWS.md`](docs/BUILD-WINDOWS.md) (German).

## Documentation

- [`docs/PDX_SPEC_v1_5.pdf`](docs/PDX_SPEC_v1_5.pdf) — the album format the
  apps read and write
- The server repository holds the API and the architecture

## License

My Photo Diary Apps are licensed under the **GNU Affero General Public
License v3.0** ([LICENSE](LICENSE)). © 2026 Thomas Bugge.

The bundled fonts are licensed under the SIL Open Font License 1.1
(`docs/fonts-lizenzen/`).
