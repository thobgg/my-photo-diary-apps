package de.bgghome.mpd.data

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import de.bgghome.mpd.api.ExifCamera
import de.bgghome.mpd.api.ExifData
import de.bgghome.mpd.api.ExifGps
import de.bgghome.mpd.api.ExifSettings

/*
 * EXIF einer Geraete-Aufnahme (06.09.2026).
 *
 * Seit dem gemischten Strom stehen Fotos in der Timeline, die der Server
 * noch nicht kennt — das Info-Blatt fragte trotzdem /api/exif und blieb
 * leer („Eben gemachte Fotos tauchen jetzt in Timeline auf, aber
 * ohne exif Infos"). Die Daten stecken in der Datei selbst.
 *
 * Ergebnis ist dieselbe ExifData wie vom Server, damit das Panel nicht
 * zwei Faelle kennen muss — nur die Quelle unterscheidet sich.
 */
object LocalExif {

    fun read(context: Context, uri: String): ExifData = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
            val x = ExifInterface(input)

            fun tag(name: String) = x.getAttribute(name)?.trim()?.takeIf { it.isNotEmpty() }

            // Der Server liefert das EXIF-Rohformat "2026:09:01 18:33:12";
            // das Panel zerlegt es selbst, also unveraendert durchreichen.
            val datetime = tag(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: tag(ExifInterface.TAG_DATETIME)

            val make = tag(ExifInterface.TAG_MAKE)
            val model = tag(ExifInterface.TAG_MODEL)

            val focal = x.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0 }?.let { "%.0fmm".format(it) }
            val aperture = x.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                .takeIf { it > 0 }?.let { "f/%.1f".format(it) }
            val iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.toIntOrNull()
                ?: tag(ExifInterface.TAG_ISO_SPEED_RATINGS)?.toIntOrNull()
            // Belichtung lesbar: Bruch unter einer Sekunde, sonst Sekunden.
            // Der Server schreibt hier "0.00s" — bei 1/2000 sagt das nichts.
            val shutter = x.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                .takeIf { it > 0 }
                ?.let { if (it < 1.0) "1/${Math.round(1.0 / it)}" else "%.1f".format(it) }

            val latLong = FloatArray(2).takeIf { x.getLatLong(it) }
            val altitude = x.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.toInt()

            ExifData(
                datetime = datetime,
                camera = if (make != null || model != null) {
                    ExifCamera(make = make, model = model)
                } else null,
                lens = tag(ExifInterface.TAG_LENS_MODEL),
                settings = if (focal != null || aperture != null ||
                    iso != null || shutter != null
                ) {
                    ExifSettings(
                        focalLength = focal, aperture = aperture,
                        shutterSpeed = shutter, iso = iso,
                    )
                } else null,
                gps = latLong?.let {
                    ExifGps(
                        lat = it[0].toDouble(), lon = it[1].toDouble(),
                        altitude = altitude,
                    )
                },
                description = tag(ExifInterface.TAG_IMAGE_DESCRIPTION),
            )
        }
    }.getOrElse { ExifData(error = it.message ?: Texte.t(Res.string.exif_err_unreadable)) }
}
