// Linux/Windows-Huelle von My Photo Diary: ein Fenster um den geteilten
// Kern (:shared), native Pakete via jpackage. Gebaut wird je System, auf
// dem es laeuft: deb hier, msi/exe auf dem Windows-Laptop (WiX noetig).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // BackHandler von Compose Multiplatform ist noch experimentell (wie in :shared).
        freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "de.bgghome.mpd.desktop.MainKt"
        // Build-Zaehler fuer den Ueber-Dialog (DesktopSystemDienste.versionName).
        jvmArgs += "-Dmpd.desktopBuild=${property("mpd.desktopBuild")}"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "My Photo Diary"
            // Vom Plugin vorgeschlagen (suggestRuntimeModules); java.prefs
            // fuer DesktopAblage, jdk.unsupported fuer Skiko/Coroutines.
            modules("java.instrument", "java.prefs", "jdk.unsupported")
            // Dieselbe Nummer wie die APK (gradle.properties). Windows
            // Installer verlangt rein numerisch x.y.z - kein "-alpha".
            packageVersion = property("mpd.versionName") as String
            description = "My Photo Diary"
            vendor = "bgg-home.de"

            linux {
                menuGroup = "Graphics"
                packageName = "myphotodiary"
                // Desktop-Build-Zaehler als Debian-Release: 1.19.0-1, 1.19.0-2 …
                appRelease = property("mpd.desktopBuild") as String
                // Bildmarke: Squircle-Fassung der Marke (mpd/frontend/img/
                // brand/src/icon_legacy.svg, 512 px), wie im Web-Seitenkopf.
                // Nicht den Launcher-Vordergrund nehmen - der traegt die
                // Android-Sicherheitszone und war am Desktop viel zu klein
                // (24.09.2026). Ohne Angabe zeigt das Anwendungsmenue
                // das Java-Standardsymbol.
                iconFile.set(project.file("icons/mpd.png"))
            }
            windows {
                // Entscheidung vom 22.09.2026: EXE als Download (Setup-
                // Assistent, Ort waehlbar, Desktop-Verknuepfung), MSI dazu.
                // Pro Benutzer, damit Windows keine Adminrechte verlangt.
                perUserInstall = true
                menuGroup = "My Photo Diary"
                shortcut = true
                dirChooser = true
                // NIE aendern: nur mit gleicher Kennung ersetzt eine neue
                // Version die alte, statt zweimal im Startmenue zu stehen.
                upgradeUuid = "7c2f9a3e-5b64-4d1a-9e0c-2a8f4b6d1e37"
                // Dieselbe Squircle-Fassung, 16-256 px (siehe linux).
                iconFile.set(project.file("icons/mpd.ico"))
            }
        }
    }
}
