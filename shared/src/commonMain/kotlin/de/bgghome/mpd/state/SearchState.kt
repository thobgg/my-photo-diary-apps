package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/*
 * Modul „search" (GET /api/search) — aus dem ViewModel herausgeloest
 * (08.09.2026, Struktur-Audit Stufe 2, Block 3). Haengt nur an der API;
 * was ein Treffer beim Antippen tut (Album oeffnen, zum Element springen),
 * bleibt im ViewModel, denn dort liegt das offene Album.
 */
class SearchState(
    private val api: MpdApi,
    private val baseUrl: () -> String,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var space by mutableStateOf("all")
    /** null = noch nicht gesucht; leer = keine Treffer. */
    var results by mutableStateOf<List<SearchResult>?>(null)
    var building by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    /** Web-Ctrl+K-Verhalten: 300 ms Debounce, min. 2 Zeichen; bei 202
     *  (Index wird gebaut) hoeflich nachfragen statt aufgeben. */
    fun perform(q: String) {
        query = q
        job?.cancel()
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            results = null; building = false; error = null
            return
        }
        job = scope.launch {
            delay(300)
            try {
                var resp = api.search(baseUrl(), trimmed, space)
                var tries = 0
                while (resp == null && tries < 15) {
                    building = true
                    delay(2000)
                    resp = api.search(baseUrl(), trimmed, space)
                    tries++
                }
                building = false
                error = null
                results = resp?.results ?: emptyList()
            } catch (e: MpdHttpException) {
                building = false
                error = if (e.code == 403 || e.routeMissing) moduleLockedText(Texte.t(Res.string.module_search)) else e.detail
            } catch (e: IOException) {
                building = false
                error = serverUnreachable(e)
            }
        }
    }

    /** Alles, was zu einem Konto gehoert, vergessen (Abmelden, Kontowechsel). */
    fun vergiss() {
        job?.cancel(); job = null
        open = false
        query = ""
        results = null
        building = false
        error = null
    }
}
