package de.bgghome.mpd.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.Photo

/**
 * Eine Seite der Lightbox: das Bild mit Zoom. Android nimmt
 * SubsamplingScaleImageView (kachelweise, scharf bis 1:1, Original wird
 * nachgeladen), der Desktop eine Compose-Seite mit Mausrad, Doppelklick
 * und Ziehen (Aufteilung 22.09.2026).
 */
@Composable
internal expect fun PhotoPage(
    vm: AppViewModel,
    photo: Photo,
    imageLoader: ImageLoader,
    pagerState: PagerState,
    onSwipeUp: () -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissDragEnd: (Float, Float) -> Unit,
)

/** System-Leisten weg, solange die Lightbox offen ist (nur Android). */
@Composable
internal expect fun SystemleistenAusblenden()
