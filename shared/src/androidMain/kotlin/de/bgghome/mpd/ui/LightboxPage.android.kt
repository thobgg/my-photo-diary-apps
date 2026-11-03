package de.bgghome.mpd.ui

import coil3.request.allowHardware
import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.OpenAlbum
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import java.io.File
import org.jetbrains.compose.resources.stringResource

/*
 * Android-Seite der Lightbox (Aufteilung 22.09.2026): SubsamplingScaleImageView
 * fuer verpixelungsfreies Zoomen bis 1:1, Original-Nachladen, EXIF-Drehung
 * bei Geraete-Aufnahmen, und die System-Leisten. Der Rest der Lightbox
 * (Pager, Filmstreifen, Kopf, Seitenleisten) liegt in commonMain.
 */

@Composable
internal actual fun SystemleistenAusblenden() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowInsetsControllerCompat(it, view)
        }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

private sealed interface PageStage {
    data object Loading : PageStage
    data class Ready(val xl: android.graphics.Bitmap) : PageStage
    data class Failed(val message: String) : PageStage
}

/** Ab diesem Vielfachen der Einpass-Groesse wird das Original geholt. */
private const val ORIGINAL_TRIGGER = 1.3f

@Composable
internal actual fun PhotoPage(
    vm: AppViewModel,
    photo: Photo,
    imageLoader: ImageLoader,
    pagerState: androidx.compose.foundation.pager.PagerState,
    onSwipeUp: () -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissDragEnd: (Float, Float) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Blaettert den Pager von Hand: mitziehen beim Wischen, beim Loslassen
    // je nach Geschwindigkeit/Position zur Zielseite einrasten.
    val dragListener = remember(pagerState) {
        object : PagerAwareScaleImageView.PagerDragListener {
            var startPage = 0

            override fun onDrag(deltaX: Float) {
                if (!pagerState.isScrollInProgress) startPage = pagerState.currentPage
                pagerState.dispatchRawDelta(-deltaX)
            }

            override fun onDragEnd(velocityX: Float) {
                val target = when {
                    velocityX < -1000f -> startPage + 1
                    velocityX > 1000f -> startPage - 1
                    else -> pagerState.currentPage
                }.coerceIn(0, pagerState.pageCount - 1)
                scope.launch { pagerState.animateScrollToPage(target) }
            }

            override fun onDragCancel() {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
            }
        }
    }

    // Nach einem Server-Edit (Cache-Bust) laedt die Seite komplett neu.
    val editVersion = vm.editVersions[photo.file]

    var stage by remember(photo, editVersion) { mutableStateOf<PageStage>(PageStage.Loading) }
    val wantOriginal = remember(photo, editVersion) { mutableStateOf(false) }
    var originalFile by remember(photo, editVersion) { mutableStateOf<File?>(null) }
    var originalError by remember(photo, editVersion) { mutableStateOf(false) }
    var swapped by remember(photo, editVersion) { mutableStateOf(false) }
    // Zaehlt Gestenenden hoch, damit update{} nach dem Loslassen erneut
    // laeuft — der Original-Tausch passiert nie in einer laufenden Geste.
    val gestureEpoch = remember(photo, editVersion) { mutableStateOf(0) }
    var showLoadingHint by remember(photo, editVersion) { mutableStateOf(false) }

    // Basisbild: full (4096) sobald der Server es liefert und der Bildschirm
    // mehr als 2048 px hat (Tab S8 Ultra 2960, S25 Ultra 3120) — sonst xl.
    val displayMetrics = context.resources.displayMetrics
    val screenMaxPx = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val baseImageUrl =
        if (screenMaxPx > 2048 && photo.fullUrl != null) photo.fullUrl else photo.xlUrl

    LaunchedEffect(photo, editVersion) {
        val request = ImageRequest.Builder(context)
            .data(baseImageUrl)
            .allowHardware(false) // SSIV braucht ein Software-Bitmap
            .build()
        stage = when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                val bmp = (result.image as? coil3.BitmapImage)?.bitmap
                if (bmp != null) PageStage.Ready(bmp)
                else PageStage.Failed(Texte.t(Res.string.lb_decode_failed))
            }
            is ErrorResult -> PageStage.Failed(
                result.throwable.message ?: Texte.t(Res.string.lb_load_failed)
            )
        }
    }

    LaunchedEffect(wantOriginal.value) {
        if (wantOriginal.value && originalFile == null && !originalError) {
            try {
                originalFile = vm.api.downloadToCache(
                    File(context.cacheDir, "originals"),
                    photo.originalUrl,
                )
            } catch (e: Exception) {
                originalError = true
            }
        }
    }

    // Ladehinweis erst ab 400 ms — im LAN ist das Original oft so schnell
    // da, dass die Meldung nur als Blitz stoeren wuerde.
    LaunchedEffect(wantOriginal.value, originalFile, originalError) {
        if (wantOriginal.value && originalFile == null && !originalError) {
            kotlinx.coroutines.delay(400)
            if (originalFile == null && !originalError) showLoadingHint = true
        } else {
            showLoadingHint = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = stage) {
            PageStage.Loading -> CircularProgressIndicator()

            is PageStage.Failed -> Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )

            is PageStage.Ready -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PagerAwareScaleImageView(ctx).apply {
                            pagerDragListener = dragListener
                            onGestureEnd = { gestureEpoch.value++ }
                            this.onSwipeUp = onSwipeUp
                            this.onDismissDrag = onDismissDrag
                            this.onDismissDragEnd = onDismissDragEnd
                            // SSIV meldet den bestaetigten Einzel-Tipp als Klick
                            setOnClickListener { onTap() }
                            setMinimumTileDpi(160)
                            maxScale = 6f
                            orientation = SubsamplingScaleImageView.ORIENTATION_0
                            setImage(ImageSource.cachedBitmap(s.xl))
                            setOnStateChangedListener(object :
                                SubsamplingScaleImageView.OnStateChangedListener {
                                override fun onScaleChanged(newScale: Float, origin: Int) {
                                    if (newScale > minScale * ORIGINAL_TRIGGER) {
                                        wantOriginal.value = true
                                    }
                                }
                                override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
                            })
                        }
                    },
                    update = { view ->
                        gestureEpoch.value // Snapshot-Read: erneuter Lauf nach Gestenende
                        val file = originalFile
                        if (file != null && !swapped && view is PagerAwareScaleImageView && !view.isTouching) {
                            swapped = true
                            swapToOriginal(view, s.xl, file)
                        }
                    },
                )

                if (showLoadingHint) {
                    Text(
                        stringResource(Res.string.lb_original_loading),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (originalError) {
                    Text(
                        stringResource(Res.string.lb_original_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Tauscht die Basisansicht (xl/full) gegen das Original und behaelt dabei
 * die aktuelle Zoomposition bei. Der SSIV-Zustand rechnet in
 * Quellkoordinaten, darum werden Mitte und Massstab vom Basisraum in den
 * (ggf. EXIF-gedrehten) Originalraum umgerechnet. Das Basisbild wird als
 * Vorschau mitgegeben — ohne sie blitzte der Tausch schwarz auf
 * (von Thomas beim ersten Pinch bemerkt); bei gedrehten Originalen wird
 * die Vorschau in den unrotierten Quellraum zurueckgedreht, weil SSIV sie
 * derselben EXIF-Drehung unterzieht wie die Quelle.
 */
private fun swapToOriginal(
    view: SubsamplingScaleImageView,
    baseBitmap: android.graphics.Bitmap,
    file: File,
) {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    val origW = opts.outWidth
    val origH = opts.outHeight
    if (origW <= 0 || origH <= 0) return

    val rotation = exifRotation(file)
    val dispW = if (rotation == 90 || rotation == 270) origH else origW
    val dispH = if (rotation == 90 || rotation == 270) origW else origH
    val factor = dispW.toFloat() / baseBitmap.width

    val old = view.state
    val newState = if (old != null) {
        val dx = old.center.x * factor
        val dy = old.center.y * factor
        val (sx, sy) = when (rotation) {
            90 -> dy to (dispW - dx)
            180 -> (dispW - dx) to (dispH - dy)
            270 -> (dispH - dy) to dx
            else -> dx to dy
        }
        ImageViewState(old.scale / factor, PointF(sx, sy), rotation)
    } else null

    val preview = if (rotation == 0) baseBitmap else {
        val m = android.graphics.Matrix().apply { postRotate(-rotation.toFloat()) }
        android.graphics.Bitmap.createBitmap(baseBitmap, 0, 0, baseBitmap.width, baseBitmap.height, m, true)
    }
    val source = ImageSource.uri(Uri.fromFile(file)).dimensions(origW, origH)
    val previewSource = ImageSource.cachedBitmap(preview)

    view.maxScale = 3f // 3 Bildschirmpixel je Originalpixel — mehr zeigt nichts Neues
    if (newState != null) {
        view.setImage(source, previewSource, newState)
    } else {
        view.orientation = rotation
        view.setImage(source, previewSource)
    }
}

/** Klick ohne Ripple — fuers Scrim neben der Sidebar. */

private fun exifRotation(file: File): Int = try {
    when (ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (e: Exception) {
    0
}
