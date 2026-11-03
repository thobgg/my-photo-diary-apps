package de.bgghome.mpd

import android.app.Application
import de.bgghome.mpd.api.TimelineItem
import de.bgghome.mpd.data.LocalFolder
import de.bgghome.mpd.data.LocalMedia
import de.bgghome.mpd.data.NotifyWorker
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.data.UploadWorker

/** Android-Umsetzung von [Plattform]: WorkManager-Worker und MediaStore. */
class AndroidPlattform(private val app: Application) : Plattform {
    override val settings = SettingsStore(app)
    override val hatGeraetefotos = true
    override val cacheOrdner: java.io.File get() = app.cacheDir

    override fun starteHintergrund(backupEnabled: Boolean) {
        // Liegengebliebene Uploads (Outbox/Backup) nach App-Start anstossen
        UploadWorker.kick(app)
        UploadWorker.ensurePeriodic(app, backupEnabled)
        // Seit 07.09.2026: laeuft Sekunden nach dem Ausloesen der Kamera,
        // statt bis zum naechsten Stundenlauf zu warten.
        UploadWorker.watchMedia(app)
        // Benachrichtigungen: sofort abholen + 15-min-Wachlauf
        NotifyWorker.kick(app)
        NotifyWorker.ensurePeriodic(app)
    }

    override fun kickUpload() = UploadWorker.kick(app)
    override fun kickNotify() = NotifyWorker.kick(app)

    override fun holeUploadFehler(): String? =
        UploadWorker.lastError?.also { UploadWorker.lastError = null }

    override fun scanLocalMedia(hidden: Set<String>): List<TimelineItem> = LocalMedia.scan(app, hidden)
    override fun ledgerKeys(): Set<String> = LocalMedia.ledgerKeys(app)
    override fun localFolders(): List<LocalFolder> = LocalMedia.folders(app)
    override fun lokaleExif(url: String) = de.bgghome.mpd.data.LocalExif.read(app, url)
    override fun abgelehnteUploads(): List<String> = UploadWorker.rejectedNames(app)
    override fun abgelehnteErneut() { UploadWorker.clearRejected(app); UploadWorker.kick(app) }
    override fun sichereJetzt(keys: List<String>) = UploadWorker.sichereJetzt(app, keys)
}
