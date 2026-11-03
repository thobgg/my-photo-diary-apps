package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import java.io.IOException

/*
 * Die zwei Saetze, die das ViewModel am haeufigsten sagt — an EINER Stelle
 * (08.09.2026, Struktur-Audit Stufe 2, Block 3).
 *
 * Vorher stand „Server nicht erreichbar" fuenfzehnmal von Hand im
 * ViewModel, elfmal mit Ursache und viermal ohne, und der Modul-Hinweis
 * dreimal mit eingesetztem Modulnamen. Wer den Wortlaut aendern will,
 * aendert ihn jetzt hier.
 *
 * Wortlaut ist unveraendert — auch das „: null", das bei einer IOException
 * ohne Meldung entsteht; das war vorher so und ist keine Verbesserung
 * fuer diesen Umbau. Seit i18n (08.09.2026) kommen die Saetze aus den
 * Ressourcen ueber [Texte]; die Signaturen bleiben, weil Aufrufer in
 * anderen Bereichen liegen.
 */

/** Netzfehler; mit [e] samt Ursache, ohne nur der Satz. */
internal fun serverUnreachable(e: IOException? = null): String =
    if (e == null) Texte.t(Res.string.err_server_unreachable)
    else Texte.t(Res.string.err_server_unreachable_cause, e.message)

/** 403 an einem Modul-Endpunkt: das Konto hat das Modul nicht. */
internal fun moduleLockedText(name: String): String =
    Texte.t(Res.string.err_module_locked, name)
