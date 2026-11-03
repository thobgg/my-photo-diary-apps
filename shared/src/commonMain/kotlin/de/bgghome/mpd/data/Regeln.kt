package de.bgghome.mpd.data

/*
 * Die Entscheidungsregeln der App — als reine Funktionen, ohne ViewModel,
 * ohne Android (06.09.2026).
 *
 * Anlass: An genau diesen Regeln sind heute drei Fehler entstanden, und
 * alle drei hat Thomas gefunden, nicht ich. Der schlimmste: Der
 * Loeschknopf in der Lightbox pruefte MIT Album-Kontext, die Aktion
 * dahinter OHNE — beides kompilierte, beides sah richtig aus, und aus dem
 * Album heraus loeschte es deshalb gar nicht.
 *
 * Solche Fehler faengt kein Blick, sondern nur ein Test. Testbar sind sie
 * aber erst, wenn sie nicht mehr im ViewModel-Zustand haengen: Hier
 * stehen sie mit allen Abhaengigkeiten in der Signatur, sodass jeder Fall
 * ohne Geraet durchgespielt werden kann.
 *
 * Das ViewModel bleibt der Ort, an dem der ZUSTAND liegt (Rolle,
 * Albenliste, Ledger) — es reicht ihn hier hinein und entscheidet nichts
 * selbst.
 */
internal object Regeln {

    // -- Rechte: ZUM ABBAU VORGESEHEN ---------------------------------------
    //
    // ACHTUNG, Stand 06.09.2026 abends: Diese beiden Funktionen sind bereits
    // VERALTET. Der Server hat seine Rollen am selben Tag geaendert — ein
    // Betrachter verwaltet seinen eigenen Bereich jetzt vollstaendig,
    // Teilen ist je Konto abschaltbar, Bearbeiten haengt nicht mehr am
    // Kurator-Recht. `personal → role != "viewer"` unten ist damit falsch:
    // es verbietet einem Betrachter etwas, das er darf.
    //
    // Sie stehen hier trotzdem, weil sie besser sind als der Zustand davor
    // (die App bot alles an und lief in 403). Aber sie sind ein Duplikat
    // fremder Logik, und genau das ist eingetreten, wovor die CLAUDE.md
    // warnt: zwei Kopien derselben Regel laufen auseinander — hier
    // innerhalb von sechs Stunden.
    //
    // ERSATZ STEHT BEREIT: /api/whoami liefert seit 3.0.272 `rights` je
    // Bestand als Namensliste. Sobald die App das liest, fallen diese
    // beiden Funktionen ersatzlos weg — samt ihrer Tests.

    /** Darf kuratieren: Alben gestalten, Dateien loeschen. */
    fun darfKuratieren(role: String, istDemo: Boolean, space: String): Boolean = when {
        istDemo -> false
        space == "shared" -> role == "admin"
        space == "personal" -> role != "viewer"
        else -> false // unbekannter Bestand: im Zweifel nichts erlauben
    }

    /** Darf beitragen: hochladen, einsortieren, Ausschuss wegwerfen. */
    fun darfBeitragen(role: String, istDemo: Boolean, space: String): Boolean = when {
        istDemo -> false
        space == "shared" -> role == "admin" || role == "editor"
        space == "personal" -> role != "viewer"
        else -> false
    }

    // -- Loeschen -----------------------------------------------------------

    /**
     * Darf diese Datei aus der aktuellen Ansicht geloescht werden?
     *
     * Regel vom 06.09.2026, woertlich: „im Timeline darf nie Medien
     * geloescht werden koennen, die in einem Albumordner mit album.json
     * sind." Und der zweite Teil, genauso wichtig: „auch Fotos in Alben
     * sollen geloescht werden koennen, nur eben in den Alben."
     *
     * @param nurAufGeraet Aufnahme liegt (noch) nicht auf der NAS. Sie geht
     *   ueber den Android-Papierkorb, nicht ueber den Server — hier immer
     *   erlaubt.
     * @param referenziert Datei steht in irgendeiner album.json. Eine Datei
     *   im Durchlauf kann per source_album in einem Album haengen, ohne
     *   dort zu liegen.
     * @param ausAlbum Die Ansicht IST das Album. Dann ist Loeschen der
     *   vorgesehene Weg, und die eigene Referenz zaehlt nicht — sonst
     *   koennte man ein Foto nie aus seinem eigenen Album entfernen.
     * @param darfBeitragen Ergebnis von [darfBeitragen] fuer diesen Bestand.
     * @param albumOrdner Alle Ordner MIT album.json als (space, name).
     *   `null` = Liste noch nicht geladen; dann gilt alles als geschuetzt,
     *   denn bei einem „nie" ist der sichere Standard der einzig moegliche.
     */
    fun darfLoeschen(
        nurAufGeraet: Boolean,
        referenziert: Boolean,
        ausAlbum: Boolean,
        space: String,
        album: String,
        darfBeitragen: Boolean,
        albumOrdner: Set<Pair<String, String>>?,
    ): Boolean {
        if (nurAufGeraet) return true
        if (!darfBeitragen) return false
        if (ausAlbum) return true
        if (referenziert) return false
        val bekannt = albumOrdner ?: return false
        return (space to album) !in bekannt
    }

    // -- Gemischter Strom ---------------------------------------------------

    /**
     * Welche Geraete-Aufnahmen sind NOCH NICHT auf der NAS?
     *
     * Drei Wege, absteigend nach Verlaesslichkeit — eine Aufnahme gilt als
     * gesichert, sobald einer greift:
     *  1. Das Backup-Ledger (MediaStore-id). Sicher, aber nur fuer das, was
     *     diese App hochgeladen hat, und weg nach einer Neuinstallation.
     *  2. Der Sekundenstempel: UploadWorker benennt beim Hochladen um, der
     *     Aufnahmezeitpunkt steht also im Server-Dateinamen.
     *  3. Originalname + Aufnahmetag — faengt den Altbestand, den frueher
     *     Synology Photos hochgeladen hat.
     *
     * Die gefaehrliche Richtung ist Nummer zwei: Wer faelschlich als
     * gesichert gilt, verschwindet aus der Uebersicht, und das faellt
     * niemandem auf.
     */
    fun <T> nurAufDemGeraet(
        lokal: List<T>,
        ledger: Set<String>,
        serverStempel: Set<String>,
        serverNameTag: Set<String>,
        ledgerSchluessel: (T) -> String,
        stempel: (T) -> String,
        nameTag: (T) -> String,
    ): List<T> = lokal.filter {
        ledgerSchluessel(it) !in ledger &&
            stempel(it) !in serverStempel &&
            nameTag(it) !in serverNameTag
    }

    /** Vergleichsschluessel „dateiname|tag", einheitlich kleingeschrieben. */
    fun nameTagSchluessel(dateiname: String, datum: String): String =
        dateiname.lowercase() + "|" + datum.take(10)
}
