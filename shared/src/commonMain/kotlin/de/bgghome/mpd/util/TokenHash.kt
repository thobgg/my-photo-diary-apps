package de.bgghome.mpd.util

/**
 * `sha256(token)` als Kleinbuchstaben-Hex — **exakt** das, was der Server als
 * `token_hash` fuehrt:
 *
 * ```python
 * # backend/core/shares.py
 * def _hash(token: str) -> str:
 *     return hashlib.sha256(token.encode("utf-8")).hexdigest()
 * ```
 *
 * Damit findet ein lokal gemerkter Klartext-Token seinen Listeneintrag
 * wieder, ohne dass der Server den Hash beim Anlegen mitliefern muesste.
 *
 * Weicht diese Berechnung auch nur in der Gross-/Kleinschreibung ab, findet
 * **kein einziger** Token mehr seinen Eintrag — und zwar lautlos: es gaebe
 * einfach nie die Zusatzaktionen. Deshalb der Test dazu.
 */
fun tokenHash(token: String): String =
    sha256Hex(token.toByteArray(Charsets.UTF_8), uppercase = false)
