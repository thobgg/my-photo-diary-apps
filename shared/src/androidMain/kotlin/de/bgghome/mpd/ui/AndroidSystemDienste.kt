package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import de.bgghome.mpd.state.Texte
import java.io.File
import de.bgghome.mpd.meldung

/** Android-Fassung der [SystemDienste]: ClipboardManager, SEND-Intent, PackageManager. */
class AndroidSystemDienste(private val context: Context) : SystemDienste {

    /**
     * AB ANDROID 13 KEINE EIGENE MELDUNG (16.09.2026, Umstellung Toast →
     * Snackbar). Das System zeigt dort selbst eine Vorschau des Kopierten
     * unten am Rand; Google raet ausdruecklich, dann nicht noch einmal zu
     * bestaetigen, sonst erscheint dieselbe Nachricht doppelt. Darunter gibt
     * es die Systemvorschau nicht — dann meldet sich die App als Snackbar.
     */
    override fun kopiere(label: String, wert: String, meldung: (String) -> Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, wert))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            meldung(Texte.t(Res.string.common_copied_x, label))
        }
    }

    /** System-Teilen-Blatt — am Handy der Weg zu WhatsApp, Signal, Mail. */
    override fun teileText(text: String, meldung: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, Texte.t(Res.string.share_link_chooser_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override val kannFotosTeilen = true
    override fun teileAuswahl(vm: de.bgghome.mpd.AppViewModel) {
        (vm as? de.bgghome.mpd.AndroidAppViewModel)?.shareSelectionToApp(context)
    }

    override fun teileFoto(vm: de.bgghome.mpd.AppViewModel, photo: de.bgghome.mpd.api.Photo) {
        (vm as? de.bgghome.mpd.AndroidAppViewModel)?.sharePhotoToApp(context, photo)
    }

    override fun bearbeiteLokal(vm: de.bgghome.mpd.AppViewModel, photo: de.bgghome.mpd.api.Photo, params: de.bgghome.mpd.api.PhotoEditParams, onError: (String) -> Unit) {
        val avm = vm as? de.bgghome.mpd.AndroidAppViewModel ?: return onError("Lokales Bearbeiten gibt es nur auf dem Geraet.")
        avm.editLocalPhoto(context, photo, params, onError)
    }

    override val geraeteName: String get() = android.os.Build.MODEL ?: "Handy"

    /**
     * Datei in den Ordner „Downloads" legen. Bewusst nicht ueber den
     * DownloadManager: der kennt weder unseren Zertifikatsspeicher noch den
     * Bearer-Token. Ab Android 10 ueber den MediaStore (keine Berechtigung
     * noetig), darunter in den app-eigenen Downloads-Ordner.
     */
    override suspend fun speichereDownload(vm: de.bgghome.mpd.AppViewModel, url: String, filename: String, mime: String) {
    // Sofort sagen, dass etwas passiert (09.09.2026: „man tappt es,
    // nichts passiert, nachher ist aber doch was im Download-Ordner"): der
    // Export ueber 555.000 Punkte dauert, und die Pille gibt selbst keine
    // Rueckmeldung. Am Ende „Oeffnen", damit man die Datei nicht suchen muss.
    vm.meldung(Texte.t(Res.string.trails_dl_started, filename), kurz = true)
    try {
        val target: String
        var opened: android.net.Uri? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException(Texte.t(Res.string.trails_dl_no_downloads))
            vm.api.downloadStream(
                url,
                resolver.openOutputStream(uri) ?: throw java.io.IOException(Texte.t(Res.string.trails_dl_no_write)),
            )
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            target = "Downloads/$filename"
            opened = uri
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw java.io.IOException(Texte.t(Res.string.trails_dl_no_location))
            val file = File(dir, filename)
            vm.api.downloadStream(url, file.outputStream())
            target = file.absolutePath
        }
        val uri = opened
        if (uri != null) {
            vm.meldung(
                Texte.t(Res.string.trails_dl_saved, target),
                aktion = Texte.t(Res.string.trails_dl_open),
            ) {
                try {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            ),
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    vm.meldung(Texte.t(Res.string.trails_dl_no_app, mime), kurz = true)
                }
            }
        } else {
            vm.meldung(Texte.t(Res.string.trails_dl_saved, target))
        }
    } catch (e: Exception) {
        vm.meldung(trailsErrorText(e))
    }
    
    }

    override fun toggleSprache() = de.bgghome.mpd.data.AppLanguage.toggle(context)

    override val kannGpxOeffnen = true

    /**
     * GPX in den Share-Cache (derselbe eng geschnittene FileProvider-Ordner
     * wie beim Weitergeben von Fotos, file_paths.xml), dann ACTION_VIEW mit
     * application/gpx+xml in der Systemauswahl. OsmAnd importiert die Spur
     * und zeigt sie; Komoot und Locus ebenso. Nicht der DownloadManager und
     * kein nackter Link: der Server will die Sitzung sehen.
     */
    override suspend fun oeffneGpx(vm: de.bgghome.mpd.AppViewModel, url: String, filename: String) {
        try {
            val name = filename.substringAfterLast('/').ifBlank { "tour.gpx" }
            val file = File(shareDir(context), name)
            file.outputStream().use { vm.api.downloadStream(url, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/gpx+xml")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(intent, Texte.t(Res.string.gpx_open_chooser))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: android.content.ActivityNotFoundException) {
            vm.meldung(Texte.t(Res.string.gpx_open_no_app), kurz = true)
        } catch (e: Exception) {
            vm.meldung(Texte.t(Res.string.gpx_open_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** „1.19.0 (Build 67)": Versionsnummer des gemeinsamen Codes plus versionCode. */
    override val versionName: String by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName} (Build $code)"
        }.getOrNull() ?: "?"
    }
}
