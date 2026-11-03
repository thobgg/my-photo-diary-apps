package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.ui.theme.MpdThemeId
import org.jetbrains.compose.resources.stringResource

/*
 * Kopfzeile mit Avatar + Hamburger — Nachbau des Web-Headers (index.html,
 * Handy/Tablet-Variante). Avatar-Tap → Benutzermenue (Name, Rolle,
 * Abmelden); ☰ → Menue mit den Web-Sektionen: Anzeige (Space-Filter),
 * Entdecken (Module, noch deaktiviert), Werkzeuge (Themes).
 */

@Composable
fun AppHeader(
    vm: AppViewModel,
    who: WhoAmI,
    title: String = "My Photo Diary",
    /** Optionaler Inhalt zwischen Marke und Avatar (z.B. die Zaehl-Pills). */
    centerContent: (@Composable () -> Unit)? = null,
) {
    val colors = Mpd.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bild- + Wortmarke wie .header-brand im Web
        Image(
            painter = painterResource(Res.drawable.mpd_logo),
            contentDescription = null,
            modifier = Modifier.padding(end = 10.dp).size(42.dp),
        )
        Column {
            Text(
                title,
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Mpd.style.fontFamily,
            )
            Text(
                "where your memories stay yours.",
                color = colors.muted,
                fontSize = 11.sp,
            )
        }
        // Linke Gruppe: Zaehl-Pills, direkt daneben angedockt Such-Pille +
        // Stats-Knopf (Wunsch 02.09., wie die mpd-search-Pille im Web);
        // der Rest der Zeile bleibt leer, Avatar + ☰ docken rechts.
        val screenW = fensterBreiteDp()
        val tabletKopf = istTablet()
        // Tablet HOCHKANT: Tablet-Kopf, aber nur 924 dp breit (Tab S8 Ultra,
        // 1848 px bei Dichte 320, am Geraet nachgemessen 12.09.2026). Die
        // volle Reihe braucht mehr: Marke rund 230, Suchpille 240, vier
        // beschriftete Chips rund 400, Theme-Pille 90, Avatar und Menue 76 —
        // zusammen ueber 1000. Sie passte also nicht und wurde gequetscht.
        // Das war „viel zu breiter Balken". Quer (1480 dp) ist Platz.
        val eng = tabletKopf && screenW < 1000
        Row(
            Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            centerContent?.invoke()
            // Je Tab eine Theme-Pille (04.09.): Alben = App-Theme
            // (oeffnet den Waehler), Timeline = eigener Look (zykelt),
            // Memories = Farbwelt (zykelt). Such-Pille nur auf breiten
            // Schirmen und nicht auf der Pinnwand.
            val themePill: @Composable (Boolean) -> Unit = { compact ->
                when (vm.homeTab) {
                    0 -> AppThemePill(vm, compact)
                    1 -> TimelineThemePill(vm, compact)
                    // Tab 2 ist seit 05.09. die Suche — die hat keinen
                    // eigenen Look. Die Memories-Farbwelt sitzt in der
                    // Vollbild-Ansicht selbst.
                    else -> Unit
                }
            }
            when {
                tabletKopf -> {
                    if (vm.homeTab != 2 && !eng) {
                        SearchPill(vm, widthDp = 240, hint = stringResource(Res.string.header_search_hint))
                    }
                    // Dieselbe Reihe wie im Web (index.html, stat-pill),
                    // in derselben Reihenfolge — 08.09.2026: „die
                    // Reihenfolge der Pillen/Icons im Webview oben gehoert
                    // nochmals besprochen". Geordnet nach „wie oft schaut
                    // man hin", nicht nach Wichtigkeit:
                    //   Durchlauf — traegt eine Zahl und meldet wartende
                    //     Arbeit; das Einzige hier, was sich von selbst
                    //     meldet, gehoert nach vorn.
                    //   Trails — die Tagesansicht braucht man taeglich und
                    //     erreicht sie sonst erst zwei Ebenen tief.
                    //   Tours  — wird beim GESTALTEN gebraucht (Tour ins
                    //     Album einfuegen), und Gestalten ist Tablet-Sache.
                    //   Stats  — sieht man selten an. Stand am 08.09.2026
                    //     kurz nur im Menue; das war falsch, weil es die
                    //     Reihe im Web gibt und beide Haeuser dieselbe
                    //     Sprache sprechen muessen.
                    //   Themes — aendert das Aussehen, nicht den Inhalt,
                    //     und steht deshalb am Ende.
                    //
                    // Gesperrte Module erscheinen hier NICHT. Das Schloss
                    // gehoert ins Menue — das oeffnet man bewusst, dort ist
                    // ein Angebot am Platz. Der Kopf ist Arbeitsflaeche und
                    // immer sichtbar; ein toter Knopf stoert dort dauernd
                    // und wird trotzdem nicht gekauft.
                    //
                    // Durchlauf ist Grundpaket und deshalb ungeprueft. Er
                    // oeffnet keinen eigenen Bildschirm, sondern setzt den
                    // Zeitstrahl auf „Unkuratiert" — denselben Zustand, den
                    // der Filter oben rechts im Zeitstrahl schaltet. Der
                    // Kopf ist der Weg dorthin von AUSSERHALB des
                    // Zeitstrahls, etwa aus der Albenuebersicht.
                    HeaderChip(
                        MpdIcons.Inbox,
                        if (eng) null else stringResource(Res.string.nav_durchlauf),
                        count = vm.durchlaufCount,
                    ) {
                        vm.clearSelection()
                        vm.timelineUncuratedOnly = true
                        vm.switchHomeTab(1)
                    }
                    if (vm.moduleAllowed("trails")) {
                        HeaderChip(MpdIcons.Compass, if (eng) null else "Trails") {
                            vm.tryOpenModule("trails") { vm.trailsOpen = true }
                        }
                    }
                    if (vm.moduleAllowed("tours")) {
                        HeaderChip(MpdIcons.Tour, if (eng) null else "Tours") {
                            vm.tryOpenModule("tours") { vm.toursOpen = true }
                        }
                    }
                    if (vm.moduleAllowed("stats")) {
                        HeaderChip(MpdIcons.BarChart, if (eng) null else "Stats") {
                            vm.tryOpenModule("stats") { vm.stats.open = true }
                        }
                    }
                    themePill(eng)
                }
                screenW >= 600 -> {
                    if (vm.homeTab != 2) SearchPill(vm, widthDp = 170, hint = stringResource(Res.string.header_search_hint_short))
                    themePill(false)
                    HeaderChip(MpdIcons.BarChart, null) { vm.tryOpenModule("stats") { vm.stats.open = true } }
                }
                // Handy hochkant: zwischen Marke und Avatar sind nur ~60 dp
                // — dort passt nur das Emoji („kleines antippbares
                // Auswahl-Element").
                else -> themePill(true)
            }
        }
        // Handy hochkant: keine Lupe im Kopf (04.09.) — Suchen
        // wohnt dort im ☰-Menue; auf Memories zeigt die Mitte die
        // Farbwelt-Pille.
        UserAvatar(vm, who)
        HamburgerMenu(vm)
    }
}

/**
 * Tablet oder nicht — die eine Regel fuer alle Bildschirme.
 *
 * Bis zum 12.09.2026 stand hier `screenWidthDp >= 840 && screenHeightDp >=
 * 480`, also die AKTUELLE Breite. Fuer das Tab S8 Ultra (924 × 1480 dp)
 * aendert das nichts — es lag in beiden Ausrichtungen darueber. Betroffen
 * waren die kleineren Tablets zwischen 600 und 839 dp: hochkant bekamen
 * sie den Handy-Aufbau mit einer zweiten Zeile fuer die Zaehl-Pillen, quer
 * den Tablet-Kopf. Dieselbe Frage zweimal verschieden beantwortet.
 *
 * `smallestScreenWidthDp` ist die kuerzere der beiden Kanten und dreht sich
 * mit dem Geraet NICHT mit — dieselbe Zahl, die Android fuer `sw600dp`
 * benutzt. Ein Handy quer (S25 Ultra: 832 × 384) bleibt damit ein Handy,
 * ein Tablet bleibt in beiden Ausrichtungen ein Tablet.
 */
@Composable
internal fun istTablet(): Boolean = kleinsteFensterSeiteDp() >= 600

/**
 * Fenstergroesse in dp aus LocalWindowInfo, auf beiden Plattformen
 * (Aufteilung 22.09.2026; vorher die Android-Konfiguration, die es auf
 * dem Desktop nicht gibt). Am Handy ist das Fenster der Bildschirm.
 */
@Composable
internal fun fensterBreiteDp(): Int {
    val size = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    return with(androidx.compose.ui.platform.LocalDensity.current) { size.width.toDp().value.toInt() }
}

@Composable
internal fun kleinsteFensterSeiteDp(): Int {
    val size = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    return with(androidx.compose.ui.platform.LocalDensity.current) {
        minOf(size.width, size.height).toDp().value.toInt()
    }
}

/** Ueber-Dialog — Gegenstueck zu _openAboutDialog im Web. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val version = LocalSystemDienste.current.versionName
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My Photo Diary") },
        text = {
            Column {
                Text("where your memories stay yours.", fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.about_version, version), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("© 2026 Thomas Bugge", fontSize = 13.sp)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
    )
}

/** App-Theme-Pille (Alben-Tab): zeigt das aktive Theme, Tipp oeffnet
 *  den Waehler — sieben Themes sind zum Durchzykeln zu viele. */
@Composable
private fun AppThemePill(vm: AppViewModel, compact: Boolean = false) {
    ThemePillShell(
        label = if (compact) vm.themeId.icon else "${vm.themeId.icon} ${stringResource(vm.themeId.labelRes)}",
    ) { vm.themePickerOpen = true }
}

private val TIMELINE_THEME_LABELS = mapOf(
    "dunkel" to Res.string.tl_theme_dark, "hell" to Res.string.tl_theme_light, "auto" to Res.string.tl_theme_auto,
)

/** Timeline-Look-Pille: Tipp zykelt Dunkel → Hell → Auto. */
@Composable
private fun TimelineThemePill(vm: AppViewModel, compact: Boolean = false) {
    val full = stringResource(TIMELINE_THEME_LABELS[vm.timelineTheme] ?: Res.string.tl_theme_dark)
    ThemePillShell(if (compact) full.substringBefore(" ") else full) {
        vm.cycleTimelineTheme()
    }
}

/**
 * Gemeinsame Huelle der Theme-Pillen.
 *
 * Traegt seit 08.09.2026 dieselbe Flaeche wie [HeaderChip] — Rand,
 * Fuellung, Radius. Vorher war sie ein nackter Text zwischen umrandeten
 * Pillen, obwohl sie dasselbe tut: einen Einstieg oeffnen. Rueckmeldung: „das
 * Theme-Logo koennte den Flaechen Stats, Tours, Trails und Durchlauf
 * angeglichen werden." Derselbe Befund gilt im Web, wo der Theme-Knopf
 * als nacktes Icon zwischen Avatar und Hamburger sass.
 */
@Composable
private fun ThemePillShell(label: String, onClick: () -> Unit) {
    val colors = Mpd.colors
    Box(
        Modifier
            .padding(start = 10.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.text, fontSize = 14.sp, maxLines = 1)
    }
}

/** Farbwelt-Pille (nur auf dem Memories-Tab): Tipp zykelt durch die
 *  sechs Pinnwand-Farbwelten — die feine Auswahl wohnt in den
 *  Einstellungen („Memories-Farbwelt"). */
@Composable
internal fun MemoriesThemePill(vm: AppViewModel, compact: Boolean = false) {
    val full = MEMORIES_THEME_LABELS[vm.memories.theme] ?: "🎨 Auto"
    ThemePillShell(if (compact) full.substringBefore(" ") else full) {
        vm.memories.cycleTheme()
    }
}

/** Memories-Quelle als Pille auf der Pinnwand: Familie / Meine / Beides.
 *  Ein Menue statt Durchtippen — jeder Wechsel schreibt die Konto-
 *  einstellung und laedt neu, das soll nicht dreimal passieren. */
@Composable
internal fun MemoriesScopePill(vm: AppViewModel) {
    val scope = vm.userSettings?.memoriesScope
    var menu by remember { mutableStateOf(false) }
    val optionen = listOf(
        "both" to stringResource(Res.string.settings_scope_both),
        "shared" to stringResource(Res.string.settings_scope_shared),
        "personal" to stringResource(Res.string.settings_scope_personal),
    )
    Box {
        ThemePillShell(optionen.firstOrNull { it.first == scope }?.second ?: "…") {
            if (scope != null) menu = true
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            optionen.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text((if (key == scope) "✓ " else "") + label) },
                    onClick = {
                        menu = false
                        if (key != scope) vm.setMemoriesScope(key) { err -> if (err != null) vm.meldung(err) }
                    },
                )
            }
        }
    }
}

/** Such-Pille wie .mpd-search-wrap im Web — Tipp oeffnet die Suchseite. */
@Composable
private fun SearchPill(vm: AppViewModel, widthDp: Int, hint: String) {
    val colors = Mpd.colors
    Row(
        modifier = Modifier
            .padding(start = 10.dp)
            .width(widthDp.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(19.dp))
            .clickable { vm.tryOpenModule("search") { vm.search.open = true } }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MpdIcons.Search, null,
            Modifier.padding(end = 8.dp).size(15.dp), tint = colors.muted,
        )
        Text(hint, color = colors.muted, fontSize = 13.sp, maxLines = 1)
    }
}

/** Modul-Knopf in der Kopfzeile (z.B. 📊 Stats) — ab Tablet-Breite. */
@Composable
private fun HeaderChip(
    icon: ImageVector,
    label: String?,
    /** Zahl hinter dem Namen (" · 7"), wie `durchlauf-pill-count` im
     *  Web. null oder 0 = nichts anzeigen: ein dauerhaftes „· 0" waere
     *  Rauschen, und leer heisst hier ohnehin „nichts zu tun". */
    count: Int? = null,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    Row(
        modifier = Modifier
            .padding(start = 10.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label ?: "Stats", Modifier.size(15.dp), tint = colors.text)
        if (label != null) {
            Text(
                label + if (count != null && count > 0) " · $count" else "",
                color = Mpd.colors.text,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun UserAvatar(vm: AppViewModel, who: WhoAmI) {
    val colors = Mpd.colors
    var open by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.blue)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                who.user.take(1).uppercase().ifBlank { "?" },
                color = colors.textOnAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(who.user, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(who.role, color = colors.muted, fontSize = 12.sp)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.header_sign_out)) },
                onClick = { open = false; vm.logout() },
            )
        }
    }
}

@Composable
private fun HamburgerMenu(vm: AppViewModel) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    var open by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    Box(Modifier.padding(start = 8.dp)) {
        Icon(
            MpdIcons.Menu, stringResource(Res.string.header_menu),
            modifier = Modifier
                .clip(CircleShape)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .size(24.dp),
            tint = colors.text,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSection(stringResource(Res.string.menu_section_view))
            // Drei Chips in EINER Zeile statt drei Zeilen (08.09.2026): mit
            // dem Sprachumschalter war das Menue am Handy laenger als der
            // Bildschirm, die Fusszeile mit Version und Autor rutschte unter
            // die Kante — „im Hamburger-Menue ganz unten steht es im Tablet,
            // nicht am Handy". Zwei Zeilen gespart, Verhalten gleich.
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((value, label) in listOf(
                    null to Res.string.menu_filter_all,
                    "shared" to Res.string.menu_filter_shared,
                    "personal" to Res.string.menu_filter_personal,
                )) {
                    ChoicePill(
                        stringResource(label), active = vm.spaceFilter == value, compact = true,
                    ) { vm.spaceFilter = value; open = false }
                }
            }

            HorizontalDivider()
            MenuSection(stringResource(Res.string.menu_section_discover))
            // Gesperrte Module bleiben sichtbar (Schloss) und oeffnen das
            // Schaufenster — Basis frei, Module kosten (ModuleShowcase.kt).
            val lock: (String) -> Boolean = { !vm.moduleAllowed(it) }
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Search, stringResource(Res.string.menu_search), locked = lock("search")) },
                onClick = { open = false; vm.tryOpenModule("search") { vm.search.open = true } },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Tour, "Tours", locked = lock("tours")) },
                onClick = { open = false; vm.tryOpenModule("tours") { vm.toursOpen = true } },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.History, "Memories", locked = lock("memories")) },
                onClick = { open = false; vm.tryOpenModule("memories") { vm.memories.open = true } },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.BarChart, "Stats", locked = lock("stats")) },
                onClick = { open = false; vm.tryOpenModule("stats") { vm.stats.open = true } },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Compass, "Trails", locked = lock("trails")) },
                onClick = { open = false; vm.tryOpenModule("trails") { vm.trailsOpen = true } },
            )

            HorizontalDivider()
            MenuSection(stringResource(Res.string.menu_section_tools))
            // Kamera-Backup ist in die Einstellungen umgezogen (Thomas
            // 03.09.) — Themes bleibt wie im Web direkt im Menue.
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Palette, stringResource(Res.string.menu_themes)) },
                onClick = { open = false; vm.themePickerOpen = true },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Inbox, stringResource(Res.string.menu_contrib)) },
                onClick = { open = false; vm.contribOpen = true },
            )
            // Papierkorb des GERAETS — die NAS hat einen eigenen, den MPD
            // nicht auflisten kann (kein Loesch-Journal). Der Untertitel
            // im Screen sagt das; hier steht deshalb der schlichte Name.
            if (vm.plattform.hatGeraetefotos) DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Trash, stringResource(Res.string.menu_trash)) },
                onClick = { open = false; vm.zeigePapierkorb() },
            )
            // Desktop: Ordner auf diesem PC fuer den Zeitstrahl (04.10.2026).
            if (vm.plattform.hatLokaleOrdner) DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Folder, stringResource(Res.string.pc_folders_title)) },
                onClick = { open = false; vm.timelineFoldersOpen = true },
            )
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Settings, stringResource(Res.string.menu_settings)) },
                onClick = { open = false; vm.settingsOpen = true },
            )
            // Web-Paritaet (22.09.2026): Hilfe = Benutzerhandbuch vom Server
            // im eigenen PDF-Viewer; „Handy verbinden" = QR wie verbinden.html,
            // sinnvoll dort, wo ein Handy den Bildschirm abscannen kann.
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Book, stringResource(Res.string.menu_help)) },
                onClick = { open = false; vm.openPdfUrl("My Photo Diary", vm.baseUrl + "/hilfe.pdf") },
            )
            if (dienste.webIdiom || istTablet()) {
                DropdownMenuItem(
                    text = { MenuLabel(MpdIcons.QrCode, stringResource(Res.string.menu_connect)) },
                    onClick = { open = false; vm.connectOpen = true },
                )
            }
            val role = (vm.state as? de.bgghome.mpd.AppState.LoggedIn)?.who?.role
            if (role == "admin") {
                DropdownMenuItem(
                    text = { MenuLabel(MpdIcons.Users, stringResource(Res.string.menu_users)) },
                    onClick = { open = false; vm.users.open = true },
                )
            }

            // Sprachumschalter wie im Web (hamburger-lang-toggle): letzter
            // Eintrag, zeigt die jeweils ANDERE Sprache. Ab Android 13 landet
            // die Wahl beim System und steht auch in den Einstellungen.
            DropdownMenuItem(
                text = { MenuLabel(MpdIcons.Language, stringResource(Res.string.menu_language_toggle)) },
                onClick = { open = false; dienste.toggleSprache() },
            )

            // Fusszeile wie das mpd-version-badge im Web: Version + Autor,
            // Tipp oeffnet den Ueber-Dialog.
            HorizontalDivider()
            val version = LocalSystemDienste.current.versionName
            Text(
                "My Photo Diary $version · © Thomas Bugge",
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = false; aboutOpen = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (aboutOpen) {
        AboutDialog { aboutOpen = false }
    }

    if (vm.themePickerOpen) {
        ThemePickerDialog(vm) { vm.themePickerOpen = false }
    }
    if (vm.connectOpen) {
        ConnectDialog(vm) { vm.connectOpen = false }
    }
}


@Composable
private fun MenuSection(label: String) {
    Text(
        label.uppercase(),
        color = Mpd.colors.muted,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun MenuLabel(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    /** Modul nicht freigeschaltet: kleines Schloss hinter dem Namen. */
    locked: Boolean = false,
) {
    val colors = Mpd.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null, Modifier.padding(end = 10.dp).size(18.dp),
            tint = if (enabled) colors.text else colors.muted,
        )
        Text(label, color = if (enabled) colors.text else colors.muted)
        if (locked) {
            Icon(
                MpdIcons.LockClosed, stringResource(Res.string.module_locked_short),
                Modifier.padding(start = 8.dp).size(14.dp), tint = colors.muted,
            )
        }
    }
}

/** Theme-Auswahl — Gegenstueck zum #theme-picker des Webs. */
@Composable
private fun ThemePickerDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.theme_dialog_title)) },
        text = {
            Column {
                MpdThemeId.entries.forEach { theme ->
                    val active = vm.themeId == theme
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(if (active) Mpd.colors.bluePale else Color.Transparent)
                            .clickable { vm.setTheme(theme); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(theme.icon, fontSize = 16.sp, modifier = Modifier.padding(end = 12.dp))
                        Text(
                            stringResource(theme.labelRes),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) } },
    )
}

/** „Handy verbinden": QR mit mpd://setup?url=… — die App auf dem Handy
 *  uebernimmt die Adresse (Gegenstueck zu verbinden.html im Web). */
@Composable
private fun ConnectDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val dienste = LocalSystemDienste.current
    val link = "mpd://setup?url=" + vm.baseUrl
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.connect_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.connect_lead), fontSize = 13.sp)
                Box(
                    Modifier.padding(vertical = 12.dp).background(QrPaper, RoundedCornerShape(8.dp)).padding(8.dp),
                ) { QrCodeImage(link, modifier = Modifier.size(220.dp)) }
                Text(vm.baseUrl, color = Mpd.colors.muted, fontSize = 12.sp)
                Text(stringResource(Res.string.connect_step), color = Mpd.colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                dienste.kopiere(stringResource_connectLabel, vm.baseUrl) { vm.meldung(it, kurz = true) }
            }) { Text(stringResource(Res.string.connect_copy)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) } },
    )
}
private const val stringResource_connectLabel = "URL"
