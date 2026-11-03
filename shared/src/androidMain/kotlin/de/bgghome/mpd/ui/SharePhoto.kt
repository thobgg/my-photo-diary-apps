package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.bgghome.mpd.AppViewModel
import java.io.File

/*
 * Ein Foto an eine andere App weitergeben (06.09.2026).
 *
 * Bis hierher konnte MPD nur LINKS teilen (shareText, text/plain): der
 * Empfaenger bekam eine Adresse und brauchte Zugang. Ein Bild an einen
 * Chat zu schicken ging nicht — das gehoert zu dem, was jede Galerie kann
 * (Zielergaenzung, CLAUDE.md).
 *
 * Datenschutz ist hier Bauvorschrift, nicht Beiwerk. Rueckmeldung: „es bleibt
 * dabei — where your memories stay yours, kein Google Datenabgriff oder
 * aehnliches." Deshalb:
 *
 *  - Es geht ausschliesslich ins Android-Share-Sheet. Die empfangende App
 *    waehlt der Nutzer, wir kennen sie nicht und fragen nicht nach.
 *  - Freigegeben wird eine einzelne Datei aus cache/share, mit temporaerem
 *    Leserecht; der Provider ist nicht exportiert (res/xml/file_paths.xml).
 *  - Wir reichern nichts an: kein Wasserzeichen, kein Zusatztext, keine
 *    Herkunftsangabe. Was rausgeht, ist die Datei — nicht mehr.
 *  - Der Share-Ordner wird vor jedem Weitergeben geleert, damit dort nicht
 *    unbemerkt eine Sammlung entsteht.
 */

/** Grenze fuer den Share-Cache: aufgeraeumt wird vor jedem Weitergeben. */
internal fun shareDir(context: Context): File =
    File(context.cacheDir, "share").apply {
        if (!exists()) mkdirs() else listFiles()?.forEach { it.delete() }
    }

/**
 * Gibt das Foto als Bild weiter. Geraete-Aufnahmen tragen ihre
 * content://-Adresse schon, die geht direkt ins Share-Sheet; Server-Fotos
 * werden vorher als Original in den Share-Cache geladen.
 *
 * @return null bei Erfolg, sonst eine Meldung fuer den Nutzer.
 */
suspend fun sharePhotoFile(context: Context, vm: AppViewModel, photo: Photo): String? {
    val uri: Uri = if (photo.isLocal) {
        Uri.parse(photo.originalUrl)
    } else {
        val file = runCatching {
            // Mit Endung: sonst meldet FileProvider.getType() der
            // empfangenden App application/octet-stream (siehe dort).
            val endung = photo.file.substringAfterLast('.', "")
            vm.api.downloadToCache(
                shareDir(context), photo.originalUrl,
                if (endung.isNotBlank()) ".${endung.lowercase()}" else "",
            )
        }.getOrElse {
            return Texte.t(Res.string.share_err_original_x, it.message)
        } ?: return Texte.t(Res.string.share_err_original)
        runCatching {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
        }.getOrElse {
            return Texte.t(Res.string.share_err_not_possible_x, it.message)
        }
    }

    val mime = mimeFuerEndung(photo.file.substringAfterLast('.', "").lowercase())
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        // Nur diese eine Datei, nur fuer diesen Vorgang.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, Texte.t(Res.string.share_chooser_title)))
        null
    }.getOrElse {
        Texte.t(Res.string.share_err_no_app)
    }
}

/**
 * Mehrere Aufnahmen auf einmal weitergeben (08.09.2026:
 * Galerie-Bestandsaufnahme, „1-2: ok"). Der Urlaubsabend: fuenf Bilder in
 * den Familienchat — bisher ging nur eines.
 *
 * Dieselben Regeln wie [sharePhotoFile]: Geraete-Aufnahmen gehen mit
 * ihrer content://-Adresse direkt, Server-Fotos werden vorher als
 * Original in den Share-Cache geladen (nacheinander; [onProgress] meldet
 * „k von n" fuer die Server-Haelfte), dann EIN Share-Sheet mit
 * ACTION_SEND_MULTIPLE. Nichts wird angereichert.
 *
 * @return null bei Erfolg, sonst eine Meldung fuer den Nutzer.
 */
suspend fun sharePhotosFiles(
    context: Context,
    vm: AppViewModel,
    items: List<de.bgghome.mpd.api.TimelineItem>,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): String? {
    if (items.isEmpty()) return null
    // Einmal leeren, dann alle Originale hinein — nicht je Datei, sonst
    // wuerde jede Datei die vorige loeschen.
    val dir = shareDir(context)
    val uris = ArrayList<Uri>(items.size)
    var allImages = true
    val serverTotal = items.count { it.localUri == null }
    var serverDone = 0
    for (item in items) {
        val ext = item.file.substringAfterLast('.', "").lowercase()
        if (!mimeFuerEndung(ext).startsWith("image/")) allImages = false
        val uri: Uri = if (item.localUri != null) {
            Uri.parse(item.localUri)
        } else {
            serverDone++
            onProgress(serverDone, serverTotal)
            val path = item.original
                ?: de.bgghome.mpd.api.MpdApi.originalPath(item.space, item.album, item.file)
            val file = runCatching {
                vm.api.downloadToCache(
                    dir, vm.baseUrl + path,
                    if (ext.isNotBlank()) ".$ext" else "",
                )
            }.getOrElse {
                return Texte.t(Res.string.share_err_original_x, it.message)
            } ?: return Texte.t(Res.string.share_err_original)
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrElse {
                return Texte.t(Res.string.share_err_not_possible_x, it.message)
            }
        }
        uris += uri
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        // Nur Bilder → image/*, sonst gemischt: die empfangende App soll
        // ehrlich wissen, was kommt.
        type = if (allImages) "image/*" else "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(
            Intent.createChooser(intent, Texte.t(Res.string.share_multi_chooser_title)),
        )
        null
    }.getOrElse {
        Texte.t(Res.string.share_err_no_app)
    }
}

/**
 * MIME-Typ fuer das Android-Share-Sheet, aus der Dateiendung.
 *
 * Warum die App das selbst weiss und nicht den Server fragt: Beim
 * Weitergeben einer Geraete-Aufnahme ist gar kein Server im Spiel. Die
 * Tabelle ist deshalb kein Nachbau fremder Logik, sondern eine
 * Android-Angelegenheit — sie deckt aber absichtlich dieselben Endungen
 * ab wie `PHOTO_MIME`/`VIDEO_MIME` in `core/media_types.py`, damit
 * dieselbe Datei auf beiden Seiten denselben Namen traegt.
 *
 * KORRIGIERT AM 07.09.2026: Vorher kannte die Tabelle nur mp4, m4v und
 * mov — jedes andere Video fiel in den Rueckfall „image/jpeg". Ein per
 * MPD weitergegebenes .mkv, .webm, .avi oder .3gp wurde der
 * empfangenden App also als Bild angekuendigt. Das ist derselbe Fehler,
 * den der Server am selben Tag auf seiner Seite abgelegt hat: einen Typ
 * raten, statt ihn zu sagen.
 *
 * Der Rueckfall fragt jetzt erst Androids eigene Tabelle und gibt erst
 * dann auf. „image/jpeg" als letzte Antwort waere fuer ein unbekanntes
 * Video die schlechteste von allen.
 */
internal fun mimeFuerEndung(ext: String): String =
    mimeAusTabelle(ext)
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"

/**
 * Der nachschlagbare Teil — ohne Android, damit ihn ein Unit-Test
 * vollstaendig durchgehen kann. Genau deshalb getrennt: Androids
 * MimeTypeMap gibt es auf der JVM nicht, und eine Tabelle, die man nur
 * am Geraet pruefen kann, wird nicht geprueft.
 *
 * null = MPD kennt die Endung nicht. Dann fragt [mimeFuerEndung]
 * Android und gibt danach ehrlich auf.
 */
internal fun mimeAusTabelle(ext: String): String? = when (ext) {
    "jpg", "jpeg", "jfif" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "tif", "tiff" -> "image/tiff"
    "bmp" -> "image/bmp"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "3gp" -> "video/3gpp"
    "mts", "m2ts" -> "video/mp2t"
    "wmv" -> "video/x-ms-wmv"
    "mpg", "mpeg" -> "video/mpeg"
    else -> null
}
