package de.bgghome.mpd.data

/*
 * Helligkeit, Kontrast und Saettigung — dieselbe Rechnung wie auf dem
 * Server (07.09.2026).
 *
 * VORGESCHICHTE. Bis heute waren diese drei Regler bei Geraete-Aufnahmen
 * ausgeblendet, mit dieser Begruendung im Code: „Pillow auf dem Server und
 * Androids ColorMatrix rechnen anders, und dann saehe dasselbe Foto vor
 * und nach dem Sichern verschieden aus." Der Satz war richtig — aber nur,
 * solange man Androids FERTIGE Bausteine benutzt. Er war kein Naturgesetz,
 * sondern eine Aussage ueber zwei Standardformeln.
 *
 * Pillows Rechnung steht offen da und laesst sich Zeile fuer Zeile
 * nachbauen. Genau das passiert hier — nicht ungefaehr, sondern mit
 * denselben Konstanten, derselben Reihenfolge und demselben Rundungsweg:
 *
 *   1. Helligkeit   out = f · in
 *   2. Kontrast     out = f · in + (1-f) · m
 *                   m = mittleres Grau des Bildes NACH Schritt 1
 *   3. Saettigung   out = f · in + (1-f) · grau(Pixel)
 *
 * Drei Feinheiten, an denen es sonst auseinanderlaeuft:
 *
 * - DER KONTRAST DREHT SICH UM DEN BILDMITTELWERT, nicht um mittleres
 *   Grau. Das ist der grosse Unterschied zur CSS-Formel (die bei 128
 *   dreht). Bei einem dunklen Bild mit Mittelwert 100 und Kontrast +50
 *   liegen die beiden Ergebnisse 14 Helligkeitsstufen auseinander —
 *   sichtbar, nicht theoretisch.
 * - DIE GRAUSTUFE benutzt ITU-R 601 (0,299/0,587/0,114), nicht die
 *   BT.709-Gewichte von ColorMatrix.setToSaturation. Pillow rechnet in
 *   Ganzzahlen und schneidet ab: L = (r·299 + g·587 + b·114) / 1000.
 * - PILLOW SCHNEIDET AB, es rundet nicht. Image.blend castet auf UINT8;
 *   bei einem Faktor ausserhalb 0..1 wird vorher geklemmt. Deshalb
 *   [mische] und nicht Math.round.
 *
 * Die Schritte laufen NACHEINANDER ueber 8-Bit-Werte, jeder mit eigener
 * Klemmung — sie in eine Matrix zusammenzufassen waere schneller und an
 * den Raendern falsch.
 *
 * Reine Funktionen ohne Android, damit ein Unit-Test sie vollstaendig
 * durchgehen kann. Die Bitmap-Arbeit steht in LocalPhotoEdit.
 */
internal object Tonwerte {

    /** Pillows rgb2l: ITU-R 601, Ganzzahl, abgeschnitten. */
    fun grau(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000

    /**
     * Pillows Image.blend(deg, img, f) fuer einen Kanal:
     * deg + f·(img - deg), auf 0..255 geklemmt und ABGESCHNITTEN.
     */
    fun mische(deg: Int, img: Int, f: Float): Int {
        val t = deg + f * (img - deg)
        return when {
            t <= 0f -> 0
            t >= 255f -> 255
            else -> t.toInt()
        }
    }

    /** Reglerwert (-100..100) → Pillow-Faktor. */
    fun faktor(regler: Int): Float = 1f + regler / 100f

    /**
     * Ein Pixel durch alle drei Schritte. [mittel] ist das mittlere Grau
     * des ganzen Bildes NACH der Helligkeit — deshalb kommt es von aussen.
     *
     * @return dasselbe ARGB-Format wie Bitmap.getPixels; Alpha bleibt.
     */
    fun pixel(argb: Int, helligkeit: Int, kontrast: Int, saettigung: Int, mittel: Int): Int {
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF

        if (helligkeit != 0) {
            val f = faktor(helligkeit)
            r = mische(0, r, f); g = mische(0, g, f); b = mische(0, b, f)
        }
        if (kontrast != 0) {
            val f = faktor(kontrast)
            r = mische(mittel, r, f); g = mische(mittel, g, f); b = mische(mittel, b, f)
        }
        if (saettigung != 0) {
            val f = faktor(saettigung)
            val l = grau(r, g, b)
            r = mische(l, r, f); g = mische(l, g, f); b = mische(l, b, f)
        }
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** Nur die Helligkeit — fuer den Durchgang, der den Mittelwert bildet. */
    fun grauNachHelligkeit(argb: Int, helligkeit: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (helligkeit == 0) return grau(r, g, b)
        val f = faktor(helligkeit)
        return grau(mische(0, r, f), mische(0, g, f), mische(0, b, f))
    }

    /** Pillows ImageStat.mean → int(mean + 0.5). */
    fun mittelwert(summe: Long, anzahl: Long): Int =
        if (anzahl <= 0) 128 else ((summe.toDouble() / anzahl) + 0.5).toInt()
}
