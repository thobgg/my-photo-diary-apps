package de.bgghome.mpd

import de.bgghome.mpd.ui.isLocalOnlyAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Ein QR-Code, der beim Gast ins Leere laeuft, sagt das nicht von selbst —
 * er wird einfach gescannt, und dann passiert nichts. Deshalb erkennt die
 * App Adressen, die nur im eigenen Netz gelten, und schreibt es dazu.
 *
 * Falsch positiv waere aergerlich (unnoetige Warnung), falsch negativ waere
 * schlimmer (Code laeuft ins Leere, niemand weiss warum). Beide Richtungen
 * hier abgedeckt.
 */
class LocalAddressTest {

    @Test fun privateIPv4BereicheGeltenAlsLokal() {
        assertTrue(isLocalOnlyAddress("https://192.168.1.109:5001"))
        assertTrue(isLocalOnlyAddress("http://10.0.0.5"))
        assertTrue(isLocalOnlyAddress("https://172.16.0.1"))
        assertTrue(isLocalOnlyAddress("https://172.31.255.254"))
        assertTrue(isLocalOnlyAddress("http://127.0.0.1:8080"))
        assertTrue(isLocalOnlyAddress("http://169.254.10.1"))
    }

    /** 172.15 und 172.32 liegen AUSSERHALB von 172.16–31 — klassischer Patzer. */
    @Test fun nachbarnVon172SindOeffentlich() {
        assertFalse(isLocalOnlyAddress("https://172.15.0.1"))
        assertFalse(isLocalOnlyAddress("https://172.32.0.1"))
    }

    /** 192.169.x ist nicht privat, 11.x auch nicht. */
    @Test fun aehnlicheAberOeffentlicheAdressen() {
        assertFalse(isLocalOnlyAddress("https://192.169.1.1"))
        assertFalse(isLocalOnlyAddress("https://11.0.0.1"))
        assertFalse(isLocalOnlyAddress("https://9.255.255.255"))
    }

    @Test fun heimnetzNamenGeltenAlsLokal() {
        assertTrue(isLocalOnlyAddress("http://localhost:5001"))
        assertTrue(isLocalOnlyAddress("https://diskstation.local"))
        assertTrue(isLocalOnlyAddress("http://nas.fritz.box"))
        assertTrue(isLocalOnlyAddress("http://fritz.box"))
        assertTrue(isLocalOnlyAddress("https://server.lan"))
    }

    @Test fun echteAdressenGeltenAlsOeffentlich() {
        assertFalse(isLocalOnlyAddress("https://mpd.example.org"))
        assertFalse(isLocalOnlyAddress("https://meine-nas.dyndns.org:5001"))
        assertFalse(isLocalOnlyAddress("https://example.org/mpd"))
    }

    /** IPv6: Unique Local und Link-Local sind lokal, oeffentliche nicht. */
    @Test fun ipv6WirdErkannt() {
        assertTrue(isLocalOnlyAddress("http://[fd00::1]:5001"))
        assertTrue(isLocalOnlyAddress("http://[fe80::1]"))
        assertFalse(isLocalOnlyAddress("http://[2001:db8::1]"))
    }

    /** Unsinn darf nicht abstuerzen und nicht faelschlich warnen. */
    @Test fun kaputteEingabenSindHarmlos() {
        assertFalse(isLocalOnlyAddress(""))
        assertFalse(isLocalOnlyAddress("kein-url"))
        assertFalse(isLocalOnlyAddress("https://999.999.999.999"))
    }
}
