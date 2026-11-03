package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.request.ImageRequest

/*
 * Bilder zwischen Coil und Compose, je Plattform (22.09.2026): auf Android
 * ist ein Coil-Bild ein android.graphics.Bitmap, auf dem Desktop ein
 * Skia-Bitmap; beide werden hier zum Compose-ImageBitmap, mit dem der
 * Zuschnitt-Editor und die Vorschau rechnen.
 */

/** Coil-Ergebnis als Compose-Bitmap, null wenn kein Rasterbild. */
expect fun Image.zuImageBitmap(): ImageBitmap?

/**
 * Bitmap im Hauptspeicher anfordern. Android liefert sonst Hardware-Bitmaps,
 * die sich nicht auf einen Software-Canvas zeichnen lassen; auf dem Desktop
 * gibt es den Unterschied nicht.
 */
expect fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder
