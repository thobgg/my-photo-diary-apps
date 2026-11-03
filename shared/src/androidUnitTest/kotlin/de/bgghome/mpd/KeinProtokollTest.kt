package de.bgghome.mpd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * DIE APP PROTOKOLLIERT NICHT, WAS DER NUTZER TUT.
 *
 * 16.09.2026: „Komplett dieses aus schnüffeln raus." Damit sind
 * alle `android.util.Log`-Ausgaben gefallen — auch die, die ich behalten
 * wollte, weil sie nur im Fehlerfall feuern.
 *
 * Der Grund ist nicht Aufräumen, sondern seine durchgängige Haltung: Was
 * er mit seinen Fotos tut, gehört ihm. Ein Protokoll im System-Log ist
 * von jeder App mit Leserechten einsehbar und überlebt die App; dass
 * darin nur Anzahlen und keine Dateinamen standen, ändert daran nichts.
 * Wie viele Fotos jemand gelöscht, geteilt oder im Papierkorb hat, ist
 * ebenfalls eine Auskunft.
 *
 * Fehler bleiben sichtbar — auf dem Bildschirm, wo der Nutzer sie sieht
 * und niemand sonst. Jede entfernte Zeile stand an einer Stelle, die
 * ohnehin einen Fehlertext zurückgibt oder eine Meldung setzt; die eine
 * Ausnahme (Vorschaubild einer Benachrichtigung) war absichtlich
 * unkritisch und im Code so kommentiert.
 *
 * Dieser Test hält den Zustand. Wer beim Suchen eines Fehlers eine
 * Logzeile braucht, baut sie ein und nimmt sie wieder heraus, bevor er
 * committet — der Test sagt es ihm.
 */
class KeinProtokollTest {

    private fun quellDateien(): List<File> {
        // Alle Quell-Sets (androidMain, commonMain, desktopMain), nicht die Tests.
        val wurzel = File("src")
        require(wurzel.isDirectory) { "Quellordner nicht gefunden: ${wurzel.absolutePath}" }
        return wurzel.walkTopDown().filter { it.isFile && it.extension == "kt" && it.path.contains("Main/") }.toList()
    }

    @Test
    fun `keine Log-Ausgaben im Quellcode`() {
        val muster = Regex("""(android\.util\.)?Log\s*\.\s*[vdiwe]\s*\(""")
        val treffer = quellDateien().flatMap { datei ->
            datei.readLines().withIndex()
                .filter { (_, zeile) -> muster.containsMatchIn(zeile) }
                .map { (nr, zeile) -> "${datei.name}:${nr + 1}: ${zeile.trim()}" }
        }
        assertEquals(
            "Die App protokolliert nicht, was der Nutzer tut — " +
                "Fehler gehören auf den Bildschirm, nicht ins System-Log:\n" +
                treffer.joinToString("\n"),
            emptyList<String>(),
            treffer,
        )
    }

    @Test
    fun `kein Import von android util Log`() {
        val treffer = quellDateien()
            .filter { it.readText().contains("import android.util.Log") }
            .map { it.name }
        assertEquals(emptyList<String>(), treffer)
    }
}
