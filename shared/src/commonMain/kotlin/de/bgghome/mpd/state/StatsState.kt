package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.StatsResponse
import de.bgghome.mpd.api.TextStats
import de.bgghome.mpd.api.TextYear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/*
 * Modul „stats" (GET /api/stats, /api/stats/text) — aus dem ViewModel
 * herausgeloest (08.09.2026, Struktur-Audit Stufe 2, Block 3). Haengt
 * nur an der API. Der Merge der Text-Statistiken je Space ist reine
 * Rechnung und koennte spaeter einen Test bekommen.
 */
class StatsState(
    private val api: MpdApi,
    private val baseUrl: () -> String,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
    var data by mutableStateOf<StatsResponse?>(null)
    var text by mutableStateOf<TextStats?>(null)
    var scanning by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    /** Shared + Personal additiv zusammenfuehren (wie der Web-Merge). */
    private fun mergeTextStats(parts: List<TextStats>): TextStats? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        fun addL(a: Map<String, Long>, b: Map<String, Long>) =
            (a.keys + b.keys).associateWith { (a[it] ?: 0L) + (b[it] ?: 0L) }
        val perYear = (parts[0].perYear.keys + parts[1].perYear.keys).associateWith { y ->
            TextYear(
                elements = parts.sumOf { it.perYear[y]?.elements ?: 0L },
                words = parts.sumOf { it.perYear[y]?.words ?: 0L },
            )
        }
        return TextStats(
            totalTextElements = parts.sumOf { it.totalTextElements },
            totalWords = parts.sumOf { it.totalWords },
            totalChars = parts.sumOf { it.totalChars },
            totalAlbumsWithText = parts.sumOf { it.totalAlbumsWithText },
            styleCounts = parts.map { it.styleCounts }.reduce(::addL),
            perYear = perYear,
            topByText = parts.flatMap { it.topByText }
                .sortedByDescending { it.elements }.take(10),
            topByScore = parts.flatMap { it.topByScore }
                .sortedByDescending { it.score }.take(10),
        )
    }

    fun load(refresh: Boolean = false) {
        if (job?.isActive == true) return
        if (!refresh && data != null) return
        job = scope.launch {
            error = null
            try {
                var resp = api.stats(baseUrl())
                var tries = 0
                // Erst-Scan grosser Bibliotheken dauert Minuten — Web pollt
                // alle 2 s, wir etwas gemuetlicher.
                while (resp == null && tries < 120) {
                    scanning = true
                    delay(2500)
                    resp = api.stats(baseUrl())
                    tries++
                }
                scanning = false
                data = resp
                if (resp == null) error = Texte.t(Res.string.stats_err_scan_long)
                // Text-Statistiken hinterher (nicht fatal, wenn sie fehlen):
                // je Space ein Paket, die App fuehrt additiv zusammen.
                if (resp != null) {
                    runCatching {
                        val bySpace = api.statsText(baseUrl()).values.toList()
                        text = mergeTextStats(bySpace)
                    }
                }
            } catch (e: MpdHttpException) {
                scanning = false
                error = if (e.code == 403 || e.routeMissing) moduleLockedText("Stats") else e.detail
            } catch (e: IOException) {
                scanning = false
                error = serverUnreachable(e)
            }
        }
    }

    /** Alles, was zu einem Konto gehoert, vergessen (Abmelden, Kontowechsel). */
    fun vergiss() {
        job?.cancel(); job = null
        open = false
        data = null
        text = null
        scanning = false
        error = null
    }
}
