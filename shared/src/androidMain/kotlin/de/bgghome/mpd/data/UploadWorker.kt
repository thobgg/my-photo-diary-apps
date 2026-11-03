package de.bgghome.mpd.data

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.withLock
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Geraeteordner-Backup (ersetzt Synology Photos, 02.09.2026): sichert
 * neue MediaStore-Eintraege der gewaehlten Geraeteordner (Default:
 * Camera) ab dem Aktivierungszeitpunkt in den Durchlauf-Ordner (nackte
 * Jahreszahl des Aufnahmezeitpunkts). Laeuft als WorkManager-Job auch
 * nach App-Tod/Neustart, wahlweise nur WLAN (Default) oder jedes Netz.
 * Lokale Buchfuehrung (backup-ledger.txt, eine MediaStore-id je Zeile)
 * verhindert Doppel-Uploads — der Server prueft nur den Zielordner, und
 * nach dem Einsortieren waere die Datei dort weg. 409 = Namenskollision
 * → Suffix -2… Ein einziger eindeutiger Job (KEEP).
 * Eine eigene MPD-Kamera gibt es seit 02.09. abends NICHT mehr
 * (unbrauchbar gegen die Samsung-UI) — fotografiert wird normal,
 * das Backup holt es ab.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        val baseUrl = settings.baseUrl ?: return Result.success()
        val api = MpdApi(settings)
        var allDone = true

        // Ausdruecklich angeforderte Aufnahmen („jetzt sichern"). Sie
        // laufen auch dann, wenn das Kamera-Backup ausgeschaltet ist: der
        // Nutzer hat es gerade verlangt, das ist Auftrag genug.
        val nurDiese = inputData.getStringArray(KEY_IDS)?.toSet()
        if (nurDiese != null) {
            backupLock.withLock {
                backupCameraRoll(api, baseUrl, settings, nurDiese) { allDone = false }
                // Stempeln, damit der Zeitstrahl beim naechsten Hinsehen
                // nachlaedt und die Wolke verschwindet.
                settings.backupLastRun = System.currentTimeMillis()
            }
            return if (allDone) Result.success() else Result.retry()
        }

        if (settings.backupEnabled) {
            // Nacheinander, nie nebeneinander. Der Stundenlauf, der
            // Anstoss beim App-Start und der Beobachter sind DREI
            // Auftraege mit verschiedenen Namen — WorkManager laesst sie
            // gleichzeitig laufen. Am 07.09.2026 im Server-Log gesehen:
            // dieselbe Aufnahme zweimal hochgeladen, 122 ms auseinander,
            // 13 MB doppelt. Beide hatten die Merkliste gelesen, bevor
            // der jeweils andere sie ergaenzen konnte.
            //
            // Diesmal ging es gut, weil der zweite Upload denselben
            // Dateinamen trug und die erste Datei ueberschrieb. Bei
            // minimal anderem Takt haette er eine Namenskollision
            // bekommen und waere als „-2" gelandet: dasselbe Foto
            // zweimal im Album, und niemand haette es bemerkt.
            //
            // Der Riegel ist statisch, also prozessweit — WorkManager
            // fuehrt alle Worker im selben Prozess aus. Der zweite Lauf
            // wartet und liest die Merkliste danach NEU; dann ist die
            // Aufnahme darin und wird uebersprungen.
            backupLock.withLock {
                backupCameraRoll(api, baseUrl, settings, null) { allDone = false }
                settings.backupLastRun = System.currentTimeMillis()
            }
        }

        // Den Beobachter erneut scharfstellen: ein Auftrag mit
        // ContentUri-Ausloeser feuert genau einmal.
        if (settings.backupEnabled) watchMedia(applicationContext)

        return if (allDone) Result.success() else Result.retry()
    }

    /**
     * REJECTED = der Server nimmt genau diese Datei nie an (Format, Name,
     * Groesse). Der Unterschied zu SKIP ist nicht kosmetisch: ohne ihn
     * wurde eine abgelehnte Aufnahme bei JEDEM Lauf erneut vollstaendig
     * hochgeladen, bis der Server sie wieder ablehnte — fuer immer.
     * Gefunden am 07.09.2026 beim Nachdenken ueber fremde Installationen:
     * eine einzige RAW-Datei im Kameraordner genuegt, und das Backup
     * meldet dauerhaft einen Fehler, der nie verschwindet.
     */
    private enum class UploadResult { OK, RETRY, SKIP, REJECTED }

    /** 409 = Kollision → -2, -3 … bis -6; Netzfehler → RETRY. */
    private suspend fun uploadWithSuffix(
        api: MpdApi,
        baseUrl: String,
        space: String,
        album: String,
        name: String,
        file: File,
    ): UploadResult {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ".jpg"
        var attempt = 1
        while (attempt <= 6) {
            val candidate = if (attempt == 1) name else "$stem-$attempt$ext"
            try {
                api.uploadFile(baseUrl, space, album, candidate, file)
                return UploadResult.OK
            } catch (e: MpdHttpException) {
                if (e.code == 409) { attempt++; continue }
                // 415 unbekannte Endung, 400 unbrauchbarer Name, 413 zu
                // gross — alle drei aendern sich nicht dadurch, dass man
                // es nochmal versucht. 403 dagegen schon (Rechte koennen
                // sich aendern), und 5xx erst recht.
                if (e.code == 415 || e.code == 400 || e.code == 413) {
                    lastError = e.detail
                    return UploadResult.REJECTED
                }
                lastError = e.detail
                return UploadResult.SKIP
            } catch (e: java.io.IOException) {
                return UploadResult.RETRY
            }
        }
        lastError = "Namenskollision nicht aufloesbar: $name"
        return UploadResult.SKIP
    }

    /**
     * Aufnahmen aus dem MediaStore in den Durchlauf sichern.
     *
     * @param nurDiese null = der normale Lauf: nur ueberwachte Ordner und
     *   nur, was nach dem Einschalten dazugekommen ist. Sonst genau diese
     *   Schluessel („i123"/„v456") — OHNE beide Filter.
     *
     * Der Unterschied ist der Grund fuer „jetzt sichern" (07.09.2026):
     * Eine Aufnahme in einem nicht ueberwachten Ordner oder eine von
     * VOR dem Einschalten wird vom normalen Lauf nie geholt. Nie
     * heisst nie — der Zeitstrahl zeigte sie trotzdem dauerhaft mit der
     * durchgestrichenen Wolke, ohne dass es einen Weg gab. Auf
     * ausdrueckliche Anforderung gelten die Filter deshalb nicht.
     */
    private suspend fun backupCameraRoll(
        api: MpdApi,
        baseUrl: String,
        settings: SettingsStore,
        nurDiese: Set<String>?,
        onRetry: () -> Unit,
    ) {
        val ledgerFile = File(applicationContext.filesDir, "backup-ledger.txt")
        val ledger = if (ledgerFile.isFile) {
            ledgerFile.readLines().toMutableSet()
        } else mutableSetOf()
        // Zwei getrennte Listen, mit Absicht: das Ledger heisst „liegt auf
        // der NAS" und traegt die Wolke im Zeitstrahl. Eine abgelehnte
        // Aufnahme dort einzutragen waere eine Luege — sie saehe gesichert
        // aus und duerfte geloescht werden. Sie gehoert in eine eigene
        // Liste: nicht nochmal versuchen, aber auch nicht gesichert.
        val rejectedFile = File(applicationContext.filesDir, REJECTED_FILE)
        val rejected = if (rejectedFile.isFile) {
            rejectedFile.readLines().mapNotNull {
                it.substringBefore('|').takeIf(String::isNotBlank)
            }.toMutableSet()
        } else mutableSetOf()
        var neuAbgelehnt = 0
        val sinceSec = settings.backupSince / 1000
        val space = if (settings.backupPrivat) "personal" else "shared"
        val suffix = settings.lastUser?.firstOrNull()?.lowercaseChar() ?: 'x'

        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )
        for ((collection, isVideo) in collections) {
            // Bei ausdruecklicher Anforderung: die Ids DIESER Sammlung.
            // Ist keine dabei, gibt es fuer sie nichts zu tun.
            val ids = nurDiese
                ?.filter { it.startsWith(if (isVideo) "v" else "i") }
                ?.mapNotNull { it.drop(1).toLongOrNull() }
            if (ids != null && ids.isEmpty()) continue

            val cursor = applicationContext.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                ),
                if (ids == null) {
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME +
                        " IN (${settings.backupBuckets.joinToString(",") { "?" }}) AND " +
                        "${MediaStore.MediaColumns.DATE_ADDED} > ?"
                } else {
                    // Die Zahlen stammen aus dem MediaStore und sind durch
                    // toLongOrNull() gegangen — direkt einsetzbar.
                    MediaStore.MediaColumns._ID + " IN (${ids.joinToString(",")})"
                },
                if (ids == null) {
                    (settings.backupBuckets.toList() + sinceSec.toString()).toTypedArray()
                } else null,
                MediaStore.MediaColumns.DATE_ADDED + " ASC",
            ) ?: continue
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val key = (if (isVideo) "v" else "i") + id
                    // Bei „jetzt sichern" zaehlt die Absage-Liste nicht:
                    // wer ausdruecklich darauf tippt, will es nochmal
                    // versuchen. Das Ledger gilt weiter — was oben ist,
                    // ist oben.
                    if (key in ledger) continue
                    if (nurDiese == null && key in rejected) continue

                    val takenMs = c.getLong(takenCol)
                        .takeIf { it > 0 } ?: c.getLong(addedCol) * 1000
                    val displayName = c.getString(nameCol) ?: "media"
                    val ext = displayName.substringAfterLast('.', "jpg").lowercase()
                    val stamp = Stempel.datei(takenMs)
                    val remoteName = "$stamp-$suffix.$ext"
                    val album = Stempel.jahr(stamp)

                    // In eine Temp-Datei ziehen (Upload braucht Laenge/Retry)
                    val tmp = File(applicationContext.cacheDir, "backup-tmp.$ext")
                    try {
                        val uri = android.content.ContentUris
                            .withAppendedId(collection, id)
                        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: continue
                        when (uploadWithSuffix(api, baseUrl, space, album, remoteName, tmp)) {
                            UploadResult.OK -> {
                                ledger.add(key)
                                ledgerFile.appendText("$key\n")
                                settings.backupUploaded = settings.backupUploaded + 1
                            }
                            UploadResult.RETRY -> { onRetry(); return }
                            UploadResult.SKIP -> Unit
                            UploadResult.REJECTED -> {
                                rejected.add(key)
                                rejectedFile.appendText("$key|$displayName\n")
                                neuAbgelehnt++
                            }
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }

        if (neuAbgelehnt > 0) {
            // Eine Zahl statt der rohen Servermeldung, und nur wenn in
            // DIESEM Lauf etwas dazukam. Vorher stand hier bei jedem Lauf
            // dieselbe Meldung zu derselben Datei — und ueberschrieb dabei
            // Fehler, die wirklich etwas bedeutet haetten.
            lastError = Texte.plural(Res.plurals.backup_rejected_n, neuAbgelehnt) +
                Texte.t(Res.string.backup_rejected_hint)
        }
    }

    companion object {
        /** Letzter Upload-Fehlertext — die UI liest ihn beim Refresh. */
        @Volatile var lastError: String? = null

        /** Dauerhaft abgelehnte Aufnahmen, „<schluessel>|<dateiname>" je Zeile. */
        internal const val REJECTED_FILE = "backup-rejected.txt"

        /**
         * Dateinamen der Aufnahmen, die der Server nicht annimmt — fuer
         * die Einstellungen. Ohne diese Liste waere die Ablehnung genau
         * das, was sie vorher war: unsichtbar.
         */
        fun rejectedNames(context: Context): List<String> {
            val f = File(context.filesDir, REJECTED_FILE)
            if (!f.isFile) return emptyList()
            return runCatching {
                f.readLines().mapNotNull {
                    it.substringAfter('|', "").takeIf(String::isNotBlank)
                }
            }.getOrDefault(emptyList())
        }

        /**
         * Liste leeren, damit es beim naechsten Lauf noch einmal versucht
         * wird. Der Weg muss es geben: nimmt der Server spaeter ein Format
         * an, das er heute ablehnt, waere die Aufnahme sonst fuer immer
         * uebersprungen — genau die stille Luecke, gegen die das hier
         * gebaut ist.
         */
        fun clearRejected(context: Context) {
            File(context.filesDir, REJECTED_FILE).delete()
            lastError = null
        }

        private const val UNIQUE = "mpd-upload"
        private const val PERIODIC = "mpd-upload-periodic"
        private const val WATCH = "mpd-upload-watch"

        /** Schluessel der ausdruecklich angeforderten Aufnahmen. */
        internal const val KEY_IDS = "ids"

        /**
         * „Jetzt sichern" fuer bestimmte Geraete-Aufnahmen.
         *
         * Laeuft ohne die beiden Filter des Kamera-Backups (ueberwachter
         * Ordner, Einschaltzeitpunkt) und auch bei ausgeschaltetem Backup
         * — wer darauf tippt, hat es verlangt.
         *
         * Als WorkManager-Auftrag und nicht als Coroutine im ViewModel:
         * Der Upload eines Videos dauert, und er soll weiterlaufen, wenn
         * der Nutzer die App zuklappt.
         */
        fun sichereJetzt(context: Context, keys: List<String>) {
            if (keys.isEmpty()) return
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putStringArray(KEY_IDS, keys.toTypedArray())
                            .build(),
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(net(context))
                            .build(),
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS,
                    )
                    .build(),
            )
        }

        /** Serialisiert alle Backup-Laeufe im Prozess — siehe doWork(). */
        private val backupLock = kotlinx.coroutines.sync.Mutex()

        private fun net(context: Context): NetworkType =
            if (SettingsStore(context).backupWifiOnly) NetworkType.UNMETERED
            else NetworkType.CONNECTED

        /** Sofort-Lauf (App-Start, Aktivierung, Einstellungs-Wechsel). */
        fun kick(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(net(context))
                            .build(),
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS,
                    )
                    .build(),
            )
        }

        /**
         * Beobachter: laeuft, sobald im MediaStore ein Bild oder Video
         * dazukommt — also Sekunden nach dem Ausloesen der Kamera.
         *
         * WARUM (07.09.2026): Bis hierher gab es nur den Stundenlauf und
         * den Anstoss beim App-Start. Der Nutzer hatte ein Foto gemacht und
         * gefragt, warum der Upload nicht laeuft; er lief, aber der
         * naechste Termin war 59 Minuten spaeter. Gemessen an „reinkommen
         * muss ohne jede Entscheidung funktionieren" ist eine Stunde zu
         * lang — und die App zu oeffnen, damit ein Backup startet, ist
         * genau so eine Entscheidung.
         *
         * ContentUri-Ausloeser sind der dafuer vorgesehene Weg: kein
         * Dauerpollen, sondern eine Benachrichtigung des Systems. Sie
         * gehen nur an OneTimeWorkRequest und feuern genau EINMAL —
         * deshalb stellt doWork() den Beobachter am Ende neu scharf.
         *
         * Die beiden Verzoegerungen buendeln Serien: nach der letzten
         * Aenderung 15 s warten, spaetestens aber nach 2 min laufen. Wer
         * zwanzig Bilder hintereinander schiesst, loest damit einen Lauf
         * aus und nicht zwanzig.
         *
         * Der Stundenlauf bleibt als Netz — fuer alles, was das System
         * verschluckt, und fuer Aufnahmen, die vor dem Einschalten des
         * Backups entstanden sind.
         */
        /**
         * @param ersetzen true, wenn sich die Netz-Einstellung geaendert
         *   hat: ein bereits wartender Beobachter traegt sonst weiter die
         *   ALTE Bedingung (WLAN/jedes Netz) und wuerde beim naechsten
         *   Foto nach der alten Regel laufen.
         */
        fun watchMedia(context: Context, ersetzen: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            if (!SettingsStore(context).backupEnabled) {
                wm.cancelUniqueWork(WATCH)
                return
            }
            wm.enqueueUniqueWork(
                WATCH,
                // KEEP: einen bereits wartenden Beobachter nicht bei jedem
                // App-Start zuruecksetzen. Ist er gelaufen, ist er fertig
                // und ein neuer wird angenommen.
                if (ersetzen) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(net(context))
                            .addContentUriTrigger(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true,
                            )
                            .addContentUriTrigger(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true,
                            )
                            .setTriggerContentUpdateDelay(15, TimeUnit.SECONDS)
                            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                            .build(),
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS,
                    )
                    .build(),
            )
        }

        /** Stuendlicher Wachlauf; UPDATE uebernimmt auch geaenderte
         *  Netz-Einstellungen. */
        fun ensurePeriodic(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(net(context))
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
