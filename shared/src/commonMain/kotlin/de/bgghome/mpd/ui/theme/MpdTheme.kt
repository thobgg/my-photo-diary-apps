package de.bgghome.mpd.ui.theme

import org.jetbrains.compose.resources.StringResource

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * MPD-Themes — Port der CSS-Variablen aus frontend/css/style.css und der
 * Theme-Liste aus theme.js. Ein Theme = Palette (--bg, --surface, …) plus
 * „Personality" (--cover-ratio, --card-radius, --grid-gap, Schrift).
 *
 * Zugriff ueberall via `Mpd.colors` / `Mpd.style` — KEINE Farbkonstanten
 * mehr in den Screen-Dateien.
 *
 * stack/honeycomb/filmstrip haben im Web zusaetzlich eigene GRID-LAYOUTS
 * (Polaroid-Stapel, Waben, Filmstreifen); hier wirken vorerst nur Palette
 * und Personality — die Spezial-Layouts sind ein eigenes Kapitel.
 */

data class MpdPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val blue: Color,
    val blueMid: Color,
    val bluePale: Color,
    val gold: Color,
    val goldLt: Color,
    /** Dunkler Kopfzeilen-/Akzentgrund (Web --navy). */
    val navy: Color,
    val textOnAccent: Color,
    /** true = dunkle Grundstimmung (fuer Statusbar-Icons u. ae.). */
    val dark: Boolean,
)

data class MpdStyle(
    /** Hoehe/Breite des Album-Covers (Web --cover-ratio, 1.0 = quadratisch). */
    val coverRatio: Float,
    val cardRadius: Dp,
    val gridGap: Dp,
    val titleSize: TextUnit,
    val metaSize: TextUnit,
    val letterSpacing: TextUnit,
    val titleWeight: FontWeight,
    val fontFamily: FontFamily,
    /** Kachel-Look: Cover fuellt (Crop) statt Letterbox mit Blur-Unterlage. */
    val coverFill: Boolean = false,
    /** Kachel-Look: keine space-Badge/Beschreibung, transparente Karte. */
    val minimalCard: Boolean = false,
    /** Web blendet .space-badge auch in stack/honeycomb aus (style.css). */
    val showSpaceBadge: Boolean = true,
)

/** [labelRes] statt festem Text (i18n 08.09.2026): Kacheln/Stapel/Waben/
 *  Filmstreifen sind deutsch, die uebrigen Namen sind Marken. */
enum class MpdThemeId(val labelRes: StringResource, val icon: String) {
    KACHEL(Res.string.theme_kachel, "▦"),
    DARK(Res.string.theme_dark, "🌙"),
    LIGHT(Res.string.theme_light, "☀️"),
    SEPIA(Res.string.theme_sepia, "📖"),
    NORDIC(Res.string.theme_nordic, "❄️"),
    SUNSET(Res.string.theme_sunset, "🌅"),
    STACK(Res.string.theme_stack, "🗂️"),
    HONEYCOMB(Res.string.theme_honeycomb, "⬡"),
    FILMSTRIP(Res.string.theme_filmstrip, "🎞️"),
    ;

    companion object {
        /** Web-Default ist 'kachel' (theme.js initTheme). */
        val DEFAULT = KACHEL

        fun fromName(name: String?): MpdThemeId =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/*
 * Hausschrift (CI v1.2, Abschnitt 3, beschlossen 08.09.2026). Dieselben
 * vier Familien wie im Web, damit Browser und App dasselbe Bild zeigen —
 * dort seit Server 3.0.300 live, hier ab heute. Alle vier stehen unter
 * der SIL Open Font License 1.1: Buendelung in der APK ist erlaubt, ein
 * Hinweis in der Oberflaeche nicht noetig.
 *
 * Die Dateien kommen aus docs/fonts/ im MPD-Repo; hier liegen die
 * statischen Schnitte, nicht die variablen — Compose kann variable
 * Achsen erst ab API 26 mit FontVariation, und die statischen sind aus
 * denselben Quellen erzeugt.
 */
/**
 * Die vier Familien als Compose-Ressourcen (composeResources/font), damit
 * dieselben Dateien auf Android und Desktop gelten (Aufteilung 22.09.2026).
 * `Font(Res.font.…)` ist composable — die Familien entstehen deshalb in
 * der Komposition (MpdTheme) und werden ueber MpdThemeSpec gereicht,
 * nicht mehr als Konstanten beim Klassenladen.
 */
class MpdFonts(
    val sans: FontFamily,
    /** Lesetexte — im Web `default`, `info`, `quote`, `note`. */
    val serif: FontFamily,
    /** Filmstreifen, Dateinamen, Code. */
    val mono: FontFamily,
    /**
     * Handschrift — laut CI **nur** in Memories (Ueberschrift der Ansicht
     * und die Polaroid-Unterschrift). Sonst nirgends.
     */
    val handwriting: FontFamily,
)

@Composable
private fun rememberMpdFonts(): MpdFonts {
    val sans = FontFamily(
        Font(Res.font.archivoregular, FontWeight.Normal),
        Font(Res.font.archivoitalic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.archivosemibold, FontWeight.SemiBold),
        Font(Res.font.archivobold, FontWeight.Bold),
        Font(Res.font.archivobolditalic, FontWeight.Bold, FontStyle.Italic),
    )
    val serif = FontFamily(
        Font(Res.font.literataregular, FontWeight.Normal),
        Font(Res.font.literataitalic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.literatasemibold, FontWeight.SemiBold),
        Font(Res.font.literatabold, FontWeight.Bold),
        Font(Res.font.literatabolditalic, FontWeight.Bold, FontStyle.Italic),
    )
    val mono = FontFamily(
        Font(Res.font.courierprimeregular, FontWeight.Normal),
        Font(Res.font.courierprimeitalic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.courierprimebold, FontWeight.Bold),
        Font(Res.font.courierprimebolditalic, FontWeight.Bold, FontStyle.Italic),
    )
    val handwriting = FontFamily(Font(Res.font.playwritedevaregular))
    return remember(sans, serif, mono, handwriting) { MpdFonts(sans, serif, mono, handwriting) }
}

/** Ohne Komposition (Vorgabe der CompositionLocal): Systemschriften. */
private val FALLBACK_FONTS = MpdFonts(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)

private fun palette(id: MpdThemeId): MpdPalette = when (id) {
    MpdThemeId.DARK -> MpdPalette(
        bg = Color(0xFF0A0A0A), surface = Color(0xFF1A1A1A), surfaceAlt = Color(0xFF222222),
        text = Color(0xFFE0E0E0), muted = Color(0xFF888888), border = Color(0xFF333333),
        blue = Color(0xFF2E75B6), blueMid = Color(0xFF4F81BD), bluePale = Color(0x262E75B6),
        gold = Color(0xFFC8922A), goldLt = Color(0xFFE0A83A),
        navy = Color(0xFF1A1A1A),
        textOnAccent = Color.White, dark = true,
    )
    MpdThemeId.LIGHT -> MpdPalette(
        bg = Color(0xFFFAFBFC), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFEEF2F7),
        text = Color(0xFF1A2332), muted = Color(0xFF7B8A9E), border = Color(0xFFE3E9F0),
        blue = Color(0xFF2E75B6), blueMid = Color(0xFF4F81BD), bluePale = Color(0xFFD0E4F7),
        gold = Color(0xFFB8860B), goldLt = Color(0xFFD4A017),
        navy = Color(0xFF1E3A5F),
        textOnAccent = Color.White, dark = false,
    )
    MpdThemeId.SEPIA -> MpdPalette(
        bg = Color(0xFFF2EAD9), surface = Color(0xFFFDF8EA), surfaceAlt = Color(0xFFEBE0C8),
        text = Color(0xFF3D2B1F), muted = Color(0xFF8B6F5E), border = Color(0xFFC9B89A),
        blue = Color(0xFF7A5C3E), blueMid = Color(0xFF9A7A5E), bluePale = Color(0xFFE8DDD0),
        gold = Color(0xFFB8860B), goldLt = Color(0xFFD4A017),
        navy = Color(0xFF4A3728),
        textOnAccent = Color(0xFFFDF8EA), dark = false,
    )
    MpdThemeId.NORDIC -> MpdPalette(
        bg = Color(0xFF1E2530), surface = Color(0xFF263040), surfaceAlt = Color(0xFF2E3A4A),
        text = Color(0xFFD0DAE8), muted = Color(0xFF7A8FA8), border = Color(0xFF3A4A5A),
        blue = Color(0xFF5A9FD4), blueMid = Color(0xFF7AB4E0), bluePale = Color(0x265A9FD4),
        gold = Color(0xFFD4A84B), goldLt = Color(0xFFE8C060),
        navy = Color(0xFF162030),
        textOnAccent = Color(0xFF1E2530), dark = true,
    )
    MpdThemeId.SUNSET -> MpdPalette(
        bg = Color(0xFF1A0A00), surface = Color(0xFF2A1400), surfaceAlt = Color(0xFF3A1E00),
        text = Color(0xFFF5E6D0), muted = Color(0xFFA07850), border = Color(0xFF5A3010),
        blue = Color(0xFFE06020), blueMid = Color(0xFFE88040), bluePale = Color(0x26E06020),
        gold = Color(0xFFF0A030), goldLt = Color(0xFFF8C060),
        navy = Color(0xFF1A0A00),
        textOnAccent = Color.White, dark = true,
    )
    MpdThemeId.STACK -> MpdPalette(
        bg = Color(0xFF2A1E10), surface = Color(0xFFFDF8EA), surfaceAlt = Color(0xFF3A2818),
        text = Color(0xFFE8D8B8), muted = Color(0xFFB8A07A), border = Color(0xFF3A2818),
        blue = Color(0xFFC8922A), blueMid = Color(0xFFE0A83A), bluePale = Color(0x33C8922A),
        gold = Color(0xFFF0C878), goldLt = Color(0xFFFDF8EA),
        navy = Color(0xFF2A1E10),
        textOnAccent = Color(0xFF2A1E10), dark = true,
    )
    MpdThemeId.HONEYCOMB -> MpdPalette(
        bg = Color(0xFF2A1D0E), surface = Color(0xFF3A2818), surfaceAlt = Color(0xFF4A3820),
        text = Color(0xFFF5E6D0), muted = Color(0xFFC8A070), border = Color(0xFF5A4028),
        blue = Color(0xFFF0A030), blueMid = Color(0xFFF8C060), bluePale = Color(0x33F0A030),
        gold = Color(0xFFF0C878), goldLt = Color(0xFFFFF0C8),
        navy = Color(0xFF1A120A),
        textOnAccent = Color(0xFF2A1D0E), dark = true,
    )
    // filmstrip definiert im Web keine eigene Palette → dunkler Kino-Look
    MpdThemeId.FILMSTRIP -> palette(MpdThemeId.DARK)
    MpdThemeId.KACHEL -> MpdPalette(
        bg = Color(0xFF0F0F0F), surface = Color(0xFF1A1A1A), surfaceAlt = Color(0xFF232323),
        text = Color(0xFFEDEDED), muted = Color(0xFF8C8C8C), border = Color.Transparent,
        blue = Color(0xFF2E75B6), blueMid = Color(0xFF4F81BD), bluePale = Color(0x262E75B6),
        gold = Color(0xFFC8922A), goldLt = Color(0xFFE0A83A),
        navy = Color(0xFF0F0F0F),
        textOnAccent = Color.White, dark = true,
    )
}

private fun style(id: MpdThemeId, f: MpdFonts): MpdStyle = when (id) {
    MpdThemeId.DARK -> MpdStyle(1.00f, 3.dp, 3.dp, 14.sp, 11.5.sp, 0.sp, FontWeight.SemiBold, f.sans)
    MpdThemeId.LIGHT -> MpdStyle(0.75f, 16.dp, 12.dp, 15.sp, 12.5.sp, 0.sp, FontWeight.Medium, f.sans)
    MpdThemeId.SEPIA -> MpdStyle(0.66f, 2.dp, 24.dp, 15.sp, 12.sp, 0.3.sp, FontWeight.Normal, f.serif)
    MpdThemeId.NORDIC -> MpdStyle(1.00f, 0.dp, 2.dp, 12.5.sp, 11.sp, 1.9.sp, FontWeight.Bold, f.sans)
    MpdThemeId.SUNSET -> MpdStyle(0.56f, 20.dp, 12.dp, 15.5.sp, 12.sp, 0.sp, FontWeight.Medium, f.sans)
    MpdThemeId.STACK -> MpdStyle(1.00f, 2.dp, 0.dp, 15.sp, 12.5.sp, 0.sp, FontWeight.Normal, f.serif, showSpaceBadge = false)
    MpdThemeId.HONEYCOMB -> MpdStyle(1.15f, 10.dp, 8.dp, 14.sp, 12.sp, 0.sp, FontWeight.Medium, f.sans, showSpaceBadge = false)
    // Filmstreifen traegt laut CI die Mono — er ahmt Filmmaterial nach,
    // und dort stehen Randbeschriftungen in Schreibmaschinenschrift.
    MpdThemeId.FILMSTRIP -> MpdStyle(1.00f, 3.dp, 3.dp, 14.sp, 11.5.sp, 0.sp, FontWeight.SemiBold, f.mono)
    MpdThemeId.KACHEL -> MpdStyle(
        1.00f, 14.dp, 10.dp, 14.sp, 12.sp, 0.sp, FontWeight.SemiBold, f.sans,
        coverFill = true, minimalCard = true,
    )
}

data class MpdThemeSpec(val id: MpdThemeId, val colors: MpdPalette, val style: MpdStyle, val fonts: MpdFonts = FALLBACK_FONTS)

val LocalMpdTheme = staticCompositionLocalOf {
    MpdThemeSpec(MpdThemeId.DEFAULT, palette(MpdThemeId.DEFAULT), style(MpdThemeId.DEFAULT, FALLBACK_FONTS))
}

/** Kurzzugriff: `Mpd.colors.surface`, `Mpd.style.cardRadius`, … */
object Mpd {
    val colors: MpdPalette
        @Composable get() = LocalMpdTheme.current.colors
    val style: MpdStyle
        @Composable get() = LocalMpdTheme.current.style
    val themeId: MpdThemeId
        @Composable get() = LocalMpdTheme.current.id
    val fonts: MpdFonts
        @Composable get() = LocalMpdTheme.current.fonts
}

@Composable
fun MpdTheme(id: MpdThemeId, content: @Composable () -> Unit) {
    val p = palette(id)
    val fonts = rememberMpdFonts()
    // Auch die surfaceContainer-Familie mappen — sonst faerben sich
    // Material-Bausteine (NavigationBar, Menues, Dialoge) im Default-Lila.
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.blue, secondary = p.blueMid, background = p.bg,
        surface = p.surface, surfaceVariant = p.surfaceAlt,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceAlt,
        surfaceContainerHighest = p.surfaceAlt,
        secondaryContainer = p.bluePale, onSecondaryContainer = p.text,
        onPrimary = p.textOnAccent, onBackground = p.text, onSurface = p.text,
        onSurfaceVariant = p.muted,
        outline = p.border,
    )
    CompositionLocalProvider(LocalMpdTheme provides MpdThemeSpec(id, p, style(id, fonts), fonts)) {
        // Die Hausschrift muss in die Material-Typografie, nicht nur in
        // MpdStyle: der weitaus groesste Teil der Oberflaeche nimmt die
        // Default-Textstile, und die trugen bis heute Roboto.
        MaterialTheme(colorScheme = scheme, typography = typografie(fonts.sans), content = content)
    }
}

/**
 * Material-Typografie in der Hausschrift. Nur die Familie wird gesetzt —
 * Groessen und Zeilenabstaende bleiben die von Material 3, weil die App
 * ihre eigenen Groessen ohnehin an Ort und Stelle angibt.
 */
private fun typografie(sans: FontFamily): androidx.compose.material3.Typography {
    val basis = androidx.compose.material3.Typography()
    return basis.copy(
        displayLarge = basis.displayLarge.copy(fontFamily = sans),
        displayMedium = basis.displayMedium.copy(fontFamily = sans),
        displaySmall = basis.displaySmall.copy(fontFamily = sans),
        headlineLarge = basis.headlineLarge.copy(fontFamily = sans),
        headlineMedium = basis.headlineMedium.copy(fontFamily = sans),
        headlineSmall = basis.headlineSmall.copy(fontFamily = sans),
        titleLarge = basis.titleLarge.copy(fontFamily = sans),
        titleMedium = basis.titleMedium.copy(fontFamily = sans),
        titleSmall = basis.titleSmall.copy(fontFamily = sans),
        bodyLarge = basis.bodyLarge.copy(fontFamily = sans),
        bodyMedium = basis.bodyMedium.copy(fontFamily = sans),
        bodySmall = basis.bodySmall.copy(fontFamily = sans),
        labelLarge = basis.labelLarge.copy(fontFamily = sans),
        labelMedium = basis.labelMedium.copy(fontFamily = sans),
        labelSmall = basis.labelSmall.copy(fontFamily = sans),
    )
}
