package de.bgghome.mpd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * RUECKMELDUNGEN SIND SNACKBARS, KEINE TOASTS.
 *
 * CI v1.2, Android: „Rueckmeldung: Snackbar im Fenster, nicht Toast —
 * sichtbar zuzuordnen und mit Rueckgaengig." Am 08.09.2026 waren es 46
 * Toasts in 15 Dateien, und die Umstellung galt als erledigt. Am
 * 16.09.2026 standen trotzdem wieder 15 im Code — einige uebrig
 * geblieben, andere neu dazugekommen, weil beim Bauen niemand daran
 * dachte. Ohne diesen Test waere es beim naechsten Mal wieder so.
 *
 * Ein Toast schwebt losgeloest ueber allem, laesst sich nicht zuordnen
 * und nicht rueckgaengig machen. Die Snackbar steht unten im Fenster der
 * App, und `vm.meldung()` ist die eine Stelle dafuer.
 */
class SnackbarStattToastTest {

    @Test
    fun `keine Toasts im Quellcode`() {
        // Alle Quell-Sets (androidMain, commonMain, desktopMain), nicht die Tests.
        val wurzel = File("src")
        require(wurzel.isDirectory) { "Quellordner nicht gefunden: ${wurzel.absolutePath}" }
        val treffer = wurzel.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("Main/") }
            .flatMap { datei ->
                datei.readLines().withIndex()
                    .filter { (_, z) -> z.contains("Toast.makeText") || z.contains("import android.widget.Toast") }
                    .map { (nr, z) -> "${datei.name}:${nr + 1}: ${z.trim()}" }
            }
            .toList()
        assertEquals(
            "Rueckmeldungen gehoeren in vm.meldung(), nicht in einen Toast:\n" +
                treffer.joinToString("\n"),
            emptyList<String>(),
            treffer,
        )
    }
}
