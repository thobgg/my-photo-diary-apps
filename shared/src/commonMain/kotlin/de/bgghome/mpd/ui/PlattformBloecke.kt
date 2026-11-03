package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.PdxElement

/*
 * Album-Elemente, die auf Android eine eigene Bibliothek brauchen
 * (osmdroid fuer Karten, Media3 fuer Ton) und auf dem Desktop noch
 * keinen Ersatz haben (Aufteilung 22.09.2026). Der Desktop zeigt an
 * ihrer Stelle einen Hinweis; die Elemente bleiben im Album erhalten.
 */

/** Karte mit Pins (map-Element). */
@Composable
expect fun MapElementBlock(elem: PdxElement)

/** Tour: GPX-Spur mit Fotomarkern und Hoehenprofil (tour-Element). */
@Composable
expect fun TourElementBlock(vm: AppViewModel, elem: PdxElement, imageLoader: ImageLoader)

/**
 * Spur und Foto-Nadeln einer Tour laden (27.09.2026). Eine Funktion fuer
 * das Element selbst und fuer den Vorwaermer in [AlbumViewScreen], damit
 * beide dasselbe Ergebnis in [AppViewModel.tourDaten] legen. Der Wert ist
 * plattformeigen (Android: Bitmaps, Desktop: URLs).
 * Wirft [TourOhnePunkte], wenn die Datei keine Punkte hat.
 */
internal expect suspend fun ladeTour(
    vm: AppViewModel,
    album: de.bgghome.mpd.OpenAlbum,
    elem: PdxElement,
    imageLoader: ImageLoader,
    context: coil3.PlatformContext,
): Any

/** GPX gelesen, aber ohne einen einzigen Punkt. */
internal class TourOhnePunkte : Exception()

/** Schluessel in [AppViewModel.tourDaten]. Mit Fotozahl: Kommt im
 *  Bearbeiten ein Foto dazu, sollen seine Nadeln erscheinen. */
internal fun tourSchluessel(album: de.bgghome.mpd.OpenAlbum, elem: PdxElement) =
    "${album.summary.space}|${album.summary.name}|${elem.id}|${elem.sourceAlbum}|${elem.file}|${album.photos.size}"

/** Tonaufnahme mit Abspielknopf (audio-Element). */
@Composable
expect fun AudioElementBlock(vm: AppViewModel, elem: PdxElement)
