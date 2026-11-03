package de.bgghome.mpd.api

/** Ein anzeigbares Foto der Lightbox — absolute URLs. Traegt seinen ganzen
 *  Kontext (space + aufgeloester Albumordner) selbst, damit die Lightbox
 *  auch ohne offenes Album funktioniert (Zeitstrahl). */
data class Photo(
    val file: String,
    val space: String,
    /** Aufgeloester Albumordner (source_album oder das offene Album) —
     *  Ziel fuer EXIF- und Edit-Endpunkte. */
    val album: String,
    val mUrl: String,
    val xlUrl: String,
    /** 4096 px, wenn der Server sie schon mitliefert — sonst null. */
    val fullUrl: String?,
    val originalUrl: String,
    val resolution: de.bgghome.mpd.api.Resolution? = null,
    /** In einem album.json referenziert? false = Datei liegt nur im Ordner. */
    val referenced: Boolean = true,
    /** true = Aufnahme vom Geraet, noch nicht auf der NAS. */
    val isLocal: Boolean = false,
    /**
     * Darf der Server diese Datei ueberschreiben? Nur die FORMATfrage —
     * seit 07.09.2026 zeigt MPD Formate an, die es nicht zurueckschreiben
     * kann (ein mehrseitiges .tif kaeme einseitig zurueck). Ob dieses
     * Konto es darf, ist eine andere Frage; der Stift braucht beides.
     */
    val editableFormat: Boolean = true,
)
