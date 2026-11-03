package de.bgghome.mpd.state

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.data.UploadWorker

/*
 * Kamera-Backup-Einstellungen (Dialog im Hamburger / Einstellungen) — aus
 * dem ViewModel herausgeloest (08.09.2026, Struktur-Audit Stufe 2,
 * Block 3). Die Schalter schreiben in den SettingsStore und stellen den
 * UploadWorker nach; das Anstossen eines Laufs nach dem Einschalten
 * (kickUpload, haengt am Zeitstrahl) reicht das ViewModel als [onEnabled]
 * herein.
 */
class BackupState(
    private val app: Application,
    private val settings: SettingsStore,
    private val onEnabled: () -> Unit,
    /** Spiegel fuer den gemeinsamen Kern (AppViewModel.backupBuckets). */
    private val onBuckets: (Set<String>) -> Unit = {},
) {
    var enabled by mutableStateOf(settings.backupEnabled)
    var privat by mutableStateOf(settings.backupPrivat)
        private set

    /** (aktiv seit, letzter Lauf, gesichert) in ms bzw. Anzahl. */
    fun info(): Triple<Long, Long, Long> =
        Triple(settings.backupSince, settings.backupLastRun, settings.backupUploaded)

    /** Schalter umlegen (Berechtigung muss die UI vorher eingeholt haben).
     *  Heisst nicht setEnabled: das ist auf der JVM schon der Setter von
     *  [enabled]. */
    fun enable(value: Boolean) {
        enabled = value
        settings.backupEnabled = value
        if (value && settings.backupSince == 0L) {
            settings.backupSince = System.currentTimeMillis()
        }
        UploadWorker.ensurePeriodic(app, value)
        UploadWorker.watchMedia(app)
        if (value) onEnabled()
    }

    fun toggleTarget() {
        privat = !privat
        settings.backupPrivat = privat
    }

    var wifiOnly by mutableStateOf(settings.backupWifiOnly)
        private set

    fun toggleNetwork() {
        wifiOnly = !wifiOnly
        settings.backupWifiOnly = wifiOnly
        UploadWorker.ensurePeriodic(app, settings.backupEnabled)
        // Ersetzen, nicht behalten: ein wartender Beobachter traegt sonst
        // weiter die alte Netzbedingung mit sich herum.
        UploadWorker.watchMedia(app, ersetzen = true)
    }

    var buckets by mutableStateOf(settings.backupBuckets)
        private set

    fun toggleBucket(bucket: String) {
        val next = if (bucket in buckets) buckets - bucket
        else buckets + bucket
        if (next.isEmpty()) return // mindestens ein Ordner
        buckets = next
        settings.backupBuckets = next
        onBuckets(next)
    }
}
