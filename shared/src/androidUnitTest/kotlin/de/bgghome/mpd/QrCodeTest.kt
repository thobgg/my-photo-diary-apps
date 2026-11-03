package de.bgghome.mpd

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import de.bgghome.mpd.ui.QR_HINTS
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Der QR-Code fuer Beisteuern-Links wird auf einer Feier hochgehalten —
 * faellt dort auf, dass er nicht lesbar ist, ist der Abend gelaufen und
 * der Link nicht wiederherstellbar (der Klartext-Token steht nur in der
 * Antwort auf `create`).
 *
 * Ein Scanner steht mir nicht zur Verfuegung, ZXing kann seine eigenen
 * Codes aber dekodieren. Der Test kodiert mit **denselben** Einstellungen
 * wie die App (QR_HINTS — genau dafuer ist die Konstante geteilt) und
 * liest zurueck. Damit ist belegt, dass ein realistisch langer Link mit
 * Fehlerkorrektur M und Rand 2 durchgeht.
 */
class QrCodeTest {

    /** Minimale LuminanceSource ueber eine BitMatrix: schwarz = 0, weiss = 255. */
    private class MatrixSource(private val m: BitMatrix) :
        LuminanceSource(m.width, m.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (m.get(x, y)) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val off = y * width
                for (x in 0 until width) out[off + x] = if (m.get(x, y)) 0 else 255.toByte()
            }
            return out
        }
    }

    private fun roundTrip(text: String, size: Int = 720): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, QR_HINTS)
        val bitmap = BinaryBitmap(HybridBinarizer(MatrixSource(matrix)))
        return QRCodeReader().decode(bitmap).text
    }

    /** Der echte Fall: Basis-URL plus `/contrib/` plus 43-Zeichen-Token. */
    @Test fun beisteuernLinkUeberstehtDenRundweg() {
        val token = "u7Fq3ZkPl9xR2sVbN4mTgH8jW1cY6dA0eK5oI3pQzXs"
        val url = "https://mpd.example.org/contrib/$token"
        assertEquals(url, roundTrip(url))
    }

    /** Lange Adresse mit Port und Pfad — der SPK-Fall auf eigener NAS. */
    @Test fun langeSelbstgehosteteAdresseGehtAuch() {
        val url = "https://meine-nas.beispiel-familie.de:5001/mpd/contrib/" +
            "Qw8ErTz5UiOp1AsDfGh2JkLyXcVb3NmQ9wErTz7Ui"
        assertEquals(url, roundTrip(url))
    }

    /** Umlaute im Ordnernamen taeten weh, wenn UTF-8 nicht gesetzt waere. */
    @Test fun utf8Ueberlebt() {
        val text = "https://mpd.example.org/contrib/abc?ordner=Grillfest%20M%C3%BCller"
        assertEquals(text, roundTrip(text))
    }

    /** Auch bei kleiner Zielgroesse muss der Inhalt stimmen. */
    @Test fun kleineAusgabeBleibtLesbar() {
        val url = "https://mpd.example.org/contrib/u7Fq3ZkPl9xR2sVbN4mTgH8jW1cY6dA0eK5oI3pQzXs"
        assertEquals(url, roundTrip(url, size = 240))
    }
}
