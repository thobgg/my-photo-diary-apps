// Geteilter Kern von My Photo Diary: API-Client, Zustand, Oberflaeche.
// Wird von :app (Android) und :desktop (Linux/Windows) benutzt, damit der
// Code nur EINMAL existiert — ein eigener Ordner je Plattform war erwogen
// und verworfen (22.09.2026): zwei Kopien laufen auseinander.
//
// Stand der Aufteilung: Alles liegt zunaechst in androidMain und verhaelt
// sich wie vor dem Umbau. Was ohne Android auskommt, wandert Stueck fuer
// Stueck nach commonMain; Plattform-Dinge bekommen expect/actual.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // ui-backhandler ist in Compose Multiplatform noch experimentell.
        freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // api statt implementation: die Huellen (:app, :desktop) bauen
            // ihre Oberflaeche mit denselben Compose-Artefakten.
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)
            api(libs.kotlinx.serialization.json)
            // JVM-Bibliotheken duerfen hier stehen: beide Ziele (Android,
            // Desktop) sind JVM, commonMain darf JDK-APIs benutzen.
            api(libs.okhttp)
            api(libs.zxing.core)
            // ViewModel + viewModelScope + viewModel() fuer beide Ziele
            api(libs.jetbrains.lifecycle.viewmodel)
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            // Bildladen mit Cache, Coil 3 (multiplattform); Netz ueber den
            // OkHttp-Client des API-Clients, damit Cookie und Zertifikat gelten
            api(libs.coil.compose)
            api(libs.coil.network.okhttp)
            // BackHandler fuer beide Plattformen (Android: Systemgeste,
            // Desktop: Escape) - ersetzt androidx.activity.compose.BackHandler
            api(libs.compose.ui.backhandler)
            // XmlPullParser-API fuer den GPX-Parser: Android bringt sie mit
            // (android.jar), der Desktop bekommt kxml2 (desktopMain).
            compileOnly(libs.kxml2)
        }
        androidMain.dependencies {
            api(libs.androidx.core.ktx)
            api(libs.androidx.lifecycle.runtime)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.androidx.activity.compose)
            // Kachelweises Dekodieren der Originale fuer verpixelungsfreies Zoomen
            api(libs.subsampling.scale.image.view)
            // Rasterkarten fuer map- und tour-Elemente
            api(libs.osmdroid)
            // Video-/Audiowiedergabe mit Range-Requests und Bearer-Header
            api(libs.androidx.media3.exoplayer)
            api(libs.androidx.media3.ui)
            // Hintergrund-Uploads (Outbox + Geraeteordner-Backup)
            api(libs.androidx.work)
            // EXIF aus Geraetedateien
            api(libs.androidx.exifinterface)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Dispatchers.Main auf der JVM (Swing/AWT-Thread). Android
                // bringt ihn mit, der Desktop nicht - ohne ihn stirbt der
                // erste viewModelScope.launch mit "Main dispatcher is
                // missing" (Anmelden-Knopf, 22.09.2026).
                api(libs.kotlinx.coroutines.swing)
                implementation(libs.kxml2)
                // PDF-Seiten rendern (Android: PdfRenderer im System)
                implementation(libs.pdfbox)
                // EXIF der Fotos in den PC-Ordnern (Aufnahmezeit, Drehung, Panel);
                // Android hat dafuer androidx.exifinterface.
                implementation(libs.metadata.extractor)
            }
        }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            // Echte XmlPullParser-Implementierung fuer die JVM-Tests (GPX-Parser).
            implementation(libs.kxml2)
        }
    }
}

compose.resources {
    // Ohne Vorgabe leitet das Plugin das Paket aus dem Projektnamen ab —
    // "My Photo Diary" wird zu "my photo diary.shared…", und D8 lehnt
    // Leerzeichen im Klassennamen ab.
    packageOfResClass = "de.bgghome.mpd.shared.res"
    // Die Huellen (MainActivity, spaeter der Desktop-Start) greifen auf
    // dieselben Texte zu; ohne das ist Res modul-intern.
    publicResClass = true
}

android {
    // Eigener Namespace, sonst kollidiert die R-Klasse mit der der App.
    namespace = "de.bgghome.mpd.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
