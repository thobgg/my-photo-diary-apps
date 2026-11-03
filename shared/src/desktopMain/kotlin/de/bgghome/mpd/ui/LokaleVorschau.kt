package de.bgghome.mpd.ui

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import de.bgghome.mpd.data.PcOrdner
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Vorschaubilder fuer die Fotos aus den PC-Ordnern (Desktop, 04.10.2026).
 *
 * Coil dekodiert am Desktop jede Datei in voller Groesse (Skia,
 * makeFromEncoded) und skaliert erst danach. Fuer ein 12-Megapixel-Foto
 * sind das zig Millisekunden — im Zeitstrahl mit Tausenden Kacheln waere
 * jedes Scrollen ein Ruckeln. Deshalb: kleine Anfragen (Kacheln, Ordner-
 * Vorschau) bekommen eine einmal gerechnete JPEG-Vorschau aus dem Cache,
 * grosse (Lightbox) die Originaldatei.
 *
 * Nebenbei wird hier aus der „file:"-Adresse eine java.io.File: Coils
 * eigenes Lesen der Adresse ist bei Leerzeichen und Umlauten unsicher.
 */
class LokaleVorschau(private val cache: File) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val daten = chain.request.data as? String ?: return chain.proceed()
        if (!daten.startsWith("file:")) return chain.proceed()
        val datei = runCatching { File(URI(daten)) }.getOrNull() ?: return chain.proceed()

        val w = (chain.size.width as? Dimension.Pixels)?.px
        val h = (chain.size.height as? Dimension.Pixels)?.px
        val klein = w != null && h != null && maxOf(w, h) <= GRENZE
        val ziel = if (klein && PcOrdner.istBild(datei.name)) {
            runCatching { vorschau(datei) }.getOrNull() ?: datei
        } else datei
        return chain.withRequest(chain.request.newBuilder().data(ziel).build()).proceed()
    }

    /** Vorschau aus dem Cache oder neu gerechnet; Schluessel = Pfad,
     *  Aenderungszeit und Groesse, damit eine bearbeitete Datei neu kommt. */
    private fun vorschau(datei: File): File {
        val schluessel = MessageDigest.getInstance("SHA-1")
            .digest("${datei.absolutePath}|${datei.lastModified()}|${datei.length()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ziel = File(cache, "$schluessel.jpg")
        if (ziel.isFile && ziel.length() > 0) return ziel

        // Skia wendet die EXIF-Drehung beim Dekodieren selbst an (geprueft
        // am Test-JPEG, PcOrdnerTest) — hier also nur verkleinern.
        val bild = Image.makeFromEncoded(datei.readBytes())
        try {
            val faktor = minOf(1f, VORSCHAU.toFloat() / maxOf(bild.width, bild.height))
            val tw = maxOf(1, (bild.width * faktor).toInt())
            val th = maxOf(1, (bild.height * faktor).toInt())
            val flaeche = Surface.makeRasterN32Premul(tw, th)
            flaeche.canvas.drawImageRect(
                bild, Rect.makeWH(bild.width.toFloat(), bild.height.toFloat()),
                Rect.makeWH(tw.toFloat(), th.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
            )
            val jpeg = flaeche.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85)
                ?: error("JPEG nicht kodierbar")
            cache.mkdirs()
            val tmp = File(cache, "$schluessel.tmp")
            tmp.writeBytes(jpeg.bytes)
            tmp.renameTo(ziel)
            flaeche.close()
        } finally {
            bild.close()
        }
        return ziel
    }

    companion object {
        /** Bis zu dieser Kantenlaenge (px) gilt eine Anfrage als Kachel. */
        private const val GRENZE = 720
        /** Kantenlaenge der gerechneten Vorschau. */
        private const val VORSCHAU = 720
    }
}
