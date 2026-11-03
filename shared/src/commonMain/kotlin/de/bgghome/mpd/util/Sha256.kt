package de.bgghome.mpd.util

import java.security.MessageDigest

/**
 * SHA-256 als Hex — die eine Implementierung (08.09.2026). Vorher stand
 * sie dreimal im Code: fuer den Zertifikats-Fingerprint (Grossbuchstaben,
 * MpdApi), den Cache-Dateinamen (Kleinbuchstaben, MpdApi) und den
 * Share-Token-Hash (Kleinbuchstaben, hier in util).
 *
 * Die Schreibweise ist KEINE Kosmetik: der Fingerprint wird gross
 * gespeichert (SettingsStore.trustedCertSha256) und bei jedem TLS-
 * Handshake gross verglichen; der Token-Hash muss klein sein, weil der
 * Server `hashlib.sha256(...).hexdigest()` so liefert. Wer die Vorgabe
 * aendert, verliert still das bestaetigte Zertifikat bzw. jeden Link.
 */
fun sha256Hex(data: ByteArray, uppercase: Boolean = false): String =
    MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { (if (uppercase) "%02X" else "%02x").format(it) }
