package de.bgghome.mpd.data

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import de.bgghome.mpd.shared.R
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.NotifyMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.util.concurrent.TimeUnit
import de.bgghome.mpd.api.memories
import de.bgghome.mpd.api.notifyAck
import de.bgghome.mpd.api.notifyPull
import de.bgghome.mpd.api.registerNotifyDevice

/*
 * Benachrichtigungen ohne ntfy und ohne Google-Cloud (Schritt 5):
 * Der Worker registriert das Geraet idempotent an der Notify-API des
 * MPD-Servers (docs/API_CHANGES.md, 01.09.2026), holt anstehende
 * Ereignisse per Pull ab und zeigt sie als echte Android-Benachrichtigung
 * — bei Memories mit grossem Vorschaubild (BigPictureStyle, wie Google
 * Fotos) und Deep-Link in den Erinnerungen-Schirm. Kein Sofort-Push:
 * der stuendliche Lauf reicht fuer den Morgengruss; Long-Poll im
 * Vordergrund ist ein spaeteres Kapitel.
 */
class NotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        val baseUrl = settings.baseUrl ?: return Result.success()
        if (settings.session() == null && settings.bearerToken() == null) {
            return Result.success() // nicht angemeldet — nichts zu tun
        }
        // Ohne Benachrichtigungs-Berechtigung NICHT abholen — sonst wuerde
        // das Ereignis still quittiert und waere beim spaeteren Erlauben weg.
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return Result.success()
        }
        val api = MpdApi(settings)
        val deviceId = settings.notifyDeviceId

        return try {
            api.registerNotifyDevice(baseUrl, deviceId, Build.MODEL ?: "Android")
            // Ab der zuletzt GEZEIGTEN id abfragen und blaettern, solange eine
            // volle Seite kommt (der Server liefert hoechstens 100 je Antwort).
            // Bis 13.10.2026 stand hier since=0 — und das gab bis Server
            // 3.0.501 die AELTESTEN 100 der 30-Tage-Aufbewahrung: bei 132
            // Meldungen (97 Kies-Hinweise) lag der Morgengruss ausserhalb und
            // kam wochenlang nie an, obwohl der Server ihn taeglich um 06:30
            // erzeugte. Der eigene Zaehler bleibt unabhaengig vom Server-Ack-
            // Cursor; so ueberlebt ein Ereignis ein frueheres, fehlgeschlagenes
            // Anzeigen. Beim allerersten Lauf (Zaehler 0) kommen vom neuen
            // Server die neuesten 100, vom alten die aeltesten — dann holt
            // das Blaettern den Rest.
            val all = mutableListOf<NotifyMessage>()
            var since = settings.notifyLastShownId
            do {
                val page = api.notifyPull(baseUrl, deviceId, since = since)
                val newer = page.notifications.filter { it.id > since }
                all += newer
                since = newer.maxOfOrNull { it.id } ?: break
            } while (page.notifications.size >= PAGE_SIZE)
            // Tagesgruesse sind Tagesware: Memories-Ereignisse von Vortagen
            // NICHT nachreichen ("Heute vor X Jahren" stimmt ja nicht mehr) —
            // sonst stapeln sich beim Nachholen Doppelte (03.09.).
            val today = java.time.LocalDate.now().toString()
            val fresh = all.filter { msg ->
                msg.type != "memories" ||
                    (msg.payload?.get("date") as? JsonPrimitive)?.content == today
            }
            if (fresh.isNotEmpty()) {
                ensureChannel()
                // Mehrere auf einmal (Funkpause, Neuinstallation): je Kanal
                // gebuendelt, EIN Ton ueber die Sammelbenachrichtigung statt
                // 30 einzelner Aufpopper. Eine einzelne meldet sich selbst.
                val perChannel = fresh.groupingBy { channelFor(it) }.eachCount()
                fresh.sortedBy { it.id }.forEach { msg ->
                    val bundled = (perChannel[channelFor(msg)] ?: 0) > 1
                    showNotification(api, msg, bundled)
                }
                perChannel.keys.forEach { channel ->
                    showSummary(channel, alert = (perChannel[channel] ?: 0) > 1)
                }
            }
            all.maxOfOrNull { it.id }?.let { last ->
                settings.notifyLastShownId = last
                api.notifyAck(baseUrl, deviceId, last)
            }
            Result.success()
        } catch (e: java.io.IOException) {
            Result.retry()
        }
    }

    /** 1 Foto = das Foto; 2-3 Fotos = quadratisch beschnitten nebeneinander
     *  mit schmalem Papiersteg — die Google-Fotos-Collagen-Optik. */
    private fun collage(bitmaps: List<android.graphics.Bitmap>): android.graphics.Bitmap? {
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]
        val tile = 512
        val gap = 8
        val width = bitmaps.size * tile + (bitmaps.size - 1) * gap
        val out = android.graphics.Bitmap.createBitmap(
            width, tile, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.parseColor("#FFFDF8"))
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        bitmaps.forEachIndexed { i, bmp ->
            // quadratischer Mittelausschnitt
            val side = minOf(bmp.width, bmp.height)
            val src = android.graphics.Rect(
                (bmp.width - side) / 2, (bmp.height - side) / 2,
                (bmp.width + side) / 2, (bmp.height + side) / 2,
            )
            val left = i * (tile + gap)
            val dst = android.graphics.Rect(left, 0, left + tile, tile)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        return out
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEMORIES,
                Texte.t(Res.string.notify_ch_memories),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = Texte.t(Res.string.notify_ch_memories_desc) },
        )
        // Waechter: Fremd-Einlieferungen der NAS (POST /api/notify/publish,
        // z.B. Harvester-Warnungen von db-kies/blank) — sollen auffallen.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                Texte.t(Res.string.notify_ch_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = Texte.t(Res.string.notify_ch_alerts_desc) },
        )
        // Neuigkeiten: upload-Digest (19:00) und Kuratier-Bericht (So 18:00)
        // — normal-prior, damit jede Kategorie in den Android-Einstellungen
        // einzeln stummschaltbar ist.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEWS,
                Texte.t(Res.string.notify_ch_news),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = Texte.t(Res.string.notify_ch_news_desc) },
        )
    }

    private fun channelFor(msg: NotifyMessage): String = when (msg.type) {
        "memories" -> CHANNEL_MEMORIES
        "alert" -> CHANNEL_ALERTS
        else -> CHANNEL_NEWS
    }

    /**
     * Sammelbenachrichtigung je Kanal (Gruppe = Kanal), gebaut aus dem, was
     * gerade wirklich im Schirm haengt — nicht nur aus diesem Lauf, sonst
     * zaehlt sie nach dem zweiten Lauf falsch. Unter zwei Mitgliedern gibt es
     * keine; Android raeumt sie weg, sobald das letzte Kind weggewischt ist.
     * [alert] = dieser Lauf hat mehrere gebracht: dann toent die Sammlung
     * einmal, die Kinder schweigen (GROUP_ALERT_SUMMARY); sonst nur still
     * nachfuehren, das eine Kind hat sich selbst gemeldet.
     */
    private fun showSummary(channel: String, alert: Boolean) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val members = manager.activeNotifications
            .filter { it.tag == NOTIFY_TAG && it.id > 0 && it.notification.group == channel }
            .sortedByDescending { it.notification.`when` }
        if (members.size < 2) return
        val title = Texte.plural(Res.plurals.notify_group_n, members.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        members.take(7).forEach { sbn ->
            val extras = sbn.notification.extras
            val t = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val b = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            style.addLine(listOf(t, b).filter { it.isNotBlank() }.joinToString(" — "))
        }
        val builder = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(members.first().notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE))
            .setStyle(style)
            .setGroup(channel)
            .setGroupSummary(true)
            .setGroupAlertBehavior(
                if (alert) NotificationCompat.GROUP_ALERT_SUMMARY
                else NotificationCompat.GROUP_ALERT_CHILDREN,
            )
            .setWhen(members.first().notification.`when`)
            .setShowWhen(true)
            .setContentIntent(members.first().notification.contentIntent)
            .setAutoCancel(true)
        if (channel == CHANNEL_ALERTS) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
        }
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFY_TAG, SUMMARY_IDS.getValue(channel), builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** [bundled] = in diesem Lauf kommen mehrere im selben Kanal: dann
     *  meldet sich nur die Sammelbenachrichtigung (siehe [showSummary]). */
    private suspend fun showNotification(api: MpdApi, msg: NotifyMessage, bundled: Boolean) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return

        // Ziel beim Antippen: explizit mitgelieferter deep_link, sonst nur
        // fuer Memories die Erinnerungs-Ansicht. Alarme oeffnen schlicht die
        // App — der alte Pauschal-Fallback auf mpd://memories schickte einen
        // angetippten Waechter-Alarm in den Morgengruss (03.09.).
        val payloadStr = { key: String -> (msg.payload?.get(key) as? JsonPrimitive)?.content }
        val deepLink = payloadStr("deep_link")
            ?: when (msg.type) {
                "memories" -> "mpd://memories"
                // upload-Digest: direkt ins frisch befuellte Album springen
                "upload" -> {
                    val space = payloadStr("space")
                    val album = payloadStr("album")
                    if (space != null && album != null) {
                        android.net.Uri.parse("mpd://album").buildUpon()
                            .appendQueryParameter("space", space)
                            .appendQueryParameter("album", album)
                            .build().toString()
                    } else null
                }
                else -> null
            }
        // Nicht MainActivity::class.java: die Activity liegt in der App-Huelle
        // (:app), dieser Worker im geteilten Modul (:shared, seit 22.09.2026),
        // und die Bibliothek darf die App nicht kennen. Der Start-Intent des
        // eigenen Pakets nennt dieselbe Activity — uebernommen wird von ihm
        // nur die Komponente.
        //
        // DENN: MIT action=MAIN + category=LAUNCHER KAME DER DEEP-LINK NIE AN.
        // Ein Launcher-Intent auf eine Aufgabe, die es schon gibt, holt sie
        // nur nach vorn — Android stellt ihn nicht zu, weder an onCreate noch
        // an onNewIntent. Tippt man also den Morgengruss an, waehrend die App
        // im Hintergrund lebt, landete man dort, wo man zuletzt war, statt in
        // den Erinnerungen. Eine eigene Aktion auf derselben (expliziten)
        // Komponente hat diese Sonderbehandlung nicht.
        val start = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)!!
        val intent = Intent(ACTION_DEEP_LINK).apply {
            component = start.component
            setPackage(applicationContext.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra(EXTRA_DEEP_LINK, it) }
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            msg.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val channel = channelFor(msg)
        val builder = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(msg.title.ifBlank { "My Photo Diary" })
            .setContentText(msg.body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(channel)
            .setGroupAlertBehavior(
                if (bundled) NotificationCompat.GROUP_ALERT_SUMMARY
                else NotificationCompat.GROUP_ALERT_CHILDREN,
            )
        // Anzeigezeit = Entstehung der Meldung, nicht der Abruf — eine nach
        // einer Funkpause nachgeholte Meldung soll nicht aussehen wie eine
        // neue (Server-Chat, 13.10.2026).
        if (msg.createdAt > 0) {
            builder.setWhen((msg.createdAt * 1000).toLong()).setShowWhen(true)
        }
        if (channel == CHANNEL_ALERTS) {
            // Ab API 26 entscheidet der Kanal — Prioritaet/Kategorie helfen
            // trotzdem OEM-Rankern (Samsung) beim Heads-up-Urteil.
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
        }

        // Memories: Collage wie Google Fotos — bis zu drei Fotos (je eines
        // aus verschiedenen Jahren) werden zu EINEM grossen Vorschaubild
        // montiert (BigPictureStyle kann nur ein Bild, also bauen wir eins).
        if (msg.type == "memories") {
            runCatching {
                val baseUrl = SettingsStore(applicationContext).baseUrl!!
                val photos = api.memories(baseUrl, limit = 1).years
                    .mapNotNull { it.photos.firstOrNull() }
                    .take(3)
                val bitmaps = photos.mapNotNull { photo ->
                    api.httpClient().newCall(
                        Request.Builder().url(baseUrl + photo.thumbnailUrl).build(),
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) null
                        else resp.body?.bytes()?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                }
                collage(bitmaps)?.let { bitmap ->
                    builder
                        .setLargeIcon(bitmaps.first())
                        .setStyle(
                            NotificationCompat.BigPictureStyle()
                                .bigPicture(bitmap)
                                .bigLargeIcon(null as android.graphics.Bitmap?)
                                .setSummaryText(msg.body),
                        )
                }
            } // Bild ist Kuer — ohne bleibt die Text-Benachrichtigung
        }

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFY_TAG, msg.id.toInt(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS fehlt (Nutzer hat abgelehnt) — still bleiben
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK = "mpd.deep_link"

        /** Eigene Aktion statt MAIN/LAUNCHER — siehe showNotification. */
        const val ACTION_DEEP_LINK = "de.bgghome.mpd.action.DEEP_LINK"
        private const val CHANNEL_MEMORIES = "memories"
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_NEWS = "news"
        private const val NOTIFY_TAG = "mpd-notify"
        /** Seitengroesse von GET /api/notify/pull (Servermaximum). */
        private const val PAGE_SIZE = 100
        /** Feste, negative ids der Sammelbenachrichtigungen — Kinder tragen
         *  die (positive) Notify-id des Servers. */
        private val SUMMARY_IDS = mapOf(
            CHANNEL_MEMORIES to -1, CHANNEL_ALERTS to -2, CHANNEL_NEWS to -3,
        )
        private const val UNIQUE = "mpd-notify"
        private const val PERIODIC = "mpd-notify-periodic"

        /** Sofort-Abholung (App-Start, nach Login). */
        fun kick(context: Context) {
            // REPLACE statt KEEP: haengt ein fehlgeschlagener Lauf im
            // Exponential-Backoff (Server-Neustart!), wuerde KEEP den
            // Sofort-Abruf beim App-Start still verschlucken (03.09.).
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NotifyWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** Wachlauf alle 15 min (WorkManager-Minimum) — seit die Waechter-
         *  Alarme der NAS hier ankommen, zaehlt Latenz; fuer den reinen
         *  Memories-Morgengruss hatte auch 1 h gereicht. */
        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
