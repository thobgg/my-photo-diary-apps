package de.bgghome.mpd

import de.bgghome.mpd.data.Regeln
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Die Regeln, an denen am 06.09.2026 drei Fehler entstanden sind — alle
 * drei von Thomas gefunden, keiner von mir. Jeder Fall hier entspricht
 * einem, den ich falsch hatte oder falsch haette haben koennen.
 */
class RegelnTest {

    private val alben = setOf("shared" to "2019-Toskana", "personal" to "2026")

    // -- Der Fehler vom Nachmittag ------------------------------------------

    @Test fun ausDemAlbumHerausDarfGeloeschtWerden() {
        // DAS war der Fehler: Der Knopf prueft MIT Kontext, die Aktion
        // pruefte OHNE. Beides kompilierte, und aus dem Album heraus
        // loeschte es deshalb gar nichts — genau dort, wo Thomas es
        // ausdruecklich erlaubt hat („nur eben in den Alben").
        assertTrue(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = true, ausAlbum = true,
                space = "shared", album = "2019-Toskana",
                darfBeitragen = true, albumOrdner = alben,
            ),
        )
    }

    @Test fun ausDerTimelineHerausNicht() {
        // Dieselbe Datei, andere Ansicht: hier gilt „nie".
        assertFalse(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = true, ausAlbum = false,
                space = "shared", album = "2019-Toskana",
                darfBeitragen = true, albumOrdner = alben,
            ),
        )
    }

    // -- „nie"-Regel, strenger als „nicht referenziert" -------------

    @Test fun unreferenziertImAlbumordnerBleibtGeschuetzt() {
        // Der Unterschied, den ich zuerst nicht gemacht hatte: In einem
        // Albumordner kann eine Datei liegen, die (noch) in keiner
        // album.json steht — frisch hochgeladen, aus einem Element
        // entfernt. Die waere nach „nicht referenziert" loeschbar gewesen.
        assertFalse(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = false, ausAlbum = false,
                space = "shared", album = "2019-Toskana",
                darfBeitragen = true, albumOrdner = alben,
            ),
        )
    }

    @Test fun ausschussImDurchlaufDarfWeg() {
        // Der eigentliche Zweck: Ausschuss im Durchlauf. Kein Albumordner,
        // nicht referenziert.
        assertTrue(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = false, ausAlbum = false,
                space = "shared", album = "2026",
                darfBeitragen = true, albumOrdner = alben,
            ),
        )
    }

    @Test fun referenzAusEinemAnderenAlbumSchuetztAuchImDurchlauf() {
        // Eine Datei im Durchlauf kann per source_album in einem Album
        // haengen, ohne dort zu liegen. Loeschen wuerde dort eine Luecke
        // reissen.
        assertFalse(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = true, ausAlbum = false,
                space = "shared", album = "2026",
                darfBeitragen = true, albumOrdner = alben,
            ),
        )
    }

    @Test fun ohneAlbenlisteGiltAllesAlsGeschuetzt() {
        // Bei einem „nie" ist der sichere Standard der einzig moegliche:
        // Wer die Liste nicht kennt, darf nicht loeschen.
        assertFalse(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = false, ausAlbum = false,
                space = "shared", album = "2026",
                darfBeitragen = true, albumOrdner = null,
            ),
        )
    }

    @Test fun ohneBeitragsrechtGehtNichts() {
        assertFalse(
            Regeln.darfLoeschen(
                nurAufGeraet = false, referenziert = false, ausAlbum = false,
                space = "shared", album = "2026",
                darfBeitragen = false, albumOrdner = alben,
            ),
        )
    }

    @Test fun geraeteAufnahmenGehenImmerUeberDenAndroidPapierkorb() {
        // Ohne Beitragsrecht, ohne Albenliste, referenziert — egal: die
        // Datei gehoert dem Geraet, nicht dem Server.
        assertTrue(
            Regeln.darfLoeschen(
                nurAufGeraet = true, referenziert = true, ausAlbum = false,
                space = "", album = "Camera",
                darfBeitragen = false, albumOrdner = null,
            ),
        )
    }

    // -- Rechte: die Randfaelle, die mir gefehlt haben ----------------------

    @Test fun imFamilienbestandKuratiertNurDerVerwalter() {
        assertTrue(Regeln.darfKuratieren("admin", false, "shared"))
        assertFalse(Regeln.darfKuratieren("editor", false, "shared"))
        assertFalse(Regeln.darfKuratieren("viewer", false, "shared"))
    }

    @Test fun mitwirkendeDuerfenImFamilienbestandBeitragen() {
        // Der Unterschied zwischen den beiden Stufen — ein Editor darf
        // hochladen und einsortieren, aber keine Alben gestalten.
        assertTrue(Regeln.darfBeitragen("editor", false, "shared"))
        assertFalse(Regeln.darfKuratieren("editor", false, "shared"))
    }

    @Test fun demokontenDuerfenNirgendsSchreiben() {
        for (rolle in listOf("admin", "editor", "viewer")) {
            for (bestand in listOf("shared", "personal")) {
                assertFalse("$rolle/$bestand", Regeln.darfKuratieren(rolle, true, bestand))
                assertFalse("$rolle/$bestand", Regeln.darfBeitragen(rolle, true, bestand))
            }
        }
    }

    @Test fun unbekannterBestandErlaubtNichts() {
        // Die Zeile, die man beim Nachbauen vergisst: alles ausser shared
        // und personal ist gesperrt.
        assertFalse(Regeln.darfKuratieren("admin", false, "irgendwas"))
        assertFalse(Regeln.darfBeitragen("admin", false, "irgendwas"))
    }

    // -- Gemischter Strom ---------------------------------------------------

    @Test fun schonGesicherteAufnahmenVerschwindenAusDemStrom() {
        val lokal = listOf("A", "B", "C", "D")
        val uebrig = Regeln.nurAufDemGeraet(
            lokal = lokal,
            ledger = setOf("i-A"),
            serverStempel = setOf("2026-06-21_11-24-30"),
            serverNameTag = setOf("c.jpg|2026-06-21"),
            ledgerSchluessel = { "i-$it" },
            stempel = { if (it == "B") "2026-06-21_11-24-30" else "x-$it" },
            nameTag = { if (it == "C") "c.jpg|2026-06-21" else "y-$it" },
        )
        // A ueber das Ledger, B ueber den Stempel, C ueber Name+Tag — nur D
        // liegt wirklich nur auf dem Geraet.
        assertTrue(uebrig == listOf("D"))
    }

    @Test fun schluesselIstUnabhaengigVonGrossschreibung() {
        assertTrue(
            Regeln.nameTagSchluessel("IMG_1234.JPG", "2026-06-21T11:24:30") ==
                Regeln.nameTagSchluessel("img_1234.jpg", "2026-06-21"),
        )
    }
}
