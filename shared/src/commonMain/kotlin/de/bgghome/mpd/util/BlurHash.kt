package de.bgghome.mpd.util

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * Kompakter BlurHash-Decoder (Standard-Algorithmus von blurha.sh, wie ihn
 * das Web-Frontend in js/blurhash.js nutzt). Der Server liefert die Hashes
 * seit 2.0.178 an Timeline-Items mit — dekodiert in Miniaturgroesse ist das
 * der Sofort-Platzhalter, bevor Coil die Kachel geladen hat.
 */
object BlurHash {

    private const val CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) {
            val idx = CHARS.indexOf(s[i])
            if (idx < 0) return -1
            v = v * 83 + idx
        }
        return v
    }

    private fun srgbToLinear(v: Int): Float {
        val x = v / 255f
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val r = if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (r * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun signPow(v: Float, e: Float): Float = abs(v).pow(e).withSign(v)

    /** null bei ungueltigem Hash. width/height klein halten (z.B. 24×24). */
    /** ARGB-Pixel, zeilenweise; null bei ungueltigem Hash. */
    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (hash.length < 6) return null
        val sizeFlag = decode83(hash, 0, 1)
        if (sizeFlag < 0) return null
        val numY = sizeFlag / 9 + 1
        val numX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null

        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(numX * numY) { i ->
            if (i == 0) {
                val v = decode83(hash, 2, 6)
                floatArrayOf(
                    srgbToLinear(v shr 16),
                    srgbToLinear((v shr 8) and 255),
                    srgbToLinear(v and 255),
                )
            } else {
                val v = decode83(hash, 4 + i * 2, 6 + i * 2)
                floatArrayOf(
                    signPow((v / (19 * 19) - 9) / 9f, 2f) * maxAc * punch,
                    signPow((v / 19 % 19 - 9) / 9f, 2f) * maxAc * punch,
                    signPow((v % 19 - 9) / 9f, 2f) * maxAc * punch,
                )
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f; var g = 0f; var b = 0f
                for (j in 0 until numY) {
                    val cy = cos(PI * y * j / height).toFloat()
                    for (i in 0 until numX) {
                        val basis = cos(PI * x * i / width).toFloat() * cy
                        val c = colors[j * numX + i]
                        r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or
                    (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return pixels
    }

    /**
     * Platzhalter-Painter aus dem Hash. Gemalt wird Pixel fuer Pixel auf
     * ein ImageBitmap — ohne android.graphics, damit derselbe Code auf dem
     * Desktop laeuft (Aufteilung 22.09.2026); bei 24×24 sind das 576
     * Rechtecke, einmal je Kachel im remember.
     */
    fun painter(hash: String, width: Int, height: Int, punch: Float = 1f): Painter? {
        val pixels = decode(hash, width, height, punch) ?: return null
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until height) for (x in 0 until width) {
            paint.color = Color(pixels[y * width + x])
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
        return BitmapPainter(bitmap)
    }
}
