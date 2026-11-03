package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.ExifData
import de.bgghome.mpd.api.PhotoEditParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// -- Foto-Editor -------------------------------------------------------------

@Composable
internal fun PhotoEditorOverlay(
    vm: AppViewModel,
    photo: Photo,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Geraete-Aufnahme: das Schreiben braucht Androids Zustimmung (MediaStore).
    LokalesSchreiben(vm, onClose)
    val dienste = LocalSystemDienste.current
    val cMuted = MpdMuted // Theme-Read fuer Status-Setzungen in Callbacks

    val context = coil3.compose.LocalPlatformContext.current
    var resetConfirm by remember { mutableStateOf(false) }
    var rotation by remember { mutableStateOf(0) }

    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var straighten by remember { mutableStateOf(0f) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }

    // Zuschnitt: cropRect = uebernommener Rahmen (normiert), cropMode = Rahmen offen
    var cropMode by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var cropDraft by remember { mutableStateOf(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f)) }
    var srcBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    var cropError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cropMode) {
        if (cropMode && srcBitmap == null) {
            // GROESSE BEGRENZEN (11.09.2026: „das Schneiden von
            // gemachten Fotos, die noch nicht hochgeladen sind, geht
            // nicht"). Bei einer Server-Datei ist xlUrl die 2048er-Variante;
            // bei einer GERAETE-Aufnahme ist es das Original — 12 MP sind
            // rund 48 MB als Bitmap, und ohne Deckel scheiterte das Laden
            // still. Sichtbar war nur ein Ladekreis, der nie aufhoerte
            // (CropEditor bei bitmap == null). Fuer den Rahmen genuegen
            // 2048 px: die CropBox ist relativ (0..1), die Genauigkeit
            // haengt nicht an der Vorschau.
            cropError = null
            val result = imageLoader.execute(
                coil3.request.ImageRequest.Builder(context)
                    .data(photo.xlUrl)
                    .size(2048)
                    .softwareBitmap()
                    .build(),
            )
            val bmp = result.image?.zuImageBitmap()
            if (bmp != null) {
                srcBitmap = bmp
            } else {
                // Und wenn es doch scheitert, sagt es das, statt ewig zu
                // drehen — der Fehler war vorher unsichtbar.
                cropError = (result as? coil3.request.ErrorResult)?.throwable?.let {
                    Texte.t(Res.string.crop_load_failed_reason, it.javaClass.simpleName)
                } ?: Texte.t(Res.string.crop_load_failed)
            }
        }
    }

    // Vorschau-Filter wie die CSS-filter des Webs: saturate → contrast → brightness
    val colorFilter = remember(brightness, contrast, saturation) {
        val sat = ColorMatrix().apply { setToSaturation(1f + saturation / 100f) }
        val c = 1f + contrast / 100f
        val b = 1f + brightness / 100f
        val cb = ColorMatrix(
            floatArrayOf(
                c * b, 0f, 0f, 0f, (0.5f - 0.5f * c) * 255f * b,
                0f, c * b, 0f, 0f, (0.5f - 0.5f * c) * 255f * b,
                0f, 0f, c * b, 0f, (0.5f - 0.5f * c) * 255f * b,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        cb.timesAssign(sat)
        ColorFilter.colorMatrix(cb)
    }

    fun save() {
        val params = PhotoEditParams(
            rotation = ((rotation % 360) + 360) % 360,
            flip = flipH, flipV = flipV,
            straighten = straighten,
            brightness = brightness.roundToInt(),
            contrast = contrast.roundToInt(),
            saturation = saturation.roundToInt(),
            crop = cropRect?.let {
                de.bgghome.mpd.api.CropBox(it.left, it.top, it.width, it.height)
            },
        )
        if (params.isNoOp) {
            status = Texte.t(Res.string.editor_no_changes) to cMuted
            scope.launch { delay(1200); onClose() }
            return
        }
        busy = true
        status = Texte.t(Res.string.status_saving) to cMuted
        if (photo.isLocal) {
            // Geraete-Aufnahme: gerechnet wird hier, geschrieben erst nach
            // Androids Rueckfrage. Es gibt KEINE .bak wie beim Server —
            // deshalb sagt es die Rueckfrage vorher.
            dienste.bearbeiteLokal(vm, photo, params) { msg ->
                status = msg to MpdRed
                busy = false
            }
            return
        }
        scope.launch {
            try {
                val backup = vm.api.editPhoto(
                    vm.baseUrl, photo.space, photo.album, photo.file, params,
                )
                vm.bumpEditVersion(photo.file)
                status = (backup?.let { Texte.t(Res.string.status_saved_backup, it) }
                    ?: Texte.t(Res.string.status_saved)) to MpdGreen
                delay(900)
                onClose()
            } catch (e: Exception) {
                status = (e.message ?: Texte.t(Res.string.status_save_error)) to MpdRed
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (cropMode) {
                // Rahmen auf dem vortransformierten Bild — gleiche Kette wie
                // der Server (rotate → flips → straighten), s. PhotoCrop.kt
                val previewBmp = remember(srcBitmap, rotation, flipH, flipV, straighten) {
                    srcBitmap?.let { cropPreviewBitmap(it, rotation, flipH, flipV, straighten) }
                }
                if (cropError != null) {
                    Text(
                        cropError!!, color = MpdRed, fontSize = 14.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    CropEditor(
                        bitmap = previewBmp,
                        crop = cropDraft,
                        onCropChange = { cropDraft = it },
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
            } else {
                // Vorschau: xl reicht — der Server rechnet auf dem Original
                AsyncImage(
                    model = photo.xlUrl,
                    imageLoader = imageLoader,
                    contentDescription = photo.file,
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer {
                            rotationZ = rotation + straighten
                            scaleX = if (flipH) -1f else 1f
                            scaleY = if (flipV) -1f else 1f
                        },
                )
            }
        }

        if (cropMode) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xF2141414))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { cropDraft = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f) }) {
                        Text(stringResource(Res.string.crop_whole), color = MpdMuted)
                    }
                    TextButton(onClick = { cropMode = false }) {
                        Text(stringResource(Res.string.common_cancel), color = MpdMuted)
                    }
                    TextButton(onClick = {
                        // Voller Rahmen = kein Zuschnitt
                        cropRect = cropDraft.takeIf {
                            it.left > 0.001f || it.top > 0.001f ||
                                it.right < 0.999f || it.bottom < 0.999f
                        }
                        cropMode = false
                    }) { Text(stringResource(Res.string.crop_apply), color = MpdBlue) }
                }
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF2141414))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(MpdIcons.RotateCw, stringResource(Res.string.editor_rotate), Modifier.size(22.dp), tint = MpdText)
                }
                IconButton(onClick = { flipH = !flipH }) {
                    Icon(
                        MpdIcons.FlipH, stringResource(Res.string.editor_flip_h), Modifier.size(22.dp),
                        tint = if (flipH) MpdBlue else MpdText,
                    )
                }
                IconButton(onClick = { flipV = !flipV }) {
                    Icon(
                        MpdIcons.FlipV, stringResource(Res.string.editor_flip_v), Modifier.size(22.dp),
                        tint = if (flipV) MpdBlue else MpdText,
                    )
                }
                IconButton(onClick = {
                    cropDraft = cropRect ?: androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f)
                    cropMode = true
                }) {
                    Icon(
                        MpdIcons.Crop, stringResource(Res.string.editor_crop), Modifier.size(22.dp),
                        tint = if (cropRect != null) MpdBlue else MpdText,
                    )
                }
            }
            if (cropRect != null) {
                Text(
                    stringResource(Res.string.editor_crop_active),
                    color = MpdGoldLt, fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { cropRect = null }
                        .padding(vertical = 2.dp),
                )
            }

            @Composable
            fun slider(
                label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                display: String, onChange: (Float) -> Unit,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = MpdMuted, fontSize = 12.sp, modifier = Modifier.width(92.dp))
                    Slider(
                        value = value,
                        onValueChange = onChange,
                        valueRange = range,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MpdBlue,
                            activeTrackColor = MpdBlue,
                            inactiveTrackColor = MpdBorder,
                        ),
                    )
                    Text(
                        display, color = MpdText, fontSize = 12.sp,
                        modifier = Modifier.width(46.dp),
                    )
                }
            }

            // Tonwerte gibt es seit 07.09.2026 auch fuer Geraete-Aufnahmen.
            // Die alte Sperre stand hier mit der Begruendung, Pillow und
            // Androids ColorMatrix rechneten verschieden — das stimmte,
            // solange man Androids fertige Bausteine nahm. Nachgebaut ist
            // jetzt Pillows Rechnung selbst (data/Tonwerte.kt).
            slider(stringResource(Res.string.editor_brightness), brightness, -100f..100f, "${brightness.roundToInt()}") { brightness = it }
            slider(stringResource(Res.string.editor_contrast), contrast, -100f..100f, "${contrast.roundToInt()}") { contrast = it }
            slider(stringResource(Res.string.editor_saturation), saturation, -100f..100f, "${saturation.roundToInt()}") { saturation = it }
            if (photo.isLocal) {
                Text(
                    stringResource(Res.string.editor_local_hint),
                    color = MpdGoldLt, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            slider(
                stringResource(Res.string.editor_straighten), straighten, -45f..45f,
                "%.1f°".format(straighten),
            ) { straighten = (it * 2).roundToInt() / 2f } // Schrittweite 0,5 wie im Web

            status?.let { (text, color) ->
                Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // DELETE /edit stellt das .bak-Original wieder her — die
                // API-Funktion (resetPhoto) gab es laengst, nur der Knopf
                // fehlte. Mit Rueckfrage, weil destruktiv fuers Edit.
                // Nur fuer Server-Dateien: der Knopf ruft DELETE /edit und
                // holt die .bak zurueck. Bei einer Geraete-Aufnahme gibt es
                // weder das eine noch das andere — der Knopf stand hier
                // trotzdem und waere in einen Fehler gelaufen.
                if (!photo.isLocal) {
                    TextButton(onClick = { resetConfirm = true }, enabled = !busy) {
                        Text(stringResource(Res.string.editor_reset), color = MpdGoldLt, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !busy) {
                    Text(stringResource(Res.string.common_cancel), color = MpdMuted)
                }
                TextButton(onClick = { save() }, enabled = !busy) {
                    Text(stringResource(Res.string.common_save), color = MpdBlue)
                }
            }
        }
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text(stringResource(Res.string.editor_reset_title)) },
            text = {
                Text(
                    stringResource(Res.string.editor_reset_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    busy = true
                    status = Texte.t(Res.string.status_resetting) to cMuted
                    scope.launch {
                        try {
                            vm.api.resetPhoto(vm.baseUrl, photo.space, photo.album, photo.file)
                            vm.bumpEditVersion(photo.file)
                            status = Texte.t(Res.string.status_reset_done) to MpdGreen
                            delay(900)
                            onClose()
                        } catch (e: Exception) {
                            status = (e.message ?: Texte.t(Res.string.status_error)) to MpdRed
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.restore_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}
