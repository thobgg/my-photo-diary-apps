package de.bgghome.mpd

import de.bgghome.mpd.shared.res.*

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.state.serverUnreachable
import java.io.IOException
import kotlinx.coroutines.launch
import de.bgghome.mpd.api.uploadFile

/**
 * Android-Teil des ViewModels (Aufteilung 22.09.2026): alles, was Context,
 * Uri oder die Android-Zustandsklassen (Backup, Papierkorb) braucht. Die
 * Bildschirme, die davon abhaengen (Einstellungen, Papierkorb, Teilen,
 * lokales Bearbeiten, „Oeffnen mit"), nehmen diese Klasse; alle anderen
 * den gemeinsamen AppViewModel.
 */
class AndroidAppViewModel(app: Application) : AppViewModel(AndroidPlattform(app)) {

    override fun zeigePapierkorb() = trash.show()

    /** Der Geraete-Papierkorb liegt nur hier — er gehoert mit zugeklappt,
     *  wenn die Erinnerungen nach vorn kommen. */
    override fun schliesseVollbildAnsichten() {
        super.schliesseVollbildAnsichten()
        trash.open = false
    }

    /** Schalter und Ordnerwahl liegen in state/BackupState.kt; das Anstossen
     *  eines Laufs nach dem Einschalten bleibt hier (kickUpload haengt am
     *  Zeitstrahl) und geht als Lambda hinein. */
    val backup = de.bgghome.mpd.state.BackupState(
        app, settings, onEnabled = { kickUpload() }, onBuckets = { backupBuckets = it },
    )

    /** Liste, Auswahl und die Android-Rueckfragen liegen in
     *  state/TrashState.kt; Rueckmeldung und Zeitstrahl-Nachladen kommen
     *  von hier als Lambdas. */
    val trash = de.bgghome.mpd.state.TrashState(
        app, viewModelScope,
        onStatus = { curateStatus = it },
        onMediaChanged = { loadLocalMedia() },
    )

    /** Hereingereichte Aufnahmen, die auf ihre Behandlung warten. */
    var incoming by mutableStateOf<de.bgghome.mpd.data.IncomingMedia.Incoming?>(null)
    var incomingBusy by mutableStateOf(false)
        private set

    /**
     * „Öffnen mit MPD": das Bild sofort im Vollbild zeigen, nicht in einem
     * Dialog ankuendigen. Wer eine Galerie waehlt, will das Bild sehen.
     * Es laeuft ueber denselben Weg wie Geraete-Aufnahmen im Zeitstrahl —
     * eine Photo mit content://-Adresse, isLocal.
     */
    fun viewIncoming(context: android.content.Context, uri: android.net.Uri) {
        val name = de.bgghome.mpd.data.IncomingMedia.displayName(context, uri)
        timelineLightbox = listOf(
            Photo(
                file = name, space = "", album = "",
                mUrl = uri.toString(), xlUrl = uri.toString(),
                fullUrl = uri.toString(), originalUrl = uri.toString(),
                referenced = false, isLocal = true,
            ),
        ) to 0
        incoming = null
    }

    /**
     * Sichern in den Durchlauf — ohne Rueckfrage nach dem Ort, wie das
     * Kamera-Backup: nackte Jahreszahl des Aufnahmezeitpunkts, Zielbestand
     * nach derselben Einstellung. „Reinkommen muss ohne jede Entscheidung
     * funktionieren" (CLAUDE.md).
     */
    fun saveIncoming(context: android.content.Context) {
        val batch = incoming?.uris.orEmpty()
        if (batch.isEmpty() || incomingBusy) return
        incomingBusy = true
        viewModelScope.launch {
            val space = if (settings.backupPrivat) "personal" else "shared"
            val suffix = settings.lastUser?.firstOrNull()?.lowercaseChar() ?: 'x'
            var ok = 0
            var firstError: String? = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (uri in batch) {
                    val (name, album) = de.bgghome.mpd.data.IncomingMedia
                        .remoteName(context, uri, suffix)
                    val tmp = java.io.File(
                        context.cacheDir, "incoming.${name.substringAfterLast('.', "jpg")}",
                    )
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: continue
                        api.uploadFile(baseUrl, space, album, name, tmp)
                        ok++
                    } catch (e: MpdHttpException) {
                        if (firstError == null) firstError = explain(e)
                    } catch (e: IOException) {
                        if (firstError == null) firstError = serverUnreachable()
                    } finally {
                        tmp.delete()
                    }
                }
            }
            incomingBusy = false
            incoming = null
            curateStatus = when {
                firstError != null && ok == 0 -> firstError
                firstError != null -> t(Res.string.vm_saved_partial, ok, batch.size, firstError)
                ok == 1 -> t(Res.string.vm_saved_one)
                else -> t(Res.string.vm_saved_n, ok)
            }
            if (ok > 0) loadTimeline(refresh = true)
        }
    }
    /**
     * Bearbeitet eine Geraete-Aufnahme mit denselben Parametern wie der
     * Server. Das Schreiben selbst passiert erst nach der Zustimmung —
     * hier entstehen nur die fertigen Bytes.
     */
    fun editLocalPhoto(
        context: android.content.Context,
        photo: Photo,
        params: de.bgghome.mpd.api.PhotoEditParams,
        onError: (String) -> Unit,
    ) {
        if (localEditBusy) return
        localEditBusy = true
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                de.bgghome.mpd.data.LocalPhotoEdit.apply(
                    context, android.net.Uri.parse(photo.originalUrl), params,
                )
            }
            localEditBusy = false
            when (result) {
                is de.bgghome.mpd.data.LocalPhotoEdit.Result.Ok ->
                    pendingLocalEdit = photo.originalUrl to result.bytes
                is de.bgghome.mpd.data.LocalPhotoEdit.Result.Failed ->
                    onError(result.message)
            }
        }
    }

    /**
     * Das Foto als Bilddatei an eine andere App weitergeben.
     *
     * WARUM DAS HIER LIEGT UND NICHT IN DER OBERFLAECHE (07.09.2026):
     * Vorher lief es in einem rememberCoroutineScope(), der INNERHALB des
     * Weitergeben-Dialogs erzeugt wurde. Der Knopf setzt als Erstes
     * `shareMenuFor = null` — der Dialog verlaesst damit die Komposition,
     * der Scope wird abgebrochen, und der Download des Originals stirbt
     * mitten drin:
     *
     *   LeftCompositionCancellationException: The coroutine scope left
     *   the composition
     *
     * Getroffen hat es ausschliesslich SERVER-Fotos, weil nur die vorher
     * geladen werden muessen. Geraete-Aufnahmen gehen direkt ins
     * Share-Sheet und funktionierten deshalb — was von aussen aussah wie
     * die Regel „was auf der NAS liegt, laesst sich nur als MPD-Link
     * teilen". Es war kein Entwurf, es war ein abgebrochener Ladevorgang
     * (07.09.: „Kann es sein, dass Teilen … noch nicht geht?").
     *
     * viewModelScope ueberlebt jeden Dialog und jeden Bildschirmwechsel.
     */
    fun sharePhotoToApp(context: android.content.Context, photo: Photo) {
        viewModelScope.launch {
            de.bgghome.mpd.ui.sharePhotoFile(context, this@AndroidAppViewModel, photo)
                ?.let { curateStatus = it }
        }
    }

    /**
     * Die Zeitstrahl-Auswahl an eine andere App weitergeben (08.09.2026).
     * Laeuft im viewModelScope, nicht im Scope der Leiste — der Download
     * der Originale darf nicht sterben, wenn die Auswahl verschwindet
     * (Lehre vom 07.09.).
     */
    fun shareSelectionToApp(context: android.content.Context) {
        val items = timelineSelection.toList()
        if (items.isEmpty()) return
        if (items.size > shareMax) {
            meldung(t(Res.string.share_multi_too_many, shareMax))
            return
        }
        val serverCount = items.count { it.localUri == null }
        viewModelScope.launch {
            if (serverCount > 0) meldung(plural(Res.plurals.share_multi_loading_n, serverCount), kurz = true)
            val err = de.bgghome.mpd.ui.sharePhotosFiles(context, this@AndroidAppViewModel, items)
            if (err != null) meldung(err) else clearSelection()
        }
    }

}
