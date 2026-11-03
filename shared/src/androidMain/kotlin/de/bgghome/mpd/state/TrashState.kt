package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.data.LocalMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Papierkorb des Geraets: ansehen, zurueckholen, endgueltig weg.
 *
 * Gebaut am 07.09.2026. Vorher konnte die App etwas in den Papierkorb
 * legen, ihn aber nicht zeigen — wer es zurueckwollte, musste MPD
 * verlassen und in Files oder der Samsung-Galerie suchen. Das ist genau
 * der Grund, aus dem man die andere App liegen laesst.
 *
 * Die NAS-Haelfte fehlt bewusst: der Server kann seine eigenen
 * Loeschungen im DSM-Korb nicht von fremden unterscheiden, dafuer
 * braeuchte es ein Loesch-Journal. Statt der Ansicht ist dort ein
 * „Rueckgaengig" vorgesehen — bei der Server-Seite angefragt, nicht gebaut.
 *
 * Aus dem ViewModel herausgeloest (08.09.2026, Struktur-Audit Stufe 2,
 * Block 3). Zwei Dinge gehoeren dem ViewModel und kommen als Lambdas:
 * die Rueckmeldung an den Nutzer ([onStatus] → curateStatus) und das
 * Nachladen des Zeitstrahls, wenn eine Aufnahme zurueck im Bestand ist
 * ([onMediaChanged] → loadLocalMedia).
 */
class TrashState(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onMediaChanged: () -> Unit,
) {
    var open by mutableStateOf(false)

    /** null = noch nicht geladen. Leer = wirklich leer. */
    var items by mutableStateOf<List<LocalMedia.Trashed>?>(null)
        private set

    /** Ausgewaehlte content://-Adressen in der Papierkorb-Ansicht. */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    fun show() {
        open = true
        selection = emptySet()
        // Kein load() hier: der Screen laedt selbst (LaunchedEffect).
        // Beides zusammen ergab zwei vollstaendige MediaStore-Abfragen im
        // Abstand von 100 ms — am 07.09.2026 im Log gesehen, als die
        // Zaehlzeile doppelt dastand. Stattdessen den alten Stand
        // wegwerfen, damit der Ladekreis erscheint statt der Liste von
        // vorletzter Woche.
        items = null
    }

    fun hide() {
        open = false
        selection = emptySet()
    }

    fun load() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { LocalMedia.trashed(app) }
            items = found
            // Was nicht mehr da ist, kann auch nicht ausgewaehlt bleiben.
            val vorhanden = found.map { it.uri }.toSet()
            selection = selection intersect vorhanden
        }
    }

    fun toggleSelection(uri: String) {
        selection = if (uri in selection) selection - uri else selection + uri
    }

    fun selectAll() {
        selection = items?.map { it.uri }?.toSet() ?: emptySet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /**
     * content://-Adressen, die aus dem Papierkorb zurueck sollen, und
     * solche, die endgueltig weg sollen. Beides sind Schreibzugriffe auf
     * fremde Mediendateien — den Dialog stellt Android, die UI holt die
     * Listen hier ab (wie bei pendingTrash im ViewModel).
     */
    var pendingUntrash by mutableStateOf<List<String>>(emptyList())
    var pendingErase by mutableStateOf<List<String>>(emptyList())

    fun requestUntrash() {
        if (selection.isEmpty()) return
        pendingUntrash = selection.toList()
    }

    fun requestErase() {
        if (selection.isEmpty()) return
        pendingErase = selection.toList()
    }

    fun untrashFinished(ok: Boolean, count: Int) {
        pendingUntrash = emptyList()
        if (ok) {
            onStatus(Texte.plural(Res.plurals.trash_restored_n, count))
            selection = emptySet()
            load()
            // Die Aufnahme ist wieder im Bestand — der Zeitstrahl muss es
            // mitbekommen, sonst taucht sie erst beim naechsten Start auf.
            onMediaChanged()
        }
    }

    fun eraseFinished(ok: Boolean, count: Int) {
        pendingErase = emptyList()
        if (ok) {
            onStatus(Texte.plural(Res.plurals.trash_erased_n, count))
            selection = emptySet()
            load()
        }
    }
}
