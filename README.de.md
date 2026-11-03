# My Photo Diary — Apps

**Die nativen Clients für [My Photo Diary](https://github.com/thobgg/my-photo-diary): Android, Linux und Windows.**

> 🇬🇧 [English version: README.md](README.md)

My Photo Diary (MPD) macht aus Foto-Ordnern auf der eigenen NAS Tagebücher.
Der Server verwaltet die Alben; diese Apps lesen und schreiben sie über
seine REST-API. Ein Quelltext (Kotlin, Compose Multiplatform), drei Ziele:

- **Android** (`app/`): der Client für jeden Tag — Alben, Zeitstrahl,
  Erinnerungen, Kamera-Backup direkt in den Durchlauf des Servers,
  Benachrichtigungen ohne Google-Dienste, Fotos teilen und aus anderen Apps
  entgegennehmen.
- **Desktop** (`desktop/`): Linux `.deb` und Windows `.exe`/`.msi`, dieselben
  Bildschirme, dazu Ordner dieses Rechners im Zeitstrahl.
- **Shared** (`shared/`): alles, was beide gemeinsam haben — Bildschirme,
  Logik, der API-Client.

Die Apps sprechen nur mit deinem Server. Kein Konto bei irgendwem, keine
Telemetrie, kein Push-Dienst; die Serveradresse wird einmal eingegeben oder
als QR-Code von der Seite „App verbinden“ des Servers gescannt.

## Ausprobieren

Die Apps brauchen einen Server. Einen öffentlichen Demo-Server gibt es: in der
App die Adresse `https://mpd.bgg-home.de` eingeben, Konto `mpd-demo`, Passwort
`mpd-demo` (nur lesend, sechs Beispielalben). Im Browser zeigt
[mpd.bgg-home.de/demo-login](https://mpd.bgg-home.de/demo-login) dieselben Alben ohne Passwort.

## Installieren

Fertige Pakete: [bgg-home.de/mpd.apk](https://bgg-home.de/mpd.apk)
(Android), [bgg-home.de/mpd.deb](https://bgg-home.de/mpd.deb) (Linux),
[bgg-home.de/mpd-setup.exe](https://bgg-home.de/mpd-setup.exe) (Windows).
Die Apps brauchen einen MPD-Server ab Version 3.0.

## Bauen

JDK 21 und das Android-SDK (für das Android-Ziel).

```bash
./gradlew :shared:testDebugUnitTest :shared:desktopTest   # Unit-Tests
./gradlew assembleDebug                                    # Android-APK
./gradlew :desktop:run                                     # Desktop, direkt aus dem Quelltext
./gradlew :desktop:packageDeb                              # Linux-Paket
```

Windows-Installer entstehen unter Windows mit `jpackage`, siehe
[`docs/BUILD-WINDOWS.md`](docs/BUILD-WINDOWS.md).

## Dokumentation

- [`docs/PDX_SPEC_v1_5.pdf`](docs/PDX_SPEC_v1_5.pdf) — das Albumformat, das
  die Apps lesen und schreiben
- API und Architektur stehen im Server-Repository

## Lizenz

My Photo Diary Apps stehen unter der **GNU Affero General Public License
v3.0** ([LICENSE](LICENSE)). © 2026 Thomas Bugge.

Die mitgelieferten Schriften stehen unter der SIL Open Font License 1.1
(`docs/fonts-lizenzen/`).
