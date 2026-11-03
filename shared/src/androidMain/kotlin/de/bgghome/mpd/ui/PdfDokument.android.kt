package de.bgghome.mpd.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual class PdfDokument actual constructor(datei: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    actual val seiten: Int get() = renderer.pageCount

    // PdfRenderer erlaubt nur eine offene Seite zugleich -> synchronized.
    actual fun seite(index: Int, breitePx: Float, hoehePx: Float, dichte: Float): ImageBitmap? =
        runCatching {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = seitenSkalierung(page.width, page.height, breitePx, hoehePx, dichte)
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                }
            }
        }.getOrNull()

    actual override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
