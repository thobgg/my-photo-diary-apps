import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Gleiches Muster wie MyPhotoDiary/TexSlate: Signierung aus
// keystore.properties, die nicht eingecheckt wird. Fehlt sie, bleibt der
// Release unsigniert, statt den Build zu brechen.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    // Eigener Paketname, damit die App neben dem bestehenden Wrapper
    // (de.mpd.myphotodiary) installiert werden kann. Erst wenn sie ihn
    // ersetzt, waere ein Wechsel auf dessen Paketnamen sinnvoll.
    namespace = "de.bgghome.mpd"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.bgghome.mpd"
        minSdk = 26
        targetSdk = 36
        // Zentral in gradle.properties, weil der Desktop-Client dieselbe Nummer traegt.
        versionCode = (property("mpd.versionCode") as String).toInt()
        versionName = property("mpd.versionName") as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                // Solange keystore.properties fehlt: mit dem Debug-Schluessel
                // signieren, damit Release-Builds fuer Perf-Tests installierbar
                // sind (Compose ist im Debug-Build spuerbar traeger). Vor einer
                // echten Verteilung keystore.properties anlegen — dann greift
                // automatisch der richtige Schluessel.
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
    }
    buildFeatures { compose = true }
}

dependencies {
    // Der gesamte Code liegt im geteilten Modul; die App-Huelle hat nur
    // MainActivity, Manifest, Launcher-Symbol und Signatur. Alle
    // Bibliotheken kommen als api-Abhaengigkeiten aus :shared.
    implementation(project(":shared"))
}
