package de.bgghome.mpd

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.MpdHttpException
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import de.bgghome.mpd.api.photosGeo

// Lightbox, Video, PDF, Erinnerungen, Spruenge in Module.
// Erweiterungen von AppViewModel, herausgeloest aus AppViewModel.kt (13.11.2026).

/** „Wo war ich?" — Trails am gegebenen Tag oeffnen. */
fun AppViewModel.openTrailsAt(date: String?) {
    trailsDate = date
    tryOpenModule("trails") { trailsOpen = true }
}

// -- Modul „search" (GET /api/search) -----------------------------------

/** Suchfeld, Bereich, Treffer und die Suche selbst liegen in
 *  state/SearchState.kt; hier bleibt, was ein Treffer beim Antippen
 *  tut — das haengt am offenen Album. */

fun AppViewModel.openAlbumFromSearch(space: String, album: String, elementId: String?) {
    pendingScrollElementId = elementId
    search.open = false
    openAlbum(de.bgghome.mpd.api.AlbumSummary(name = album, space = space))
}

fun AppViewModel.openMemory(photo: de.bgghome.mpd.api.MemoryPhoto, nurFoto: Boolean = true) {
    if (albumLoading) return
    albumAusErinnerung = true
    lightboxZurPinnwand = nurFoto
    // Bis 26.09.2026 stand hier fest "shared": mit der Quelle „Meine
    // Alben" fuehrte jeder Tipp ins Leere.
    openAlbum(AlbumSummary(name = photo.album, space = photo.space), showFile = photo.filename)
}

/** Ein aus der Pinnwand geoeffnetes Album liegt ueber ihr — der Host
 *  zeichnet die Pinnwand dann nicht. */

fun AppViewModel.zeigeErinnerungen(offset: Int = 0) {
    closeAlbum()
    closeTimelineLightbox()
    schliesseVollbildAnsichten()
    memories.open = true
    // Der Morgengruss meint den heutigen Tag, nicht den zuletzt
    // angesehenen: frisch laden statt auf den Vorrat zu vertrauen.
    memories.load(offset)
}

/** Alles, was ueber der Pinnwand liegt, zuklappen. Android haengt den
 *  Geraete-Papierkorb an (Unterklasse). */

fun AppViewModel.bumpEditVersion(file: String) {
    editVersions[file] = System.currentTimeMillis()
}

/**
 * Element [id] vor/hinter [targetId] einsortieren — Drop des Silent
 * Sorting (Web: handleDragEnd). Id-basiert, damit typisierte (sichtbare)
 * und rohe (ungefilterte) Liste konsistent bleiben; die Lightbox-
 * Reihenfolge wird wie im Web aus der neuen Elementfolge neu aufgebaut.
 */

/** Lightbox-Index eines Foto-Elements der Albumansicht, oder null. */
fun AppViewModel.photoIndexOf(elem: de.bgghome.mpd.api.PdxElement): Int? =
    openAlbum?.photoIndexById?.get(elem.id)

fun AppViewModel.openLightbox(photoIndex: Int) {
    lightboxIndex = photoIndex
}

/**
 * Geladene Tour-Daten des offenen Albums, je Element (27.09.2026).
 *
 * Das Album ist eine LazyColumn: Scrollt eine Tour aus dem Bild, wirft
 * Compose ihren `remember`-Zustand weg. Beim Zurueckscrollen lief deshalb
 * alles von vorn — GPX holen und parsen, fuer jedes Foto auf der Spur ein
 * Vorschaubild laden, Karte bauen. Das war das Ruckeln („wenn man durch
 * ein Album am Tablet/Handy scrollt und da Touren eingebaut sind hakelt
 * es"). Der Wert ist plattformeigen (Android: Bitmaps, Desktop: URLs),
 * daher `Any`. Geleert mit dem Album.
 */

suspend fun AppViewModel.fotoOrte(space: String, von: String, bis: String): Map<String, de.bgghome.mpd.util.FotoOrt> {
    val key = "$space|$von|$bis"
    fotoOrteCache[key]?.let { return it }
    val orte = try {
        api.photosGeo(baseUrl, space, von, bis).photos
            .filter { it.lat.isFinite() && it.lon.isFinite() }
            .associate {
                de.bgghome.mpd.util.fotoOrtSchluessel(it.space, it.album, it.file) to
                    de.bgghome.mpd.util.FotoOrt(it.lat, it.lon)
            }
    } catch (e: MpdHttpException) {
        emptyMap()
    } catch (e: IOException) {
        emptyMap()
    }
    fotoOrteCache[key] = orte
    return orte
}

/**
 * Tour-Daten holen — aus [tourDaten] oder per [laden]. Laufen Vorwaermer
 * und Element gleichzeitig an dieselbe Tour, wird nur einmal geladen:
 * der Zweite wartet auf den Lauf des Ersten.
 *
 * [laden] laeuft auf Default (29.09.2026, ANR beim Scrollen): der
 * viewModelScope ist Main, und was der Lader nicht selbst auslagerte,
 * rechnete auf dem UI-Thread — der Vorwaermer gleich fuer alle Touren
 * des Albums hintereinander. Nur der Eintrag in [tourDaten] bleibt auf
 * Main (die Map wird von Composables gelesen).
 */

suspend fun AppViewModel.tourHolen(key: String, laden: suspend () -> Any): Any {
    tourDaten[key]?.let { return it }
    val lauf = tourLaeufe.getOrPut(key) {
        viewModelScope.async {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { laden() }
                .also { tourDaten[key] = it }
        }
    }
    try {
        return lauf.await()
    } finally {
        if (lauf.isCompleted) tourLaeufe.remove(key)
    }
}

fun AppViewModel.closeLightbox() {
    lightboxIndex = null
    // Polaroid auf der Pinnwand: das Album war nur der Traeger des Fotos.
    if (lightboxZurPinnwand) closeAlbum()
}

/** PDF von einer Adresse zeigen (Hilfe: das Benutzerhandbuch vom Server). */
fun AppViewModel.openPdfUrl(title: String, url: String) { openPdf = AppViewModel.OpenPdf(title, url) }

fun AppViewModel.openPdf(elem: de.bgghome.mpd.api.PdxElement) {
    val url = elem.documentUrl?.let { baseUrl + it }
        ?: mediaUrl("document", elem)
        ?: return
    openPdf = AppViewModel.OpenPdf(elem.title ?: elem.file.orEmpty(), url)
}

fun AppViewModel.closePdf() {
    openPdf = null
}

fun AppViewModel.openVideo(elem: de.bgghome.mpd.api.PdxElement) {
    videoUrl = mediaUrl("video", elem)
}

fun AppViewModel.closeVideo() {
    videoUrl = null
}

/** /api/{kind}/{space}/{source_album|album}/{datei} — wie im Web. */
fun AppViewModel.mediaUrl(kind: String, elem: de.bgghome.mpd.api.PdxElement): String? {
    val album = openAlbum ?: return null
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    return base.newBuilder()
        .addPathSegment("api").addPathSegment(kind)
        .addPathSegment(album.summary.space)
        .addPathSegment(elem.sourceAlbum ?: album.summary.name)
        .addPathSegment(elem.file.orEmpty())
        .build().toString()
}
