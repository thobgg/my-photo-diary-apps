# Windows-Installer bauen (exe/msi)

**Kurzweg ohne Abtippen:** Die Skripte `windows-einrichten.cmd` und `windows-bauen.cmd`
liegen seit 23.09.2026 unter `tools/windows/` (nicht im Git, kommt über den NAS-Abgleich auf den
Laptop), zusammen mit `mpd-app.bundle` und einer `LIESMICH.md`. Auf dem Laptop im abgeglichenen
Ordner `AI-Projects\myphotodiary\mpd-apps\tools\windows` `windows-bauen.cmd` doppelklicken; gebaut wird in
`C:\MPD\mpd-app`. Der Rest dieser Seite beschreibt, was die Skripte tun, für den Fall, dass
etwas hakt.

Gebaut wird auf einem Windows-Rechner — jpackage erzeugt nur Pakete für das
System, auf dem es läuft. Einmalig einrichten, danach sind es zwei Befehle.

## Einmalig einrichten

1. **JDK 21** (Temurin): https://adoptium.net → „Temurin 21 (LTS)", MSI,
   beim Installieren „Set JAVA_HOME" anhaken.
2. **Git für Windows**: https://git-scm.com/download/win (Standardeinstellungen).
3. **WiX Toolset 3.14** (für msi und exe): https://github.com/wixtoolset/wix3/releases
   → `wix314.exe`. Nach dem Installieren `C:\Program Files (x86)\WiX Toolset v3.14\bin`
   zur PATH-Variable hinzufügen (Systemsteuerung → System → Erweiterte
   Systemeinstellungen → Umgebungsvariablen).
4. **Android-SDK-Kommandozeilenwerkzeuge** (das Projekt enthält das
   Android-Modul, Gradle braucht das SDK auch für den Desktop-Build):
   https://developer.android.com/studio#command-line-tools-only → ZIP nach
   `C:\Android\cmdline-tools\latest\` entpacken (so, dass darin `bin\`
   liegt). Dann in einer Eingabeaufforderung:
   ```
   setx ANDROID_HOME C:\Android
   C:\Android\cmdline-tools\latest\bin\sdkmanager --licenses
   ```
   (alle Lizenzen mit `y` bestätigen). Plattform und Build-Tools lädt Gradle
   beim ersten Lauf selbst nach.
5. **Quellcode holen** — das Repo hat keinen Remote. Entweder liegt der
   Ordner `mpd-app` über Synology Drive schon auf dem Laptop, oder aus dem
   Git-Bundle auf der NAS (`AI-Projects\mpd-app.bundle`):
   ```
   git clone C:\Pfad\zu\mpd-app.bundle C:\Projekte\mpd-app
   ```
   Nie mitnehmen: `keystore.properties`, `mpd-release.jks` (der Desktop
   braucht sie nicht).

## Bauen

Eingabeaufforderung im Projektordner:

```
gradlew.bat :desktop:packageExe :desktop:packageMsi
```

Der erste Lauf lädt Gradle und die Abhängigkeiten (rund 1 GB, dauert).
Ergebnis:

```
desktop\build\compose\binaries\main\exe\My Photo Diary-1.19.0.exe
desktop\build\compose\binaries\main\msi\My Photo Diary-1.19.0.msi
```

Die EXE ist der Download für die Landing-Page (Setup-Assistent, Ort
wählbar, Desktop-Verknüpfung), die MSI läuft still durch. Beide
installieren pro Benutzer, ohne Adminrechte. Beim ersten Start meldet
SmartScreen „unbekannter Herausgeber" → „Weitere Informationen" →
„Trotzdem ausführen".

## Vor jedem Release

In `gradle.properties`: `mpd.desktopBuild` um 1 erhöhen (und
`mpd.versionName`, wenn sich der gemeinsame Code geändert hat — dieselbe
Nummer wie die APK). `upgradeUuid` in `desktop/build.gradle.kts` **nie**
ändern, sonst steht die App zweimal im Startmenü.

## Wenn es hakt

- „SDK location not found": ANDROID_HOME nicht gesetzt oder neue
  Eingabeaufforderung nötig (setx wirkt erst danach).
- „WiX tools not found": PATH-Eintrag fehlt, oder WiX 4/5 statt 3.14.
- „Unsupported class file major version": falsches Java aktiv,
  `java -version` muss 21 zeigen.
