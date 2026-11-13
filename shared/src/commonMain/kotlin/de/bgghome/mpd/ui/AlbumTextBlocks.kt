package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource

// Text-, Trenner- und Link-Bloecke der Albumansicht.
// Herausgeloest aus AlbumViewScreen.kt (13.11.2026).

@Composable
internal fun TextBlock(vm: AppViewModel, elem: PdxElement) {
    // In Zeichen-Lambdas (drawBehind) sind Composable-Reads nicht erlaubt →
    // Theme-Farben hier in den Composable-Scope hochziehen.
    val accent = MpdBlue
    val borderColor = MpdBorder
    if (vm.isEditMode && vm.editingElementId == elem.id) {
        TextBlockEditor(vm, elem)
        return
    }
    if (vm.isEditMode) {
        // Editierbar markiert (gestrichelte Kontur wie .editable im Web);
        // Tipp oeffnet den Editor. Leere Texte bekommen einen Platzhalter,
        // sonst gaebe es nichts zum Antippen.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .drawBehind {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.5f),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                }
                .clickable { vm.startTextEdit(elem.id) },
        ) {
            if (elem.text.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.text_placeholder), color = MpdMuted, fontSize = 16.sp, fontStyle = FontStyle.Italic)
                }
            } else {
                TextBlockContent(elem, showSource = false)
            }
            // Stilwahl (Web: style-select-btn) — unten rechts, unter dem ×
            TextStyleButton(vm, elem, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        return
    }
    TextBlockContent(elem)
}

@Composable
internal fun TextBlockContent(elem: PdxElement, showSource: Boolean = true) {
    // Markdown-light (v1.5) nur in der Ansicht — der Editor bleibt Rohtext.
    val text = mdLight(elem.text.orEmpty())
    val borderColor = MpdBorder // fuer drawBehind (kein Composable-Read im Zeichen-Lambda)
    when (elem.style) {
        "info" -> StyledTextBox(
            text,
            borderStart = 3.dp to MpdBlue,
            background = MpdBluePale,
            color = MpdText,
        )

        "quote" -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .drawBehind {
                    // Web: border-top + border-bottom 1px var(--border)
                    drawLine(borderColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text("“", color = MpdMuted, fontSize = 44.sp)
            Text(
                text,
                color = MpdText,
                fontSize = 18.sp,
                lineHeight = 30.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // Quellenangabe (v1.5) — Web: „— Quelle" klein unterm Zitat,
            // nur in der Ansicht
            if (showSource && !elem.source.isNullOrBlank()) {
                Text(
                    stringResource(Res.string.quote_source, elem.source),
                    color = MpdMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        "note" -> StyledTextBox(
            text,
            borderStart = 4.dp to MpdGold,
            background = MpdGoldBg,
            color = MpdGoldLt,
        )

        // heading (v1.5): Kapitel-Ueberschrift in Gold, linksbuendig
        // (Web: .text-style-heading, 1.7rem/700)
        "heading" -> Text(
            text,
            color = MpdGold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 4.dp),
        )

        // default: zentriert, lesefreundlich bis 1200
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = MpdText,
                fontSize = 16.sp,
                lineHeight = 29.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun StyledTextBox(
    text: androidx.compose.ui.text.AnnotatedString,
    borderStart: Pair<androidx.compose.ui.unit.Dp, Color>,
    background: Color,
    color: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .height(IntrinsicSize.Min)
            .background(background, RoundedCornerShape(2.dp)),
    ) {
        // Web: border-left in Akzentfarbe
        Box(
            Modifier
                .width(borderStart.first)
                .fillMaxHeight()
                .background(borderStart.second),
        )
        Text(
            text,
            color = color,
            fontSize = 16.sp,
            lineHeight = 29.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
internal fun SeparatorBlock(vm: AppViewModel, elem: PdxElement) {
    if (vm.isEditMode) {
        SeparatorEditRow(vm, elem)
        return
    }
    val style = elem.style ?: "line"
    if (style == "space") {
        Spacer(Modifier.height(40.dp))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeparatorLine(style, Modifier.weight(1f))
        if (style == "dots" && elem.label == null) {
            Text("· · ·", color = MpdBlue.copy(alpha = 0.55f), fontSize = 20.sp, letterSpacing = 8.sp)
        }
        elem.label?.let {
            Text(
                it.uppercase(),
                color = MpdMuted,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        SeparatorLine(style, Modifier.weight(1f))
    }
}

@Composable
internal fun LinkBlock(vm: AppViewModel, elem: PdxElement) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val host = remember(elem.url) {
        runCatching { java.net.URI(elem.url).host?.removePrefix("www.") }.getOrNull() ?: elem.url.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(23.dp))
                .background(MpdSurface)
                .border(1.dp, MpdBorder, RoundedCornerShape(23.dp))
                .clickable {
                    if (vm.isEditMode) {
                        // Web: link-edit-btn ✎ — hier oeffnet der Chip selbst
                        // den Bearbeiten-Dialog, im Edit-Modus browst niemand.
                        vm.editingLinkId = elem.id
                    } else {
                        elem.url?.let {
                            runCatching { uriHandler.openUri(it) }
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MpdIcons.Link, null, Modifier.padding(end = 10.dp).size(18.dp), tint = MpdText)
            Column {
                Text(elem.title ?: host, color = MpdText, fontSize = 15.sp)
                if (elem.title != null) Text(host, color = MpdMuted, fontSize = 12.sp)
            }
        }
        elem.note?.let {
            Text(it, color = MpdMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
