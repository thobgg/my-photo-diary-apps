package de.bgghome.mpd

import de.bgghome.mpd.data.PcOrdner
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.data.Stempel
import de.bgghome.mpd.ui.LokaleVorschau
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Ordner auf diesem PC (04.10.2026). dreh6.jpg ist 40x20 Pixel gross,
 * traegt EXIF-Drehung 6 (90° rechts) und Aufnahmezeit 2024-05-01 07:02:11.
 */
class PcOrdnerTest {

    private fun testbild(dir: File, name: String = "dreh6.jpg"): File {
        val f = File(dir, name)
        javaClass.getResourceAsStream("/dreh6.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /** Skia dreht beim Dekodieren selbst nach EXIF — darauf verlassen sich
     *  LokaleVorschau (dreht nicht) und die Lightbox (laedt das Original). */
    @Test fun skiaDrehtSelbst() {
        val bytes = javaClass.getResourceAsStream("/dreh6.jpg")!!.readBytes()
        val bild = Image.makeFromEncoded(bytes)
        assertEquals(20, bild.width, "Skia dreht nicht mehr selbst — Kacheln und Lightbox stuenden quer")
        assertEquals(40, bild.height)
    }

    @Test fun aufnahmezeitAusExif() {
        val dir = Files.createTempDirectory("pc").toFile()
        val f = testbild(dir)
        assertEquals("2024-05-01T07:02:11", Stempel.iso(PcOrdner.aufnahmezeit(f)))
    }

    @Test fun vorschauIstGedreht() = runBlocking {
        val dir = Files.createTempDirectory("pc").toFile()
        val f = testbild(dir)
        val vorschau = LokaleVorschau(File(dir, "cache"))
        val m = LokaleVorschau::class.java.getDeclaredMethod("vorschau", File::class.java).apply { isAccessible = true }
        val ziel = m.invoke(vorschau, f) as File
        val bild = Image.makeFromEncoded(ziel.readBytes())
        assertEquals(20, bild.width)
        assertEquals(40, bild.height)
    }

    @Test fun scanFindetMedienUndUeberspringtVersteckte() {
        val wurzel = Files.createTempDirectory("pc").toFile()
        testbild(wurzel, "a b ä.jpg")
        File(wurzel, "Urlaub").mkdirs(); testbild(File(wurzel, "Urlaub"), "c.JPG")
        File(wurzel, ".thumbnails").mkdirs(); testbild(File(wurzel, ".thumbnails"), "x.jpg")
        File(wurzel, "@eaDir").mkdirs(); testbild(File(wurzel, "@eaDir"), "y.jpg")
        File(wurzel, "notiz.txt").writeText("x")
        val settings = SettingsStore(SpeicherAblage()).apply { lokaleOrdner = setOf(wurzel.absolutePath) }
        val pc = PcOrdner(settings, File(wurzel, "../pc-cache-${wurzel.name}"), File(wurzel, "../pc-daten-${wurzel.name}"))
        val items = pc.scan()
        assertEquals(setOf("a b ä.jpg", "c.JPG"), items.map { it.file }.toSet())
        assertTrue(items.all { it.localUri!!.startsWith("file:") && it.localKey.startsWith("f:") })
        assertTrue(items.all { it.date == "2024-05-01T07:02:11" && it.album == wurzel.name })
        // Adresse mit Leerzeichen/Umlaut fuehrt zurueck zur Datei.
        val a = items.first { it.file == "a b ä.jpg" }
        assertTrue(File(java.net.URI(a.localUri!!)).isFile)
        assertEquals(2, pc.ordner().single().count)
    }
}

/** Einstellungen im Speicher, fuer Tests. */
private class SpeicherAblage : de.bgghome.mpd.data.Ablage {
    private val m = HashMap<String, Any?>()
    override fun getString(key: String, default: String?) = m[key] as String? ?: default
    override fun putString(key: String, value: String?) { m[key] = value }
    override fun getBoolean(key: String, default: Boolean) = m[key] as Boolean? ?: default
    override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    override fun getLong(key: String, default: Long) = m[key] as Long? ?: default
    override fun putLong(key: String, value: Long) { m[key] = value }
    override fun getInt(key: String, default: Int) = m[key] as Int? ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>) = m[key] as Set<String>? ?: default
    override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}
