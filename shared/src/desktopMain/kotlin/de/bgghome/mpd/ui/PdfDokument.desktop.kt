package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer

/** pdfbox: Seitengroesse in Punkt wie bei Androids PdfRenderer, Skalierung identisch. */
actual class PdfDokument actual constructor(datei: File) : AutoCloseable {
    private val dokument: PDDocument = Loader.loadPDF(datei)
    private val renderer = PDFRenderer(dokument)
    actual val seiten: Int get() = dokument.numberOfPages

    actual fun seite(index: Int, breitePx: Float, hoehePx: Float, dichte: Float): ImageBitmap? =
        runCatching {
            synchronized(dokument) {
                val box = dokument.getPage(index).mediaBox
                val scale = seitenSkalierung(box.width.toInt(), box.height.toInt(), breitePx, hoehePx, dichte)
                renderer.renderImage(index, scale).toComposeImageBitmap()
            }
        }.getOrNull()

    actual override fun close() { runCatching { dokument.close() } }
}
