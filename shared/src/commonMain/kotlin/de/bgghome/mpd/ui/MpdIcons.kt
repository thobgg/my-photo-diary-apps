package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Linien-Icons der App — 1:1 aus den SVGs des Web-Frontends portiert
 * (frontend/album.html: fab-menu, edit-mode-toggle). Kreise/Linien/Polygone
 * der SVGs sind in Pfad-Syntax umgeschrieben; gezeichnet wird weiss und per
 * Icon-tint eingefaerbt (LocalContentColor), wie currentColor im Web.
 */
object MpdIcons {

    private fun icon(
        name: String,
        strokeWidth: Float = 2f,
        fill: Boolean = false,
        vararg paths: String,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = if (fill) null else SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** edit-icon-pencil (Material-Stift, fill). */
    val Pencil by lazy {
        icon(
            "Pencil", fill = true,
            paths = arrayOf(
                "M3 17.25V21h3.75l11.06-11.06-3.75-3.75L3 17.25z" +
                    "M20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0" +
                    "l-1.83 1.83 3.75 3.75 1.83-1.83z",
            ),
        )
    }

    /** thumbnail-change-btn: Bild-Rahmen + Sonne + Berg (stroke 2). */
    val Image by lazy {
        icon(
            "Image", strokeWidth = 2f,
            paths = arrayOf(
                "M5 3 h14 a2 2 0 0 1 2 2 v14 a2 2 0 0 1 -2 2 h-14 a2 2 0 0 1 -2 -2 v-14 a2 2 0 0 1 2 -2 z",
                "M9 7 a2 2 0 1 0 0.001 0 z",
                "M21 15 l-3.086 -3.086 a2 2 0 0 0 -2.828 0 L6 21",
            ),
        )
    }

    /** edit-icon-check (stroke 3). */
    val Check by lazy { icon("Check", strokeWidth = 3f, paths = arrayOf("M20 6L9 17L4 12")) }

    /** fab-main „+". */
    val Plus by lazy { icon("Plus", paths = arrayOf("M12 5L12 19", "M5 12L19 12")) }

    /** fabAction('text'): T-Glyphe. */
    val TextT by lazy {
        icon(
            "TextT",
            paths = arrayOf("M4 7L4 4L20 4L20 7", "M9 20L15 20", "M12 4L12 20"),
        )
    }

    /** fabAction('photo'): Kamera. */
    val Camera by lazy {
        icon(
            "Camera",
            paths = arrayOf(
                "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z",
                "M16 13a4 4 0 1 1-8 0a4 4 0 1 1 8 0",
            ),
        )
    }

    /** fabAction('separator'): Linie. */
    val SeparatorLine by lazy { icon("SeparatorLine", paths = arrayOf("M5 12L19 12")) }

    /** Vollbild — vier Ecken nach aussen (Lucide maximize). */
    val Maximize by lazy {
        icon(
            "Maximize",
            paths = arrayOf(
                "M8 3H5a2 2 0 0 0-2 2v3",
                "M16 3h3a2 2 0 0 1 2 2v3",
                "M8 21H5a2 2 0 0 1-2-2v-3",
                "M16 21h3a2 2 0 0 0 2-2v-3",
            ),
        )
    }

    /** fabAction('map'): Faltkarte. */
    val Map by lazy {
        icon(
            "Map",
            paths = arrayOf(
                "M1 6L1 22L8 18L16 22L23 18L23 2L16 6L8 2Z",
                "M8 2L8 18", "M16 6L16 22",
            ),
        )
    }

    /** fabAction('audio'): Noten. */
    val Audio by lazy {
        icon(
            "Audio",
            paths = arrayOf(
                "M9 18V5l12-2v13",
                "M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
                "M21 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
            ),
        )
    }

    /** fabAction('document'): Blatt. */
    val Document by lazy {
        icon(
            "Document",
            paths = arrayOf(
                "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
                "M14 2L14 8L20 8", "M9 13L15 13", "M9 17L15 17",
            ),
        )
    }

    /** fabAction('link') und fabAction('mixed'): Kettenglieder. */
    val Link by lazy {
        icon(
            "Link",
            paths = arrayOf(
                "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
                "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
            ),
        )
    }

    /** fabAction('tour'): Route mit Start/Ziel. */
    val Tour by lazy {
        icon(
            "Tour",
            paths = arrayOf(
                "M9 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
                "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15",
                "M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
            ),
        )
    }

    private const val LOCK_BODY =
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"

    /** _lockIconSvg(true): Koerper gefuellt, Buegel geschlossen. */
    val LockClosed by lazy {
        ImageVector.Builder(
            name = "LockClosed",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(addPathNodes(LOCK_BODY), fill = SolidColor(Color.White))
            addPath(
                addPathNodes("M7 11V7a5 5 0 0 1 10 0v4"),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** _lockIconSvg(false): nur Konturen, Buegel offen. */
    val LockOpen by lazy {
        icon(
            "LockOpen", strokeWidth = 1.6f,
            paths = arrayOf(LOCK_BODY, "M7 11V7a5 5 0 0 1 9.9-1"),
        )
    }

    /** remove-photo-btn „×". */
    val Cross by lazy { icon("Cross", paths = arrayOf("M18 6L6 18", "M6 6L18 18")) }

    val ArrowBack by lazy {
        icon("ArrowBack", paths = arrayOf("M19 12L5 12", "M12 19L5 12L12 5"))
    }

    val Star by lazy {
        icon(
            "Star",
            paths = arrayOf(
                "M12 2.5L14.9 8.6L21.5 9.4L16.6 14L17.9 20.6L12 17.3L6.1 20.6L7.4 14L2.5 9.4L9.1 8.6L12 2.5",
            ),
        )
    }

    /** EXIF-Panel: Kalender. */
    val Calendar by lazy {
        icon(
            "Calendar",
            paths = arrayOf(
                "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
                "M16 2L16 6", "M8 2L8 6", "M3 10L21 10",
            ),
        )
    }

    /** EXIF-Panel: Ortsmarke. */
    val Pin by lazy {
        icon(
            "Pin",
            paths = arrayOf(
                "M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z",
                "M15 10a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
            ),
        )
    }

    /** EXIF-Panel: Sprechblase (Beschreibung). */
    val Chat by lazy {
        icon(
            "Chat",
            paths = arrayOf("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"),
        )
    }

    /** Info-Kreis (EXIF-Panel der Lightbox). */
    val Info by lazy {
        icon(
            "Info",
            paths = arrayOf(
                "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0",
                "M12 16L12 12", "M12 8L12.01 8",
            ),
        )
    }

    /** Editor: 90° drehen (rotate-cw). */
    val RotateCw by lazy {
        icon(
            "RotateCw",
            paths = arrayOf("M23 4v6h-6", "M20.49 15a9 9 0 1 1-2.12-9.36L23 10"),
        )
    }

    /** Editor: horizontal spiegeln (Achse + Pfeile). */
    val FlipH by lazy {
        icon(
            "FlipH",
            paths = arrayOf("M12 3L12 21", "M8 8L4 12L8 16", "M16 8L20 12L16 16"),
        )
    }

    /** Editor: vertikal spiegeln. */
    val FlipV by lazy {
        icon(
            "FlipV",
            paths = arrayOf("M3 12L21 12", "M8 8L12 4L16 8", "M8 16L12 20L16 16"),
        )
    }

    /** Editor: Zuschneiden (lb-ctx-crop). */
    val Crop by lazy {
        icon(
            "Crop",
            paths = arrayOf(
                "M6.13 1L6 16a2 2 0 0 0 2 2h15",
                "M1 6.13L16 6a2 2 0 0 1 2 2v15",
            ),
        )
    }

    /** Bottom-Navigation: Albenraster. */
    val AlbumsGrid by lazy {
        icon(
            "AlbumsGrid",
            paths = arrayOf("M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z"),
        )
    }

    /** Bottom-Navigation: Zeitstrahl (Uhr). */
    val Clock by lazy {
        icon(
            "Clock",
            paths = arrayOf("M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0", "M12 6L12 12L16 14"),
        )
    }

    /** Ortssuche (Lupe). */
    val Search by lazy {
        icon(
            "Search",
            paths = arrayOf("M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0", "M21 21L16.65 16.65"),
        )
    }

    /**
     * Durchgestrichene Wolke: „liegt nur auf dem Geraet, noch nicht auf der
     * NAS". Nur die Ausnahme wird markiert — Gesichertes traegt nichts,
     * sonst steht der ganze Strom voller Haekchen.
     */
    val CloudOff by lazy {
        icon(
            "CloudOff",
            paths = arrayOf(
                "M2 2L22 22",
                "M5.8 5.8A7 7 0 0 0 9 19h8.5a4.5 4.5 0 0 0 1.3-.2",
                "M21.5 16.5A4.5 4.5 0 0 0 17.5 10h-1.8A7 7 0 0 0 10 5.1",
            ),
        )
    }

    /** delete-file-btn (Web nutzt 🗑 — hier als Linien-Icon im Set). */
    val Trash by lazy {
        icon(
            "Trash",
            paths = arrayOf(
                "M3 6L21 6",
                "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
                "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
                "M10 11L10 17", "M14 11L14 17",
            ),
        )
    }

    // -- Bedien-Icons nach Material-Vorbild (08.09.2026) ----------------------
    //
    // Regel: „Bedienung einfarbig, Inhalt bunt." Die Symbole unten
    // ersetzen Emoji an Menues, Leisten, Knoepfen und Zustandsmarken. Sie
    // sind NACHGEZEICHNET — Linienfuehrung wie die vorhandenen Icons dieses
    // Sets (stroke 2, runde Enden), Formen nach den Material Symbols
    // Outlined gleichen Namens (menu, more_vert, palette, settings, group,
    // cloud_upload, inbox, share, mail, play_arrow, pause, download,
    // refresh, key, smartphone, folder, videocam, storage, explore, history,
    // bar_chart, local_fire_department, menu_book, expand_more,
    // chevron_right, radio_button_checked/unchecked, check_box/_outline_blank,
    // landscape). Keine Bibliothek: material-icons-extended kostete rund
    // 10 MB, weil der Release ohne Minify baut.

    /** Hauptmenue (menu). */
    val Menu by lazy { icon("Menu", paths = arrayOf("M3 6L21 6", "M3 12L21 12", "M3 18L21 18")) }

    /** Kachel- und Wartungsmenue (more_vert): drei gefuellte Punkte. */
    val MoreVert by lazy {
        icon(
            "MoreVert", fill = true,
            paths = arrayOf(
                "M12 3.5a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
                "M12 10.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
                "M12 17a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5z",
            ),
        )
    }

    /** Themes (palette) — Form wie theme-btn in album.html. */
    val Palette by lazy {
        icon(
            "Palette",
            paths = arrayOf(
                "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c3.31 0 6-2.69 6-6 0-4.96-4.49-9-10-9z",
                "M13.5 5.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z",
                "M17.5 9.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z",
                "M8.5 6.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z",
                "M6.5 11.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z",
            ),
        )
    }

    /** Einstellungen (settings). */
    /** Material „language": Globus mit Meridian und Breitenkreis. */
    val Language by lazy {
        icon(
            "Language",
            paths = arrayOf(
                "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z",
                "M2 12h20",
                "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z",
            ),
        )
    }

    val Settings by lazy {
        icon(
            "Settings",
            paths = arrayOf(
                "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
                "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
            ),
        )
    }

    /** Benutzer, Familie (group / groups). */
    val Users by lazy {
        icon(
            "Users",
            paths = arrayOf(
                "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2",
                "M13 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0",
                "M23 21v-2a4 4 0 0 0-3-3.87",
                "M16 3.13a4 4 0 0 1 0 7.75",
            ),
        )
    }

    /** Kamera-Backup (cloud_upload). */
    val CloudUpload by lazy {
        icon(
            "CloudUpload",
            paths = arrayOf(
                "M16 16l-4-4-4 4", "M12 12v9",
                "M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 4 16.3",
            ),
        )
    }

    /** Beisteuern, Unkuratiert (inbox). */
    val Inbox by lazy {
        icon(
            "Inbox",
            paths = arrayOf(
                "M22 12h-6l-2 3h-4l-2-3H2",
                "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
            ),
        )
    }

    /** Weitergeben ans System (share). */
    val Share by lazy {
        icon(
            "Share",
            paths = arrayOf(
                "M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
                "M9 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
                "M21 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
                "M8.59 13.51L15.42 17.49", "M15.41 6.51L8.59 10.49",
            ),
        )
    }

    /** E-Mail (mail). */
    val Mail by lazy {
        icon(
            "Mail",
            paths = arrayOf(
                "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
                "M22 6L12 13L2 6",
            ),
        )
    }

    /** Abspielen (play_arrow), gefuellt wie im Web (slideshow-btn). */
    val Play by lazy { icon("Play", fill = true, paths = arrayOf("M6 4L20 12L6 20z")) }

    /** Pause (pause), gefuellt. */
    val Pause by lazy { icon("Pause", fill = true, paths = arrayOf("M6 4h4v16H6z", "M14 4h4v16h-4z")) }

    /** Herunterladen (download). */
    val Download by lazy {
        icon(
            "Download",
            paths = arrayOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10L12 15L17 10", "M12 15V3"),
        )
    }

    /** Neu bauen, neu laden (refresh). */
    val Refresh by lazy {
        icon(
            "Refresh",
            paths = arrayOf(
                "M23 4v6h-6", "M1 20v-6h6",
                "M3.51 9a9 9 0 0 1 14.85-3.36L23 10",
                "M20.49 15a9 9 0 0 1-14.85 3.36L1 14",
            ),
        )
    }

    /** Passwort (key). */
    val Key by lazy {
        icon(
            "Key",
            paths = arrayOf(
                "M21 2L19 4",
                "M11.39 11.61a5.5 5.5 0 1 1-7.78 7.78a5.5 5.5 0 0 1 7.78-7.78z",
                "M11.39 11.61L15.5 7.5", "M15.5 7.5L18.5 10.5", "M22 7L19 4", "M15.5 7.5L19 4",
            ),
        )
    }

    /** Geraet (smartphone). */
    val Smartphone by lazy {
        icon(
            "Smartphone",
            paths = arrayOf(
                "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
                "M11.5 18L12.5 18",
            ),
        )
    }

    /** Ordner (folder) — Form wie SP_FOLDER in index.html. */
    val Folder by lazy {
        icon(
            "Folder",
            paths = arrayOf("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"),
        )
    }

    /** Video (videocam) — Form wie SP_VIDEO in index.html. */
    val Video by lazy {
        icon(
            "Video",
            paths = arrayOf(
                "M23 7L16 12L23 17L23 7z",
                "M3 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
            ),
        )
    }

    /** Speicher (storage). */
    val Storage by lazy {
        icon(
            "Storage",
            paths = arrayOf(
                "M22 12L2 12",
                "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
                "M6 16L6.01 16", "M10 16L10.01 16",
            ),
        )
    }

    /** Trails, bis eine Bildmarke existiert (explore: Kompass). */
    val Compass by lazy {
        icon(
            "Compass",
            paths = arrayOf(
                "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0",
                "M16.24 7.76L14.12 14.12L7.76 16.24L9.88 9.88L16.24 7.76z",
            ),
        )
    }

    /** Memories, bis eine Bildmarke existiert (history: Uhr mit Rueckpfeil). */
    val History by lazy {
        icon(
            "History",
            paths = arrayOf("M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5", "M12 7v5l4 2"),
        )
    }

    /** Stats (bar_chart) — Form wie SP_STATS in index.html. */
    val BarChart by lazy {
        icon("BarChart", paths = arrayOf("M12 20L12 10", "M18 20L18 4", "M6 20L6 16"))
    }

    /** Heatmap (local_fire_department). */
    val Fire by lazy {
        icon(
            "Fire",
            paths = arrayOf(
                "M12 22a7 7 0 0 0 7-7c0-4-3-6-4-9-1 2-2 3-2 5-1-2-2-3-2-6-3 3-6 6-6 10a7 7 0 0 0 7 7z",
            ),
        )
    }

    /** Album als Buch (menu_book) — Such-Treffer vom Typ Album. */
    val Book by lazy {
        icon(
            "Book",
            paths = arrayOf(
                "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z",
                "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z",
            ),
        )
    }

    /** Aufklappen (expand_more) / zugeklappt (chevron_right). */
    val ChevronDown by lazy { icon("ChevronDown", paths = arrayOf("M6 9L12 15L18 9")) }
    val ChevronRight by lazy { icon("ChevronRight", paths = arrayOf("M9 18L15 12L9 6")) }

    /** Auswahl: Radio an/aus, Kaestchen an/aus. */
    val RadioOn by lazy {
        ImageVector.Builder(
            name = "RadioOn", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                addPathNodes("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0"),
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            )
            addPath(addPathNodes("M16.5 12a4.5 4.5 0 1 1-9 0a4.5 4.5 0 1 1 9 0"), fill = SolidColor(Color.White))
        }.build()
    }
    val RadioOff by lazy { icon("RadioOff", paths = arrayOf("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0")) }
    val CheckBoxOn by lazy {
        icon(
            "CheckBoxOn",
            paths = arrayOf(
                "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M8 12L11 15L16.5 9",
            ),
        )
    }
    val CheckBoxOff by lazy {
        icon(
            "CheckBoxOff",
            paths = arrayOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"),
        )
    }

    /** QR-Code anzeigen (qr_code_2), nachgezeichnet. */
    val QrCode by lazy {
        icon(
            "QrCode",
            paths = arrayOf(
                "M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z",
                "M5.5 5.5h2v2h-2z", "M16.5 5.5h2v2h-2z", "M5.5 16.5h2v2h-2z",
                "M14 14h3v3h-3z", "M19 14h2v2h-2z", "M14 19h2v2h-2z", "M18 18h3v3h-3z",
            ),
        )
    }

    /** Karten-Ebene Gelaende (landscape) — Gegenstueck zu [Map]. */
    val Mountain by lazy {
        icon("Mountain", paths = arrayOf("M3 20L9 8L13 15L16 11L21 20L3 20z", "M13 15L14.5 13"))
    }
}
