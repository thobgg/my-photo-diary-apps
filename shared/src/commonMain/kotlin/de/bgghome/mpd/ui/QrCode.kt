package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.common.BitMatrix
import kotlin.math.floor
import kotlin.math.min
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.compose.resources.stringResource

/*
 * QR-Code fuer Beisteuern-Links.
 *
 * Warum ueberhaupt: ein Contrib-Link nuetzt nichts, solange er nicht beim
 * Gast ankommt. Wer auf einer Feier steht, kann keine URL diktieren — der
 * QR-Code ist der einzige Weg, der ohne Konto, ohne App und ohne Tippen
 * funktioniert.
 *
 * Gemalt wird selbst (nur `zxing:core`, kein zxing-android-embedded): wir
 * brauchen keinen Scanner, nur den Encoder.
 */

/**
 * Zeigt die Adresse nur ins eigene Netz? Dann erreicht ein Gast den Link
 * nur im selben WLAN — der QR-Code laeuft ausserhalb ins Leere, ohne dass
 * man es ihm ansieht.
 *
 * Die Erreichbarkeit selbst ist Sache des Betreibers (Portfreigabe, DDNS,
 * Reverse-Proxy); die App kann sie nicht herstellen. Sie kann aber sagen,
 * was Sache ist, statt einen Code zu zeigen, der beim Gast stumm scheitert.
 *
 * Erkannt werden die privaten IPv4-Bereiche (RFC 1918), Loopback,
 * Link-Local (169.254., auch APIPA) sowie die ueblichen Heimnetz-Endungen.
 * Ein Hostname wie `mpd.example.org` gilt als oeffentlich — ob dahinter
 * wirklich jemand ans Netz darf, kann nur ein Versuch zeigen.
 */
fun isLocalOnlyAddress(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
        ?.lowercase()
        ?.trim('[', ']')          // IPv6 kommt in eckigen Klammern
        ?: return false

    if (host == "localhost" || host == "::1") return true
    if (host.endsWith(".local") || host.endsWith(".lan") ||
        host.endsWith(".home") || host.endsWith(".internal") ||
        host.endsWith(".fritz.box") || host == "fritz.box"
    ) return true
    // IPv6 Unique Local (fc00::/7) und Link-Local (fe80::/10)
    if (host.startsWith("fd") || host.startsWith("fc") || host.startsWith("fe80:")) return true

    val teile = host.split(".")
    if (teile.size == 4) {
        val z = teile.map { it.toIntOrNull() ?: return false }
        if (z.any { it !in 0..255 }) return false
        return z[0] == 10 ||
            z[0] == 127 ||
            (z[0] == 192 && z[1] == 168) ||
            (z[0] == 172 && z[1] in 16..31) ||
            (z[0] == 169 && z[1] == 254)
    }
    return false
}

/**
 * Encoder-Einstellungen — **eine** Quelle fuer App und Test, sonst prueft
 * der Test etwas anderes, als die App erzeugt.
 *
 * Fehlerkorrektur **M** (~15 %): genug Reserve fuer einen Bildschirm, der
 * schraeg oder gespiegelt abfotografiert wird, ohne den Code so dicht zu
 * machen, dass ihn aeltere Handykameras nicht mehr fassen. Rand 2 Module
 * statt der genormten 4 — der Code sitzt hier auf einer eigenen weissen
 * Flaeche, die den Rand ohnehin mitbringt.
 */
internal val QR_HINTS: Map<EncodeHintType, Any> = mapOf(
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    EncodeHintType.CHARACTER_SET to "UTF-8",
    EncodeHintType.MARGIN to 2,
)

/**
 * Modulmatrix zu [content] in Modulaufloesung (Breite 0 = "so klein wie
 * moeglich", ZXing nimmt dann die Modulzahl samt Rand). Gemalt wird in
 * QrCodeImage auf einen Compose-Canvas — ohne android.graphics, damit
 * derselbe Code auf dem Desktop laeuft (Aufteilung 22.09.2026).
 */
fun qrMatrix(content: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, QR_HINTS)
}.getOrNull()

/**
 * Zeigt [content] als QR-Code. Immer auf weissem Grund mit schwarzen
 * Modulen, unabhaengig vom App-Theme: ein invertierter Code wird von vielen
 * Kameras nicht erkannt, und die Farbe ist hier kein Gestaltungsmittel,
 * sondern Teil der Funktion. Module werden auf ganze Pixel gerundet und
 * ohne Filter gemalt: weiche Kanten sind fuer einen Scanner schlechter als
 * harte Treppen. [sizePx] ist nur noch die Vorgabe, wenn der Modifier
 * keine Groesse setzt.
 */
@Composable
fun QrCodeImage(content: String, sizePx: Int = 720, modifier: Modifier = Modifier) {
    val matrix = remember(content) { qrMatrix(content) } ?: return
    val beschreibung = stringResource(Res.string.qr_description)
    val vorgabe = with(LocalDensity.current) { sizePx.toDp() }
    Canvas(
        modifier
            .defaultMinSize(minWidth = vorgabe, minHeight = vorgabe)
            .semantics { contentDescription = beschreibung },
    ) {
        val w = matrix.width
        val h = matrix.height
        val cell = floor(min(size.width / w, size.height / h))
        val x0 = floor((size.width - cell * w) / 2f)
        val y0 = floor((size.height - cell * h) / 2f)
        drawRect(QrPaper)
        for (y in 0 until h) for (x in 0 until w) {
            if (matrix.get(x, y)) {
                drawRect(Color.Black, Offset(x0 + x * cell, y0 + y * cell), Size(cell, cell))
            }
        }
    }
}

/** Weiss als Compose-Farbe, fuer die Flaeche unter dem Code. */
internal val QrPaper = Color.White
