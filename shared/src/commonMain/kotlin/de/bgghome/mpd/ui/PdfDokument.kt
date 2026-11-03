package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File

/**
 * PDF-Seiten als Bilder (22.09.2026): Android ueber PdfRenderer, der
 * Desktop ueber pdfbox. Nur eine Seite zugleich - der Aufrufer
 * synchronisiert nicht, das tut die Fassung selbst.
 */
expect class PdfDokument(datei: File) : AutoCloseable {
    val seiten: Int
    /** Seite [index] gerendert; Skalierung aus [seitenSkalierung]. */
    fun seite(index: Int, breitePx: Float, hoehePx: Float, dichte: Float): ImageBitmap?
    override fun close()
}
