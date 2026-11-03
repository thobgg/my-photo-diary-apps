package de.bgghome.mpd.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/*
 * Markdown-light (PDX v1.5): ausschliesslich *kursiv* und **fett**, kein
 * HTML, KEINE Verschachtelung (Spec §text). Gerendert wird NUR in der
 * Ansicht — der Editor zeigt und speichert den Rohtext mit Sternchen.
 *
 * Die Tokenisierung ist Zeichen fuer Zeichen dieselbe wie in der
 * Referenz mpdRenderInlineMarkup (album-render.js):
 *   split(/(\*\*[^*]+\*\*|\*[^*\n]+\*)/g)
 * D.h.: fett-Inhalt ohne Sternchen (darf Zeilenumbrueche enthalten),
 * kursiv-Inhalt ohne Sternchen und ohne Zeilenumbruch; alles andere —
 * einsame, leere oder unvollstaendige Marker — bleibt woertlich stehen.
 * Nur so rendern Web und App denselben Text identisch.
 */

private val MD_TOKEN = Regex("""\*\*[^*]+\*\*|\*[^*\n]+\*""")

internal fun mdLight(src: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in MD_TOKEN.findAll(src)) {
        append(src.substring(last, m.range.first))
        val token = m.value
        if (token.startsWith("**")) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.substring(2, token.length - 2))
            }
        } else {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
        }
        last = m.range.last + 1
    }
    append(src.substring(last))
}
