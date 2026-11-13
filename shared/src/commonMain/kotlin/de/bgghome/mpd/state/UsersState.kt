package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.AdminUsersResponse
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import de.bgghome.mpd.api.adminUsers

/*
 * Benutzerverwaltung (admin-only, Web: „Benutzer"-Modal) — aus dem
 * ViewModel herausgeloest (08.09.2026, Struktur-Audit Stufe 2, Block 3).
 * Schutzregeln (sich selbst, letzter Admin) prueft der Server; hier liegt
 * nur die Liste und der Aufruf-Rahmen.
 */
class UsersState(
    private val api: MpdApi,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
    var list by mutableStateOf<AdminUsersResponse?>(null)

    fun load() {
        val base = settings.baseUrl ?: return
        scope.launch {
            runCatching { api.adminUsers(base) }.onSuccess { list = it }
        }
    }

    /** Aktion ausfuehren, danach Liste neu laden; onDone(null) = ok. */
    fun op(op: suspend (String) -> Unit, onDone: (String?) -> Unit = {}) {
        val base = settings.baseUrl ?: return
        scope.launch {
            try {
                op(base)
                list = api.adminUsers(base)
                onDone(null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onDone(e.message ?: Texte.t(Res.string.err_generic))
            }
        }
    }

    /** Alles, was zu einem Konto gehoert, vergessen (Abmelden, Kontowechsel). */
    fun vergiss() {
        open = false
        list = null
    }
}
