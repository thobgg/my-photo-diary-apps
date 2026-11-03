package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/*
 * Passwort aendern — Zustand und Aufruf, aus dem ViewModel herausgeloest
 * (08.09.2026, Struktur-Audit Stufe 2, Block 3). Haengt an nichts ausser
 * der API: der erzwungene Fall (must_change_password) und der
 * freiwillige aus den Einstellungen teilen sich diese Klasse; was danach
 * mit dem App-Zustand passiert (refreshAfterPasswordChange), bleibt im
 * ViewModel, denn dort liegt der Zustand.
 */
class PasswordChangeState(
    private val api: MpdApi,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    /** Aus den Einstellungen geoeffnet (freiwilliger Wechsel, mit Zurueck). */
    var open by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf(false)

    /** Mindestlaenge — dieselbe Schwelle, die das Backend erzwingt (400). */
    val minLength = 12

    /**
     * Wechselt das Passwort. [onDone] laeuft nur bei Erfolg; im erzwungenen
     * Fall setzt der Aufrufer damit den Zustand zurueck auf LoggedIn.
     *
     * Die Laengen- und Gleichheitspruefung passiert schon hier, damit der
     * Nutzer die Antwort sofort bekommt statt nach einem Serverlauf — der
     * Server prueft aus gutem Grund trotzdem selbst.
     */
    fun change(oldPassword: String, newPassword: String, onDone: () -> Unit) {
        val base = settings.baseUrl ?: return
        error = when {
            oldPassword.isEmpty() -> Texte.t(Res.string.pw_err_current_empty)
            newPassword.length < minLength ->
                Texte.t(Res.string.pw_err_too_short, minLength)
            newPassword == oldPassword -> Texte.t(Res.string.pw_err_same)
            else -> null
        }
        if (error != null) return

        scope.launch {
            busy = true
            try {
                api.changePassword(base, oldPassword, newPassword)
                done = true
                onDone()
            } catch (e: MpdHttpException) {
                error = when (e.code) {
                    401 -> Texte.t(Res.string.pw_err_wrong_current)
                    429 -> Texte.t(Res.string.pw_err_too_many)
                    else -> e.detail
                }
            } catch (e: IOException) {
                error = serverUnreachable(e)
            } finally {
                busy = false
            }
        }
    }
}
