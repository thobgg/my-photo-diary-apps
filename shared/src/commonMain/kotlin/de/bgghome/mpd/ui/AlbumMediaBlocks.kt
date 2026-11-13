package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.moveElement
import de.bgghome.mpd.openLightbox
import de.bgghome.mpd.openVideo
import de.bgghome.mpd.photoIndexOf

// Foto- und Videokacheln der Albumansicht (Reihe, Einzelbild, Fehlerkasten).
// Herausgeloest aus AlbumViewScreen.kt (13.11.2026).

// -- Fotos ------------------------------------------------------------------

@Composable
internal fun PhotoRowBlock(
    vm: AppViewModel,
    row: Block.PhotoRow,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = GAP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP.dp, Alignment.CenterHorizontally),
    ) {
        row.items.forEach { item ->
            DragTileBox(
                drag, item.elem,
                enabled = vm.isEditMode,
                horizontal = true,
                onDrop = vm::moveElement,
                modifier = Modifier.width(item.widthDp.dp).height(row.heightDp.dp),
            ) {
                PhotoThumb(vm, item.elem, imageLoader, Modifier.fillMaxSize(), useXl = false)
                EditElementButtons(vm, item.elem, onRemove)
            }
        }
    }
}

@Composable
internal fun SinglePhotoBlock(
    vm: AppViewModel,
    block: Block.SinglePhoto,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    val heightMod = block.heightDp?.let { Modifier.height(it.dp) } ?: Modifier
    DragTileBox(
        drag, block.elem,
        enabled = vm.isEditMode,
        horizontal = true,
        onDrop = vm::moveElement,
        modifier = Modifier.fillMaxWidth().then(heightMod).padding(bottom = GAP.dp),
    ) {
        PhotoThumb(vm, block.elem, imageLoader, Modifier.fillMaxSize(), useXl = true)
        EditElementButtons(vm, block.elem, onRemove)
    }
}

@Composable
internal fun PhotoThumb(
    vm: AppViewModel,
    elem: PdxElement,
    imageLoader: ImageLoader,
    modifier: Modifier,
    useXl: Boolean,
) {
    val isVideo = elem.type == "video"
    // Titelbild eines Videos (05.11.2026: „ein Titelbild, kein black
    // screen"): Die Album-Antwort traegt auch fuer Videos /api/thumbnail-
    // Adressen, und die antworten bei Videos mit 415 — die Kachel blieb
    // schwarz. Das Web faellt auf /api/video-thumb zurueck (album-render.js,
    // source_album || albumName); der Zeitstrahl nimmt ihn direkt. Hier
    // genauso, aufgeloest auf den Quellordner bei referenzierten Videos.
    val url = if (isVideo) {
        val summary = vm.openAlbum?.summary ?: return
        val file = elem.file ?: return
        vm.baseUrl + MpdApi.videoThumbPath(summary.space, elem.sourceAlbum ?: summary.name, file)
    } else {
        val thumbs = elem.thumbnails ?: return
        vm.baseUrl + if (useXl) thumbs.xl else thumbs.m
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            // Im Edit-Modus oeffnet der Foto-Tipp nicht die Lightbox —
            // dort liegt seit 07.09.2026 das Kachelmenue darauf
            // (EditElementButtons). Im Web tut der Tipp dort gar nichts;
            // dass er hier etwas tut, ist eine Android-Zutat.
            .clickable(enabled = !vm.isEditMode) {
                if (isVideo) {
                    vm.openVideo(elem)
                } else {
                    vm.photoIndexOf(elem)?.let { vm.openLightbox(it) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = elem.file,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MpdIcons.Play, stringResource(Res.string.video), Modifier.size(26.dp), tint = Color.White)
            }
        }
        // Aufnahmedatum-Badge im Edit-Modus (Web photo-date-badge,
        // _dateFromFilename: nur ISO-Reihenfolge am Namensanfang).
        if (vm.isEditMode) {
            dateFromFilename(elem.file)?.let { capture ->
                // Unten links (seit 08.09.2026 — unten rechts sitzt wieder
                // der Papierkorb, Entscheidung; das Web hat das
                // Datum unten rechts, edit-photo.css:106). Bewusst anders
                // gesetzt als die Knoepfe: Schreibmaschinenschrift,
                // gedaempft, kein Kreis. Es ist Auskunft, kein Knopf.
                Text(
                    capture,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Port von _dateFromFilename (album-render.js): lieber kein Badge als ein
 *  falsches — nur JJJJ-MM-TT(-hh-mm) am Namensanfang, optional Kamera-Praefix. */
internal val DATE_RE = Regex(
    """^(?:(?:IMG|VID|PXL|DSC|MVIMG|Screenshot|WhatsApp)[-_ ]?)?(\d{4})[-_]?(\d{2})[-_]?(\d{2})(?:[-_T ]?(\d{2})[-_:]?(\d{2}))?""",
)

internal fun dateFromFilename(fname: String?): String? {
    val m = DATE_RE.find(fname ?: return null) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val yn = y.toInt()
    if (yn < 1990 || yn > 2100) return null
    if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
    val base = "$d.$mo.$y"
    return if (h.isNotEmpty() && mi.isNotEmpty()) "$base $h:$mi" else base
}

// -- Uebrige Elementtypen ---------------------------------------------------

@Composable
internal fun GenericBlock(
    vm: AppViewModel,
    elem: PdxElement,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    DragTileBox(
        drag, elem,
        // Offener Editor bleibt tippbar — kein Long-Press-Drag auf ihm.
        enabled = vm.isEditMode && vm.editingElementId != elem.id,
        // Bewusste Abweichung vom Web: dort bekommen auch volle-Breite-
        // Elemente (Trenner, Audio …) den Links/Rechts-Indikator der Fotos;
        // hier zeigen alle Nicht-Foto-Elemente darueber/darunter — bei
        // voller Breite ist „links von der Mitte = davor" nicht zu treffen.
        horizontal = false,
        onDrop = vm::moveElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            elem.error != null -> MissingBlock(elem)
            elem.type == "text" -> TextBlock(vm, elem)
            elem.type == "separator" -> SeparatorBlock(vm, elem)
            elem.type == "link" -> LinkBlock(vm, elem)
            elem.type == "document" -> DocumentBlock(vm, elem, imageLoader)
            elem.type == "audio" -> AudioElementBlock(vm, elem)
            elem.type == "map" -> MapElementBlock(elem)
            elem.type == "tour" -> TourElementBlock(vm, elem, imageLoader)
        }
        EditElementButtons(vm, elem, onRemove)
    }
}

@Composable
internal fun MissingBlock(elem: PdxElement) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, MpdBorder, RoundedCornerShape(4.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("${elem.file ?: elem.type}: ${elem.error}", color = MpdRed, fontSize = 14.sp)
    }
}
