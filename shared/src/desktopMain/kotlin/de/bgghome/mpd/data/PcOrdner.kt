package de.bgghome.mpd.data

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.drew.metadata.mov.QuickTimeDirectory
import de.bgghome.mpd.SicherErgebnis
import de.bgghome.mpd.api.ExifCamera
import de.bgghome.mpd.api.ExifData
import de.bgghome.mpd.api.ExifGps
import de.bgghome.mpd.api.ExifSettings
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.TimelineItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TimeZone
import de.bgghome.mpd.api.uploadFile

/*
 * Ordner auf diesem PC im Zeitstrahl (Desktop, 04.10.2026).
 *
 * Vorgabe: „kann man in der Timeline mehrfach Markierungen machen um diese
 * zu löschen oder die markierten hochzuladen" — und dann: „es soll für die
 * deb auf Linux und die exe in Windows gehen". Am Handy kommt die Antwort
 * aus dem MediaStore (LocalMedia, UploadWorker); am PC gibt es keinen
 * MediaStore, also waehlt der Nutzer Ordner aus, und dieses Objekt liest
 * sie, merkt sich das Hochgeladene und legt Weggeworfenes in den
 * Papierkorb des Systems.
 *
 * Gleich wie am Handy, mit Absicht, weil der gemischte Zeitstrahl genau
 * daran Doppelte erkennt (Regeln.nurAufDemGeraet):
 *  - Datum = Aufnahmezeit aus EXIF (Ortszeit), sonst Aenderungszeit.
 *  - Servername = `Stempel.datei(ms)-<kuerzel>.<endung>` im Durchlauf des
 *    Aufnahmejahres; 409 → -2, -3 …
 *  - Ein Buch der hochgeladenen Dateien (Schluessel „f:" + Pfad).
 *
 * Bewusst NICHT wie am Handy: kein Hochladen von selbst. Thomas wollte
 * das Ordner-Backup am PC erst ueberlegen (22.09.2026, Sorge: Handy- und
 * PC-Backup kommen sich ins Gehege). Hier wird nur hochgeladen, was man
 * markiert und sichert.
 */
internal class PcOrdner(
    private val settings: SettingsStore,
    private val cacheOrdner: File,
    private val datenOrdner: File,
) {
    private val indexDatei get() = File(cacheOrdner, "pc-index.tsv")
    private val buchDatei get() = File(datenOrdner, "pc-ledger.txt")

    /** Pfad → (Aenderungszeit, Groesse, Aufnahmezeit ms). EXIF zu lesen
     *  kostet je Datei ein paar Millisekunden — bei zehntausend Fotos
     *  waere jeder Blick in den Zeitstrahl eine Wartepause. */
    private data class IndexEintrag(val mtime: Long, val size: Long, val taken: Long)
    private val index = HashMap<String, IndexEintrag>()
    private var indexGeladen = false

    /** Letztes Einlesen, je Wurzelordner — fuer die Ordnerliste im Dialog. */
    @Volatile private var letzterScan: Map<String, List<TimelineItem>> = emptyMap()

    private val lock = Any()
    private val uploadLock = Mutex()

    // -- Einlesen -------------------------------------------------------------

    fun scan(): List<TimelineItem> = synchronized(lock) {
        ladeIndex()
        val proWurzel = LinkedHashMap<String, List<TimelineItem>>()
        val gesehen = HashSet<String>()
        for (wurzel in settings.lokaleOrdner.sorted()) {
            val dir = File(wurzel)
            if (!dir.isDirectory) { proWurzel[wurzel] = emptyList(); continue }
            val bucket = dir.name.ifEmpty { wurzel }
            val liste = ArrayList<TimelineItem>()
            for (f in medienIn(dir.toPath())) {
                val pfad = f.absolutePath
                if (!gesehen.add(pfad)) continue   // verschachtelte Wurzeln
                val mtime = f.lastModified()
                val size = f.length()
                val alt = index[pfad]
                val taken = if (alt != null && alt.mtime == mtime && alt.size == size) alt.taken
                else aufnahmezeit(f).also { index[pfad] = IndexEintrag(mtime, size, it) }
                liste.add(
                    TimelineItem(
                        date = Stempel.iso(taken),
                        space = "",
                        album = bucket,
                        file = f.name,
                        type = if (istVideo(f.name)) "video" else "photo",
                        referenced = false,
                        localUri = f.toURI().toString(),
                        localKey = schluessel(f),
                        size = size,
                    ),
                )
            }
            proWurzel[wurzel] = liste
        }
        // Verschwundene Dateien aus dem Index werfen, dann sichern.
        index.keys.retainAll(gesehen)
        speichereIndex()
        letzterScan = proWurzel
        proWurzel.values.flatten().sortedByDescending { it.date }
    }

    fun ordner(): List<LocalFolder> {
        if (letzterScan.keys != settings.lokaleOrdner) scan()
        return settings.lokaleOrdner.sorted().map { wurzel ->
            val items = letzterScan[wurzel].orEmpty()
            LocalFolder(
                name = wurzel,
                count = items.size,
                preview = items.filter { it.type == "photo" }.maxByOrNull { it.date }?.localUri,
            )
        }
    }

    /** Bilder und Videos unter [wurzel], ohne versteckte Ordner und ohne
     *  Synologys Vorschau-/Papierkorb-Ordner (wer die NAS eingehaengt hat). */
    private fun medienIn(wurzel: Path): List<File> {
        val out = ArrayList<File>()
        runCatching {
            Files.walkFileTree(wurzel, emptySet(), 16, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val n = dir.fileName?.toString().orEmpty()
                    return if (dir != wurzel && (n.startsWith(".") || n.startsWith("@") || n.startsWith("#") || n == "\$RECYCLE.BIN"))
                        FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val n = file.fileName.toString()
                    if (attrs.isRegularFile && !n.startsWith(".") && istMedium(n)) out.add(file.toFile())
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return out
    }

    // -- Buch der hochgeladenen Dateien --------------------------------------

    fun buch(): Set<String> =
        if (buchDatei.isFile) runCatching { buchDatei.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        else emptySet()

    // -- Hochladen ------------------------------------------------------------

    /**
     * Nacheinander hochladen, nie nebeneinander (der Riegel — dieselbe
     * Lehre wie am Handy, 07.09.2026: zwei Laeufe lasen das Buch, bevor der
     * andere es ergaenzen konnte, und luden dasselbe Foto zweimal).
     */
    suspend fun sichere(
        api: MpdApi,
        keys: List<String>,
        fortschritt: (Int, Int) -> Unit,
    ): SicherErgebnis = uploadLock.withLock {
        val baseUrl = settings.baseUrl ?: return SicherErgebnis(0, keys.size, "Kein Server eingetragen.")
        val space = if (settings.backupPrivat) "personal" else "shared"
        val kuerzel = settings.lastUser?.firstOrNull()?.lowercaseChar() ?: 'x'
        val schonOben = buch().toMutableSet()
        var ok = 0
        var fehler: String? = null
        fortschritt(0, keys.size)
        for ((i, key) in keys.withIndex()) {
            if (key in schonOben) { ok++; fortschritt(i + 1, keys.size); continue }
            val f = File(key.removePrefix("f:"))
            if (!key.startsWith("f:") || !f.isFile) {
                if (fehler == null) fehler = "Datei nicht gefunden: ${f.name}"
                fortschritt(i + 1, keys.size)
                continue
            }
            val taken = synchronized(lock) {
                index[f.absolutePath]?.takeIf { it.mtime == f.lastModified() }?.taken
            } ?: aufnahmezeit(f)
            val stempel = Stempel.datei(taken)
            val endung = f.name.substringAfterLast('.', "jpg").lowercase()
            val name = "$stempel-$kuerzel.$endung"
            when (val r = hochladen(api, baseUrl, space, Stempel.jahr(stempel), name, f)) {
                null -> {
                    ok++
                    schonOben.add(key)
                    runCatching {
                        buchDatei.parentFile?.mkdirs()
                        buchDatei.appendText("$key\n")
                    }
                }
                else -> {
                    if (fehler == null) fehler = r
                    // Netz weg: der Rest scheitert genauso, also aufhoeren.
                    if (r == NETZ_WEG) { fortschritt(i + 1, keys.size); break }
                }
            }
            fortschritt(i + 1, keys.size)
        }
        SicherErgebnis(ok, keys.size, fehler)
    }

    /** null = hochgeladen, sonst der Grund. */
    private suspend fun hochladen(
        api: MpdApi, baseUrl: String, space: String, album: String, name: String, file: File,
    ): String? {
        val stamm = name.substringBeforeLast('.')
        val endung = name.substringAfterLast('.')
        for (versuch in 1..6) {
            val kandidat = if (versuch == 1) name else "$stamm-$versuch.$endung"
            try {
                api.uploadFile(baseUrl, space, album, kandidat, file)
                return null
            } catch (e: MpdHttpException) {
                if (e.code == 409) continue
                return "${file.name}: ${e.detail.ifBlank { "HTTP ${e.code}" }}"
            } catch (e: IOException) {
                return NETZ_WEG
            }
        }
        return "Namenskollision nicht auflösbar: ${file.name}"
    }

    // -- Papierkorb -----------------------------------------------------------

    /**
     * In den Papierkorb des Systems, nie endgueltig loeschen. Windows (und
     * macOS) koennen das ueber java.awt.Desktop; unter Linux kennt AWT den
     * Papierkorb nicht, dort `gio trash` (GLib, auf jedem Xfce/GNOME-System)
     * und zur Not der Papierkorb im Home nach freedesktop.org-Spezifikation.
     */
    fun inDenPapierkorb(uris: List<String>): Int {
        var n = 0
        for (uri in uris) {
            val f = runCatching { File(URI(uri)) }.getOrNull() ?: continue
            if (!f.exists()) continue
            if (papierkorb(f)) n++
        }
        return n
    }

    private fun papierkorb(f: File): Boolean {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported()) {
                val d = java.awt.Desktop.getDesktop()
                if (d.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && d.moveToTrash(f)) return true
            }
        }
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return false
        runCatching {
            val p = ProcessBuilder("gio", "trash", "--", f.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            if (p.waitFor() == 0 && !f.exists()) return true
        }
        return runCatching { heimPapierkorb(f) }.getOrDefault(false)
    }

    /** freedesktop.org Trash 1.0: Datei nach files/, Herkunft in info/. */
    private fun heimPapierkorb(f: File): Boolean {
        val basis = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        val files = File(basis, "Trash/files").apply { mkdirs() }
        val info = File(basis, "Trash/info").apply { mkdirs() }
        var name = f.name
        var i = 1
        while (File(files, name).exists() || File(info, "$name.trashinfo").exists()) {
            name = f.nameWithoutExtension + ".$i" + (f.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
            i++
        }
        val pfad = f.absolutePath.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
        val jetzt = java.time.LocalDateTime.now().withNano(0)
        File(info, "$name.trashinfo").writeText("[Trash Info]\nPath=$pfad\nDeletionDate=$jetzt\n")
        return try {
            Files.move(f.toPath(), File(files, name).toPath())
            true
        } catch (e: IOException) {
            File(info, "$name.trashinfo").delete()
            false
        }
    }

    // -- EXIF -----------------------------------------------------------------

    fun exif(uri: String): ExifData = runCatching {
        val m = ImageMetadataReader.readMetadata(File(URI(uri)))
        val ifd0 = m.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val sub = m.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val gps = m.getFirstDirectoryOfType(GpsDirectory::class.java)
        fun t(d: com.drew.metadata.Directory?, tag: Int) =
            d?.getString(tag)?.trim()?.takeIf { it.isNotEmpty() }
        val make = t(ifd0, ExifIFD0Directory.TAG_MAKE)
        val model = t(ifd0, ExifIFD0Directory.TAG_MODEL)
        val focal = sub?.getDoubleObject(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)?.takeIf { it > 0 }?.let { "%.0fmm".format(it) }
        val blende = sub?.getDoubleObject(ExifSubIFDDirectory.TAG_FNUMBER)?.takeIf { it > 0 }?.let { "f/%.1f".format(it) }
        val iso = sub?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)
        val zeit = sub?.getDoubleObject(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)?.takeIf { it > 0 }
            ?.let { if (it < 1.0) "1/${Math.round(1.0 / it)}" else "%.1f".format(it) }
        val ort = gps?.geoLocation?.takeIf { !it.isZero }
        ExifData(
            datetime = t(sub, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) ?: t(ifd0, ExifIFD0Directory.TAG_DATETIME),
            camera = if (make != null || model != null) ExifCamera(make, model) else null,
            lens = t(sub, ExifSubIFDDirectory.TAG_LENS_MODEL),
            settings = if (focal != null || blende != null || iso != null || zeit != null)
                ExifSettings(focalLength = focal, aperture = blende, shutterSpeed = zeit, iso = iso) else null,
            gps = ort?.let {
                ExifGps(it.latitude, it.longitude, gps.getDoubleObject(GpsDirectory.TAG_ALTITUDE)?.toInt())
            },
            description = t(ifd0, ExifIFD0Directory.TAG_IMAGE_DESCRIPTION),
        )
    }.getOrElse { ExifData(error = it.message ?: "EXIF nicht lesbar") }

    // -- Index ----------------------------------------------------------------

    private fun ladeIndex() {
        if (indexGeladen) return
        indexGeladen = true
        runCatching {
            if (!indexDatei.isFile) return
            indexDatei.forEachLine { zeile ->
                val t = zeile.split('\t')
                if (t.size == 4) {
                    val m = t[1].toLongOrNull(); val s = t[2].toLongOrNull(); val k = t[3].toLongOrNull()
                    if (m != null && s != null && k != null) index[t[0]] = IndexEintrag(m, s, k)
                }
            }
        }
    }

    private fun speichereIndex() {
        runCatching {
            cacheOrdner.mkdirs()
            val tmp = File(cacheOrdner, "pc-index.tsv.tmp")
            tmp.bufferedWriter().use { w ->
                for ((p, e) in index) {
                    if ('\t' in p || '\n' in p) continue
                    w.write("$p\t${e.mtime}\t${e.size}\t${e.taken}\n")
                }
            }
            Files.move(tmp.toPath(), indexDatei.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val NETZ_WEG = "Server nicht erreichbar."

        private val BILDER = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")
        private val VIDEOS = setOf("mp4", "m4v", "mov")

        fun istMedium(name: String) = name.substringAfterLast('.', "").lowercase().let { it in BILDER || it in VIDEOS }
        fun istVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEOS
        fun istBild(name: String) = name.substringAfterLast('.', "").lowercase() in BILDER

        fun schluessel(f: File) = "f:" + f.absolutePath

        /**
         * Aufnahmezeit in ms. Fotos: EXIF DateTimeOriginal (Ortszeit, also
         * mit der Zeitzone dieses Rechners gelesen — dieselbe Rechnung wie
         * MediaStore am Handy). Videos: Erstellzeit aus dem Container (UTC).
         * Sonst die Aenderungszeit der Datei.
         */
        fun aufnahmezeit(f: File): Long {
            val ausDatei = runCatching {
                val m: Metadata = ImageMetadataReader.readMetadata(f)
                m.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                    ?.getDateOriginal(TimeZone.getDefault())?.time
                    ?: m.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                        ?.getDate(ExifIFD0Directory.TAG_DATETIME, TimeZone.getDefault())?.time
                    ?: m.getFirstDirectoryOfType(QuickTimeDirectory::class.java)
                        ?.getDate(QuickTimeDirectory.TAG_CREATION_TIME)?.time
                    ?: m.getFirstDirectoryOfType(Mp4Directory::class.java)
                        ?.getDate(Mp4Directory.TAG_CREATION_TIME)?.time
            }.getOrNull()
            // 1904/1970-Nullwerte mancher Kameras nicht als Datum nehmen.
            return ausDatei?.takeIf { it > 86_400_000L * 365 * 2 } ?: f.lastModified()
        }
    }
}
