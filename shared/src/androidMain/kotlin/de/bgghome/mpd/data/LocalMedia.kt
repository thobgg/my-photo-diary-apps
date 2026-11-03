package de.bgghome.mpd.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import de.bgghome.mpd.api.TimelineItem
import java.io.File

/*
 * Geraete-Aufnahmen fuer den gemischten Zeitstrahl (05.09.2026).
 *
 * Bis hierher zeigte der Zeitstrahl ausschliesslich, was auf der NAS lag:
 * ein Foto war unsichtbar, bis das Kamera-Backup es hochgeladen hatte.
 * Thomas wollte den Google-Fotos-Strom — lokale Aufnahmen sofort sichtbar,
 * mit Sicherungsstatus. Gelesen werden alle Geraeteordner; welche davon
 * im Zeitstrahl erscheinen, entscheidet der Nutzer am Geraet selbst
 * (Einstellungen → Geraeteordner im Zeitstrahl). Gespeichert wird der
 * Ausschluss, damit „alles sichtbar" der Standard bleibt und ein neu
 * angelegter Ordner nicht stillschweigend fehlt.
 */
object LocalMedia {

    /** "i"/"v" + MediaStore-id — dasselbe Format wie im Backup-Ledger. */
    fun ledgerKeys(context: Context): Set<String> {
        val f = File(context.filesDir, "backup-ledger.txt")
        return if (f.isFile) f.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    }

    /**
     * Alle Bilder und Videos des Geraets als Zeitstrahl-Eintraege, neueste
     * zuerst. `localUri` traegt die content://-Adresse und ist zugleich das
     * Kennzeichen: gesetzt = liegt (auch) auf dem Geraet.
     *
     * Fehlt die Leseberechtigung, kommt eine leere Liste zurueck statt einer
     * Ausnahme — der Zeitstrahl zeigt dann eben nur den Server.
     */
    fun scan(context: Context, hidden: Set<String> = emptySet()): List<TimelineItem> {
        val out = ArrayList<TimelineItem>(512)
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )
        for ((collection, isVideo) in collections) {
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                    ),
                    null, null, null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val bucketCol =
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val bucket = c.getString(bucketCol).orEmpty()
                        if (bucket in hidden) continue
                        val id = c.getLong(idCol)
                        // DATE_TAKEN ist ms und fehlt oft (Screenshots,
                        // heruntergeladene Bilder) — DATE_ADDED ist Sekunden.
                        val takenMs = c.getLong(takenCol).takeIf { it > 0 }
                            ?: (c.getLong(addedCol) * 1000)
                        if (takenMs <= 0) continue
                        out.add(
                            TimelineItem(
                                date = Stempel.iso(takenMs),
                                space = "",
                                album = bucket,
                                file = c.getString(nameCol) ?: "media",
                                type = if (isVideo) "video" else "photo",
                                // Nichts auf dem Geraet ist kuratiert.
                                referenced = false,
                                localUri = ContentUris
                                    .withAppendedId(collection, id).toString(),
                                localKey = (if (isVideo) "v" else "i") + id,
                                size = c.getLong(sizeCol),
                            ),
                        )
                    }
                }
            }
        }
        out.sortByDescending { it.date }
        return out
    }

    /** Ein Geraeteordner fuer die Auswahl: Name, Menge, neuestes Bild. */
    // Folder heisst seit der Aufteilung LocalFolder (commonMain).

    /**
     * Alle Ordner mit Medien, die vollsten zuerst — Camera steht damit
     * oben, ohne dass wir sie besonders behandeln muessten. Die Vorschau
     * ist die neueste Aufnahme des Ordners: einen Ordner erkennt man am
     * Bild schneller als am Namen.
     */
    fun folders(context: Context): List<LocalFolder> {
        val counts = HashMap<String, Int>()
        val newest = HashMap<String, Pair<Long, String>>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        for (collection in collections) {
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    ),
                    null, null, null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val bucketCol =
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val bucket = c.getString(bucketCol) ?: continue
                        counts[bucket] = (counts[bucket] ?: 0) + 1
                        val added = c.getLong(addedCol)
                        if ((newest[bucket]?.first ?: -1L) < added) {
                            newest[bucket] = added to ContentUris
                                .withAppendedId(collection, c.getLong(idCol)).toString()
                        }
                    }
                }
            }
        }
        return counts.map { (name, n) -> LocalFolder(name, n, newest[name]?.second) }
            .sortedByDescending { it.count }
    }

    /**
     * Der Sekundenstempel, unter dem das Kamera-Backup diese Aufnahme auf
     * dem Server ablegt: `UploadWorker` benennt beim Upload um, aus
     * `20260621_112430.jpg` wird `2026-06-21_11-24-30-<suffix>.jpg`. Der
     * Originalname traegt also nicht — dieser Stempel schon, und zwar auch
     * dann noch, wenn das Ledger fehlt (App neu installiert).
     * Seit 08.09.2026 nur noch ein Durchgriff auf [Stempel.vonIso].
     */
    fun stampOf(iso: String): String = Stempel.vonIso(iso)

    // -- Papierkorb des Geraets ---------------------------------------------

    /**
     * Ein Eintrag im Android-Papierkorb.
     *
     * [ablauf] ist der Zeitpunkt, an dem das System die Datei endgueltig
     * wegwirft (Sekunden seit Epoch, 0 = unbekannt). Das ist die einzige
     * Angabe, die hier wirklich zaehlt: ein Papierkorb ohne Restfrist ist
     * ein Ordner.
     */
    data class Trashed(
        val uri: String,
        val name: String,
        val isVideo: Boolean,
        val ablauf: Long,
        val ordner: String,
    )

    /**
     * Alles, was auf dem Geraet im Papierkorb liegt — das zuerst
     * Ablaufende oben.
     *
     * WARUM DAS UEBERHAUPT GEHT, und warum es nicht selbstverstaendlich
     * ist: Eine App sieht normalerweise nur ihre eigenen Papierkorb-
     * Eintraege, und MPD hat keine dieser Dateien angelegt — die kommen
     * aus der Samsung-Kamera. Nachgelesen im AOSP-Quelltext von
     * MediaProvider (appendAccessCheckQuery, appendWhereStandaloneMatch):
     * Wer Lesezugriff auf die ganze Sammlung hat — READ_MEDIA_IMAGES und
     * READ_MEDIA_VIDEO, beide im Manifest — bekommt gar keine
     * Eigentuemer-Filterung; MATCH_ONLY haengt dann nur `is_trashed=1` an.
     * Es braucht also kein MANAGE_EXTERNAL_STORAGE und kein eigenes
     * Loesch-Journal.
     *
     * Zurueckholen und endgueltig Loeschen sind eine andere Sache: das ist
     * ein SCHREIBZUGRIFF auf fremde Dateien und geht nur ueber die
     * System-Dialoge (createTrashRequest / createDeleteRequest) — siehe
     * MainActivity.
     *
     * Vor Android 11 gibt es den Papierkorb nicht; dann leere Liste.
     */
    fun trashed(context: Context): List<Trashed> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<Trashed>(64)
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.MediaColumns.DATE_EXPIRES + " ASC",
            )
        }
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )
        for ((collection, isVideo) in collections) {
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_EXPIRES,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    ),
                    args, null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val expCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
                    val bucketCol =
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        out.add(
                            Trashed(
                                uri = ContentUris
                                    .withAppendedId(collection, id).toString(),
                                name = c.getString(nameCol) ?: "media",
                                isVideo = isVideo,
                                ablauf = c.getLong(expCol),
                                ordner = c.getString(bucketCol).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
        out.sortBy { if (it.ablauf > 0) it.ablauf else Long.MAX_VALUE }
        return out
    }

    /**
     * Verbleibende Tage bis zur endgueltigen Loeschung, aufgerundet.
     * null = das System nennt keine Frist.
     *
     * Aufgerundet, weil „noch 0 Tage" fuer etwas, das es noch gibt, wie
     * ein Fehler aussieht. Wer heute noch etwas retten kann, soll „noch
     * 1 Tag" lesen.
     */
    fun restTage(ablauf: Long, jetztMs: Long = System.currentTimeMillis()): Int? {
        if (ablauf <= 0) return null
        val restMs = ablauf * 1000 - jetztMs
        if (restMs <= 0) return 0
        return ((restMs + 86_399_999) / 86_400_000).toInt()
    }
}
