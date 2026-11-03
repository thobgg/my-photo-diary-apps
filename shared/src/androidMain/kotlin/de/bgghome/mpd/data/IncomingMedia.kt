package de.bgghome.mpd.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/*
 * Was von aussen hereinkommt (06.09.2026).
 *
 * Zwei Wege, beide neu — vorher kannte das Manifest nur MAIN/LAUNCHER,
 * die App war fuer Android unsichtbar:
 *
 *  ACTION_SEND / SEND_MULTIPLE  → „Teilen mit MPD": sichern in den
 *      Durchlauf. Das ist zugleich der Weg, EINZELNE Dateien gezielt
 *      hochzuladen, den die CLAUDE.md als Luecke fuehrt — bisher konnte
 *      das nur das Kamera-Backup, und nur ordnerweise.
 *  ACTION_VIEW → ansehen. Damit ist MPD als Galerie waehlbar.
 *
 * Der Zielordner folgt derselben Regel wie das Backup: nackte Jahreszahl
 * des Aufnahmezeitpunkts. Keine Rueckfrage nach dem Ort — „reinkommen
 * muss ohne jede Entscheidung funktionieren" (CLAUDE.md).
 */
object IncomingMedia {

    /** Was ein hereingereichter Intent mitbringt. */
    data class Incoming(
        val uris: List<Uri>,
        /** true = ansehen (VIEW), false = sichern (SEND). */
        val viewOnly: Boolean,
    )

    fun from(intent: Intent?): Incoming? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                    ?: return null
                Incoming(listOf(uri), viewOnly = false)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
                    ?.filterNotNull().orEmpty()
                if (list.isEmpty()) null else Incoming(list, viewOnly = false)
            }

            Intent.ACTION_VIEW -> intent.data?.let { Incoming(listOf(it), viewOnly = true) }

            else -> null
        }
    }

    /** Anzeigename der Datei hinter einer content://-Adresse. */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) {
                    c.getString(col)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "geteilt"
    }

    /**
     * Zielname auf dem Server, nach derselben Regel wie das Kamera-Backup:
     * `2026-09-06_14-30-22-<kuerzel>.<ext>`. Der Aufnahmezeitpunkt kommt
     * aus dem MediaStore, sonst aus der Uhr — eine hereingereichte Datei
     * muss keinen tragen.
     */
    fun remoteName(context: Context, uri: Uri, suffix: Char): Pair<String, String> {
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "jpg").lowercase()
        var takenMs = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val t = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val a = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    if (t >= 0) takenMs = c.getLong(t)
                    if (takenMs <= 0 && a >= 0) takenMs = c.getLong(a) * 1000
                }
            }
        }
        if (takenMs <= 0) takenMs = System.currentTimeMillis()
        val stamp = Stempel.datei(takenMs)
        return "$stamp-$suffix.$ext" to Stempel.jahr(stamp)
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(
        key: String,
    ): T? = if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent
    .getParcelableArrayListExtraCompat(key: String): ArrayList<T>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            getParcelableArrayListExtra(key)
        }
}
