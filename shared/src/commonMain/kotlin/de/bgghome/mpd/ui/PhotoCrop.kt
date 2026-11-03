package de.bgghome.mpd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Zuschnitt-Editor. Die normierte CropBox bezieht sich aufs Bild NACH
 * exif_transpose → rotate → flips → straighten (core/photo_edit.py) —
 * deshalb wird die Vorschau hier mit exakt derselben Pipeline vorberechnet
 * (inkl. einbeschriebenem Rechteck beim Geraderichten, Formel wie
 * _largest_inscribed_rect / stackoverflow.com/q/16702966).
 */

/** Groesstes achsparalleles Rechteck in einem um [angleRad] gedrehten w×h. */
internal fun largestInscribedRect(w: Float, h: Float, angleRad: Double): Pair<Float, Float> {
    if (w <= 0f || h <= 0f) return 0f to 0f
    var a = abs(angleRad) % Math.PI
    if (a > Math.PI / 2) a = Math.PI - a
    val sinA = sin(a).toFloat()
    val cosA = cos(a).toFloat()
    val widthIsLonger = w >= h
    val long = if (widthIsLonger) w else h
    val short = if (widthIsLonger) h else w
    return if (short <= 2f * sinA * cosA * long || abs(sinA - cosA) < 1e-10f) {
        val x = 0.5f * short
        if (widthIsLonger) (x / sinA) to (x / cosA) else (x / cosA) to (x / sinA)
    } else {
        val cos2a = cosA * cosA - sinA * sinA
        ((w * cosA - h * sinA) / cos2a) to ((h * cosA - w * sinA) / cos2a)
    }
}

/**
 * Vorschau-Bitmap mit der Server-Transformationskette (ohne Farb-Filter).
 * Gerechnet auf einem Compose-Canvas statt mit android.graphics.Matrix,
 * damit derselbe Code auf dem Desktop laeuft (22.09.2026).
 */
internal fun cropPreviewBitmap(
    src: ImageBitmap,
    rotation: Int,
    flipH: Boolean,
    flipV: Boolean,
    straighten: Float,
): ImageBitmap {
    var bmp = src
    if (rotation != 0 || flipH || flipV) {
        val quer = ((rotation % 360) + 360) % 360 in setOf(90, 270)
        val w = if (quer) bmp.height else bmp.width
        val h = if (quer) bmp.width else bmp.height
        val out = ImageBitmap(w, h)
        val canvas = Canvas(out)
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(rotation.toFloat())
        canvas.scale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        canvas.translate(-bmp.width / 2f, -bmp.height / 2f)
        canvas.drawImage(bmp, Offset.Zero, Paint())
        bmp = out
    }
    if (abs(straighten) > 0.01f) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val rad = Math.toRadians(straighten.toDouble())
        val bw = (abs(w * cos(rad)) + abs(h * sin(rad))).toInt().coerceAtLeast(1)
        val bh = (abs(w * sin(rad)) + abs(h * cos(rad))).toInt().coerceAtLeast(1)
        val rotated = ImageBitmap(bw, bh)
        Canvas(rotated).apply {
            translate(bw / 2f, bh / 2f)
            rotate(straighten)
            translate(-w / 2f, -h / 2f)
            drawImage(bmp, Offset.Zero, Paint())
        }
        val (wr, hr) = largestInscribedRect(w, h, rad)
        val cw = wr.toInt().coerceIn(1, bw)
        val ch = hr.toInt().coerceIn(1, bh)
        val out = ImageBitmap(cw, ch)
        Canvas(out).drawImage(
            rotated,
            Offset(-((bw - cw) / 2).coerceAtLeast(0).toFloat(), -((bh - ch) / 2).coerceAtLeast(0).toFloat()),
            Paint(),
        )
        bmp = out
    }
    return bmp
}

private enum class CropHandle { TL, TR, BL, BR, MOVE, NONE }

/**
 * Rahmen-Editor: Ecken ziehen, Innenflaeche verschiebt. [crop] und
 * [onCropChange] arbeiten normiert (0..1) im transformierten Bildraum.
 */
@Composable
internal fun CropEditor(
    bitmap: ImageBitmap?,
    crop: Rect,
    onCropChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val density = LocalDensity.current.density
    val minSizeNorm = 0.05f

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val imageAr = bitmap.width.toFloat() / bitmap.height
        // ContentScale.Fit: Bildrechteck zentriert im Container
        val fitW: Float
        val fitH: Float
        if (containerW / containerH > imageAr) {
            fitH = containerH
            fitW = containerH * imageAr
        } else {
            fitW = containerW
            fitH = containerW / imageAr
        }
        val imgLeft = (containerW - fitW) / 2
        val imgTop = (containerH - fitH) / 2

        fun toScreen(r: Rect) = Rect(
            imgLeft + r.left * fitW, imgTop + r.top * fitH,
            imgLeft + r.right * fitW, imgTop + r.bottom * fitH,
        )

        var activeHandle by remember { mutableStateOf(CropHandle.NONE) }
        // DER RAHMEN LIESS SICH NICHT ZIEHEN (12.09.2026:
        // „Zuschneiden/croppen in Lightbox geht nicht"). Der Gestenblock
        // unten hat als Key nur `bitmap`, wird also nicht neu gestartet,
        // wenn sich der Rahmen aendert — und fing damit den Wert von `crop`
        // aus dem ERSTEN Durchlauf ein. Jedes Move-Event rechnete vom
        // Ausgangsrahmen plus einem winzigen Einzel-Delta: der Rahmen zuckte
        // und sprang zurueck, und weil er beim Oeffnen das ganze Bild ist,
        // blieb am Ende „keine Aenderungen" stehen. rememberUpdatedState
        // reicht den jeweils aktuellen Rahmen in die laufende Lambda nach.
        val cropJetzt by rememberUpdatedState(crop)

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = toScreen(cropJetzt)
                            val grab = 28f * density
                            fun near(p: Offset, x: Float, y: Float) =
                                abs(p.x - x) < grab && abs(p.y - y) < grab
                            activeHandle = when {
                                near(pos, r.left, r.top) -> CropHandle.TL
                                near(pos, r.right, r.top) -> CropHandle.TR
                                near(pos, r.left, r.bottom) -> CropHandle.BL
                                near(pos, r.right, r.bottom) -> CropHandle.BR
                                r.contains(pos) -> CropHandle.MOVE
                                else -> CropHandle.NONE
                            }
                        },
                        onDragEnd = { activeHandle = CropHandle.NONE },
                        onDragCancel = { activeHandle = CropHandle.NONE },
                    ) { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / fitW
                        val dy = dragAmount.y / fitH
                        var (l, t, r2, b) =
                            listOf(cropJetzt.left, cropJetzt.top, cropJetzt.right, cropJetzt.bottom)
                        when (activeHandle) {
                            CropHandle.TL -> { l += dx; t += dy }
                            CropHandle.TR -> { r2 += dx; t += dy }
                            CropHandle.BL -> { l += dx; b += dy }
                            CropHandle.BR -> { r2 += dx; b += dy }
                            CropHandle.MOVE -> {
                                val w = r2 - l
                                val h = b - t
                                l = (l + dx).coerceIn(0f, 1f - w)
                                t = (t + dy).coerceIn(0f, 1f - h)
                                r2 = l + w
                                b = t + h
                            }
                            CropHandle.NONE -> return@detectDragGestures
                        }
                        if (activeHandle != CropHandle.MOVE) {
                            l = l.coerceIn(0f, r2 - minSizeNorm)
                            t = t.coerceIn(0f, b - minSizeNorm)
                            r2 = r2.coerceIn(l + minSizeNorm, 1f)
                            b = b.coerceIn(t + minSizeNorm, 1f)
                        }
                        onCropChange(Rect(l, t, r2, b))
                    }
                },
        ) {
            val r = toScreen(crop)
            val scrim = Color(0x99000000)
            // Abdunkeln ausserhalb des Rahmens
            drawRect(scrim, Offset(imgLeft, imgTop), Size(fitW, r.top - imgTop))
            drawRect(scrim, Offset(imgLeft, r.bottom), Size(fitW, imgTop + fitH - r.bottom))
            drawRect(scrim, Offset(imgLeft, r.top), Size(r.left - imgLeft, r.height))
            drawRect(scrim, Offset(r.right, r.top), Size(imgLeft + fitW - r.right, r.height))
            // Rahmen + Drittel-Linien
            drawRect(
                Color.White, r.topLeft, r.size,
                style = Stroke(width = 1.5f * density),
            )
            for (i in 1..2) {
                val gx = r.left + r.width * i / 3
                val gy = r.top + r.height * i / 3
                drawLine(Color(0x66FFFFFF), Offset(gx, r.top), Offset(gx, r.bottom), density)
                drawLine(Color(0x66FFFFFF), Offset(r.left, gy), Offset(r.right, gy), density)
            }
            // Eck-Griffe
            val hs = 10f * density
            for ((cx, cy) in listOf(
                r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom,
            )) {
                drawCircle(Color.White, hs / 2, Offset(cx, cy))
            }
        }
    }
}
