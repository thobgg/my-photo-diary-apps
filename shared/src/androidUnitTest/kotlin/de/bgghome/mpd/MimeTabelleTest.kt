package de.bgghome.mpd

import de.bgghome.mpd.ui.mimeAusTabelle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Die MIME-Tabelle fuers Weitergeben — 07.09.2026.
 *
 * Anlass: Die Tabelle kannte nur mp4, m4v und mov. Jedes andere Video
 * fiel in den Rueckfall „image/jpeg", ein weitergegebenes .mkv wurde der
 * empfangenden App also als Bild angekuendigt. Aufgefallen ist das erst,
 * als der Server am selben Tag denselben Fehler auf seiner Seite ablegte
 * (.heic als image/jpeg) — zwei Mal dieselbe Ursache: ein Typ, der aus
 * einem Namen GERATEN statt gesagt wird.
 *
 * Diese Tests sind die Wache dagegen. Sie spiegeln bewusst die Mengen aus
 * `backend/core/media_types.py` (PHOTO_EXTS / VIDEO_MIME). Wer dort eine
 * Endung ergaenzt und hier nicht, faellt auf.
 *
 * Der Rueckfall auf Androids MimeTypeMap wird hier NICHT geprueft — der
 * braucht ein Geraet. Genau deshalb muss jede Endung, die MPD kennt, in
 * der eigenen Tabelle stehen und den Rueckfall nie erreichen.
 */
class MimeTabelleTest {

    /** PHOTO_EXTS aus media_types.py, Stand 07.09.2026. */
    private val fotoEndungen = listOf(
        "jpg", "jpeg", "png", "heic", "gif", "webp", "tif", "tiff", "bmp", "jfif",
    )

    /** VIDEO_MIME aus media_types.py, Stand 07.09.2026. */
    private val videoEndungen = listOf(
        "mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp",
        "mts", "m2ts", "wmv", "mpg", "mpeg",
    )

    @Test fun jedesFotoIstEinBild() {
        for (e in fotoEndungen) {
            val mime = mimeAusTabelle(e)
            assertTrue("$e steht gar nicht in der Tabelle", mime != null)
            assertTrue("$e ergibt $mime statt image/*", mime!!.startsWith("image/"))
        }
    }

    @Test fun jedesVideoIstEinVideo() {
        // Der eigentliche Fehler von heute: ein Video, das als Bild
        // angekuendigt wird. Kein Rueckfall, keine Ausnahme.
        for (e in videoEndungen) {
            val mime = mimeAusTabelle(e)
            assertTrue("$e steht gar nicht in der Tabelle", mime != null)
            assertTrue("$e ergibt $mime statt video/*", mime!!.startsWith("video/"))
        }
    }

    @Test fun heicUndHeifBleibenUnterscheidbar() {
        // .heic ist der Typ genau dieser Auspraegung, .heif der
        // Oberbegriff — der Server benennt es seit 07.09. ebenso.
        assertEquals("image/heic", mimeAusTabelle("heic"))
        assertEquals("image/heif", mimeAusTabelle("heif"))
    }

    @Test fun jfifIstEinJpeg() {
        // .jfif ist eine JPEG-Datei mit anderer Endung — nicht etwa ein
        // eigenes Format. Der Server sieht es genauso.
        assertEquals("image/jpeg", mimeAusTabelle("jfif"))
    }

    @Test fun unbekanntesGibtNullStattEinerBehauptung() {
        // Frueher war der Rueckfall „image/jpeg" — eine Behauptung ueber
        // etwas, das man nicht weiss. Jetzt sagt die Tabelle null, und der
        // Aufrufer fragt erst Android und gibt dann ehrlich auf
        // (application/octet-stream). Der Android-Teil ist hier nicht
        // pruefbar, deshalb steht er ausserhalb dieser Funktion.
        assertNull(mimeAusTabelle("qqqnichts"))
        assertNull(mimeAusTabelle(""))
    }
}

