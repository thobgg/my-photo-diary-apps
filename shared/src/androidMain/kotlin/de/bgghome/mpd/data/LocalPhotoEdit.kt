package de.bgghome.mpd.data

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import de.bgghome.mpd.api.PhotoEditParams
import java.io.ByteArrayOutputStream

/*
 * Foto-Editor fuer Aufnahmen, die (noch) nur auf dem Geraet liegen —
 * 06.09.2026.
 *
 * Bis hierher war der Editor rein serverseitig (POST /api/photo/…/edit).
 * Bei einer Geraetedatei war der Stift deshalb ausgeblendet. Vorgabe:
 * „aber Google Fotos kann es doch auch — genau so realisieren." Zu Recht:
 * wer die Galerie ersetzen will, kann Bearbeiten nicht davon abhaengig
 * machen, ob eine Datei schon hochgeladen ist.
 *
 * Dieselben Parameter wie der Server (PhotoEditParams), dieselbe
 * Reihenfolge der Schritte (drehen → spiegeln → geraderichten →
 * zuschneiden), damit ein Bild lokal und auf der NAS gleich aussieht.
 * Tonwerte bleiben dem Server vorbehalten — siehe unten.
 *
 * Was hier NICHT passiert: eine Sicherungskopie. Der Server legt beim
 * Bearbeiten eine .bak an; auf dem Geraet gibt es die nicht. Das gehoert
 * in die Rueckfrage, nicht in den Code.
 */
object LocalPhotoEdit {

    /** Ergebnis eines lokalen Bearbeitungslaufs. */
    sealed interface Result {
        data class Ok(val bytes: ByteArray) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Wendet die Parameter auf das Bild hinter [uri] an und gibt die
     * fertigen JPEG-Bytes zurueck. Geschrieben wird hier nichts — das
     * braucht die Zustimmung des Nutzers (MediaStore.createWriteRequest)
     * und gehoert deshalb in die Oberflaeche.
     */
    fun apply(context: Context, uri: Uri, p: PhotoEditParams, quality: Int = 95): Result {
        val src = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull() ?: return Result.Failed(Texte.t(Res.string.edit_err_unreadable))

        return runCatching {
            var bmp = src

            // 1. Die EXIF-Ausrichtung des Originals einrechnen. Sonst dreht
            //    man ein Bild, das der Betrachter ohnehin schon gedreht
            //    anzeigt — und das Ergebnis steht doppelt schief.
            val orientation = readOrientation(context, uri)
            if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                bmp = applyMatrix(bmp, matrixForOrientation(orientation))
            }

            // 2. Drehen und Spiegeln, 3. Geraderichten — beides macht
            //    cropPreviewBitmap in der Vorschau schon; hier dieselbe
            //    Rechnung auf dem Original, damit Vorschau und Ergebnis
            //    uebereinstimmen.
            if (p.rotation != 0 || p.flip || p.flipV) {
                bmp = applyMatrix(
                    bmp,
                    Matrix().apply {
                        postRotate(p.rotation.toFloat())
                        postScale(if (p.flip) -1f else 1f, if (p.flipV) -1f else 1f)
                    },
                )
            }
            if (kotlin.math.abs(p.straighten) > 0.01f) {
                bmp = straightenAndTrim(bmp, p.straighten)
            }

            // 4. Zuschnitt: CropBox ist relativ (0..1) auf das bereits
            //    gedrehte Bild — genauso liest es der Server.
            p.crop?.let { c ->
                val x = (c.x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val y = (c.y * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                val w = (c.w * bmp.width).toInt().coerceIn(1, bmp.width - x)
                val h = (c.h * bmp.height).toInt().coerceIn(1, bmp.height - y)
                bmp = Bitmap.createBitmap(bmp, x, y, w, h)
            }

            // 5. Tonwerte — seit 07.09.2026 auch lokal. Die alte
            //    Begruendung („Pillow und ColorMatrix rechnen anders")
            //    galt nur fuer Androids fertige Bausteine; Pillows
            //    Rechnung steht offen da und ist in Tonwerte.kt Zeile fuer
            //    Zeile nachgebaut. Reihenfolge wie der Server: erst
            //    Helligkeit, dann Kontrast, dann Saettigung — und der
            //    Kontrast dreht sich um den Mittelwert des Bildes NACH
            //    der Helligkeit, nicht um mittleres Grau.
            if (p.brightness != 0 || p.contrast != 0 || p.saturation != 0) {
                bmp = tonwerte(bmp, p.brightness, p.contrast, p.saturation)
            }


            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            // EXIF gleich hier anbringen, nicht erst an der ueberschriebenen
            // Datei: danach waere das Original weg und die Tags mit ihm.
            Result.Ok(withExif(context, uri, out.toByteArray()))
        }.getOrElse { Result.Failed(it.message ?: Texte.t(Res.string.edit_err_failed)) }
    }

    /**
     * EXIF des Originals in die neuen Bytes uebertragen. Ohne das
     * verlieren bearbeitete Fotos Aufnahmezeit und Ort — bei einem
     * Fotoarchiv der schwerere Verlust als ein schiefer Horizont.
     *
     * Die Ausrichtung wird dabei auf „normal" gesetzt: sie steckt nach
     * dem Bearbeiten in den Pixeln, nicht mehr im Tag. Bliebe der alte
     * Wert stehen, wuerde jeder Betrachter das Bild ein zweites Mal
     * drehen.
     *
     * Der Umweg ueber eine Datei ist noetig, weil ExifInterface nur auf
     * Dateien und Deskriptoren schreibt, nicht auf ein Byte-Feld.
     */
    private fun withExif(context: Context, from: Uri, jpeg: ByteArray): ByteArray =
        runCatching {
            val tmp = java.io.File.createTempFile("edit", ".jpg", context.cacheDir)
            try {
                tmp.writeBytes(jpeg)
                val srcTags = context.contentResolver.openInputStream(from)?.use {
                    ExifInterface(it)
                } ?: return jpeg
                val dst = ExifInterface(tmp.absolutePath)
                for (tag in CARRIED_TAGS) {
                    srcTags.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                }
                dst.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                dst.saveAttributes()
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }.getOrDefault(jpeg)

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Sichtbare Ausrichtung in eine Matrix uebersetzen. */
    internal fun matrixForOrientation(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        }
    }

    private fun applyMatrix(b: Bitmap, m: Matrix): Bitmap =
        if (m.isIdentity) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)

    /** Geraderichten und auf das groesste enthaltene Rechteck beschneiden. */
    private fun straightenAndTrim(b: Bitmap, degrees: Float): Bitmap {
        val rotated = applyMatrix(b, Matrix().apply { postRotate(degrees) })
        val (w, h) = largestInscribed(
            b.width.toFloat(), b.height.toFloat(), Math.toRadians(degrees.toDouble()),
        )
        val x = ((rotated.width - w) / 2).toInt().coerceAtLeast(0)
        val y = ((rotated.height - h) / 2).toInt().coerceAtLeast(0)
        val cw = w.toInt().coerceIn(1, rotated.width - x)
        val ch = h.toInt().coerceIn(1, rotated.height - y)
        return Bitmap.createBitmap(rotated, x, y, cw, ch)
    }

    /**
     * Groesstes achsenparalleles Rechteck, das nach einer Drehung um
     * [rad] noch vollstaendig im Bild liegt. Dieselbe Formel wie in
     * PhotoCrop.kt — die Vorschau muss zeigen, was gespeichert wird.
     */
    internal fun largestInscribed(w: Float, h: Float, rad: Double): Pair<Float, Float> {
        val a = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        if (w <= 0f || h <= 0f) return 0f to 0f
        val shorter = minOf(w, h)
        val longer = maxOf(w, h)
        return if (shorter <= 2f * a * c * longer || kotlin.math.abs(a - c) < 1e-6f) {
            val x = 0.5f * shorter
            val (wr, hr) = if (w >= h) (x / a) to (x / c) else (x / c) to (x / a)
            wr to hr
        } else {
            val cos2 = c * c - a * a
            ((w * c - h * a) / cos2) to ((h * c - w * a) / cos2)
        }
    }

    /**
     * Die drei Tonwert-Schritte auf das ganze Bild.
     *
     * Zwei Durchgaenge, und der erste ist der Grund fuer diese Funktion:
     * Pillows Kontrast dreht sich um das mittlere Grau des Bildes — und
     * zwar des Bildes, WIE ES NACH DER HELLIGKEIT AUSSIEHT. Dieser
     * Mittelwert laesst sich nicht ausrechnen, ohne einmal ueber alle
     * Pixel zu gehen (die Klemmung bei 255 macht die Rechnung
     * nichtlinear). Also: erst zaehlen, dann anwenden.
     *
     * Zeilenweise in Baendern, damit ein 12-Megapixel-Bild nicht als
     * 48-MB-IntArray im Speicher liegt — dieselbe Vorsicht, aus der auf
     * dem Server Image.draft() stammt.
     */
    private fun tonwerte(src: Bitmap, helligkeit: Int, kontrast: Int, saettigung: Int): Bitmap {
        val w = src.width
        val h = src.height
        val bandHoehe = maxOf(1, minOf(h, 262_144 / maxOf(1, w)))
        val puffer = IntArray(w * bandHoehe)

        // Durchgang 1: mittleres Grau nach der Helligkeit. Nur noetig,
        // wenn der Kontrast ueberhaupt bewegt wurde.
        var mittel = 128
        if (kontrast != 0) {
            var summe = 0L
            var y = 0
            while (y < h) {
                val zeilen = minOf(bandHoehe, h - y)
                src.getPixels(puffer, 0, w, 0, y, w, zeilen)
                for (i in 0 until w * zeilen) {
                    summe += Tonwerte.grauNachHelligkeit(puffer[i], helligkeit)
                }
                y += zeilen
            }
            mittel = Tonwerte.mittelwert(summe, w.toLong() * h)
        }

        // Durchgang 2: anwenden. Ein neues Bitmap, weil ein unveraenderliches
        // Quellbild (z. B. direkt aus dem Decoder) nicht beschreibbar ist.
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        var y = 0
        while (y < h) {
            val zeilen = minOf(bandHoehe, h - y)
            out.getPixels(puffer, 0, w, 0, y, w, zeilen)
            for (i in 0 until w * zeilen) {
                puffer[i] = Tonwerte.pixel(puffer[i], helligkeit, kontrast, saettigung, mittel)
            }
            out.setPixels(puffer, 0, w, 0, y, w, zeilen)
            y += zeilen
        }
        return out
    }

    /** Was beim Bearbeiten erhalten bleibt — alles, was die Aufnahme beschreibt. */
    private val CARRIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
    )
}
