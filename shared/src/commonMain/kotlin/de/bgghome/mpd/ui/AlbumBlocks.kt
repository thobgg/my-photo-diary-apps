package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import de.bgghome.mpd.api.PdxElement

// Layoutbloecke der Albumansicht: Reihen-Algorithmus (Port von renderAlbum)
// und das Block-Modell. Herausgeloest aus AlbumViewScreen.kt (13.11.2026).

/*
 * Lesende Albumansicht — 1:1-Nachbau des Web-Renderings
 * (frontend/js/album-render.js + style.css, View-Modus).
 *
 * Layout-Regeln des Webs:
 *  - Inhaltsspalte zentriert, max. 1600, Texte max. 1200 breit.
 *  - Fotos sammeln sich in Reihen: Zielhoehe 400 (schmal: 200, 2 Spalten),
 *    Breite je Foto = Zielhoehe x Seitenverhaeltnis; Reihe ist voll, wenn
 *    die Containerbreite (minus 100) erreicht ist. Beim Rendern wird die
 *    Reihe exakt buendig skaliert: h = (Breite - Luecken) / Summe(AR),
 *    gedeckelt bei 1,5-facher Zielhoehe.
 *  - Ein einzelnes Foto direkt vor einem Text (oder am Albumende) wird
 *    gross gerendert: volle Breite, Hoehe 500 (schmal: Breite/AR), xl.
 *  - Andere Elementtypen schliessen die laufende Fotoreihe ab.
 */

// Farbwerte: MpdTheme.kt (eine Quelle fuer alle Screens)

internal const val GAP = 4f
internal const val MOBILE_BREAKPOINT = 768f
internal const val ROW_FULL_SLACK = 100f

// -- Layoutbloecke ----------------------------------------------------------

internal data class RowItem(val elem: PdxElement, val widthDp: Float)

internal sealed interface Block {
    val key: String

    data class PhotoRow(val items: List<RowItem>, val heightDp: Float) : Block {
        override val key = "row-" + items.joinToString("+") { it.elem.id }
    }

    /** heightDp null = schmale Ansicht: Hoehe folgt aus Breite/AR. */
    data class SinglePhoto(val elem: PdxElement, val heightDp: Float?) : Block {
        override val key = "single-" + elem.id
    }

    data class Generic(val elem: PdxElement) : Block {
        override val key = elem.type + "-" + elem.id
    }
}

/** Port der Reihen-Logik aus renderAlbum() — masse in dp statt px. */
internal fun buildBlocks(elements: List<PdxElement>, containerWidth: Float): List<Block> {
    val isMobile = containerWidth <= MOBILE_BREAKPOINT
    val targetRowHeight = if (isMobile) 200f else 400f
    val columnsMobile = 2

    val blocks = mutableListOf<Block>()
    val currentRow = mutableListOf<RowItem>()
    var currentRowWidth = 0f

    fun rowHeight(row: List<RowItem>): Float {
        val sumAr = row.sumOf { it.elem.aspectRatio.toDouble() }.toFloat()
        val totalGap = (row.size - 1) * GAP
        val h = (containerWidth - totalGap) / sumAr
        return if (h > targetRowHeight * 1.5f) targetRowHeight else h
    }

    fun flushRow() {
        if (currentRow.isEmpty()) return
        if (currentRow.size == 1) {
            val elem = currentRow[0].elem
            blocks += Block.SinglePhoto(
                elem,
                heightDp = if (isMobile) containerWidth / elem.aspectRatio else 500f,
            )
        } else {
            val h = rowHeight(currentRow)
            blocks += Block.PhotoRow(
                currentRow.map { RowItem(it.elem, h * it.elem.aspectRatio) },
                heightDp = h,
            )
        }
        currentRow.clear()
        currentRowWidth = 0f
    }

    elements.forEachIndexed { idx, elem ->
        when (elem.type) {
            "photo", "video" -> {
                if (elem.error != null) {
                    blocks += Block.Generic(elem)
                    return@forEachIndexed
                }
                val imageWidth = targetRowHeight * elem.aspectRatio
                currentRow += RowItem(elem, imageWidth)
                currentRowWidth += imageWidth + GAP

                val next = elements.getOrNull(idx + 1)
                val isLastBeforeText = next == null || next.type == "text"
                val rowFull = if (isMobile) currentRow.size >= columnsMobile
                else currentRowWidth >= containerWidth - ROW_FULL_SLACK

                if (isLastBeforeText && currentRow.size == 1) flushRow()
                else if (rowFull) flushRow()
            }

            "text", "separator", "link", "audio", "document", "map", "tour" -> {
                flushRow()
                blocks += Block.Generic(elem)
            }

            // Unbekannte Typen: wie im Web still ueberspringen
            else -> Unit
        }
    }
    flushRow()
    return blocks
}
