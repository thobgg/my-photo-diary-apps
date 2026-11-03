package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd

/*
 * Modul „Erinnerungen" — Pinnwand-Stil wie memories.html (Wunsch
 * 03.09.2026: „schau mal was das Web-Memories an Eyecandy bot"): Papier-
 * Hintergrund, Handschrift (Caveat, OFL, res/font), Polaroid-Streifen mit
 * leicht verdrehten Karten, gestaffelte Einblendungen, ✦-Fusszeile.
 * Hell = Creme wie die Web-Voreinstellung; ist das App-Theme dunkel,
 * greifen die [data-theme=dark]-Werte der Webseite.
 */

// Handschrift laut CI v1.2: Playwrite DE VA, und nur hier. Bis zum
// 08.09.2026 stand hier Caveat — eine Wahl aus der Zeit vor der
// Schriftfestlegung.
private val Handwriting: androidx.compose.ui.text.font.FontFamily
    @androidx.compose.runtime.Composable get() = de.bgghome.mpd.ui.theme.Mpd.fonts.handwriting

private data class PinboardColors(
    val cream: Color, val paper: Color, val ink: Color,
    val inkLight: Color, val gold: Color, val goldLight: Color,
)

// Farbwelten 1:1 aus memories.html (:root + data-theme-Overrides). Im Web
// war der Umschalter halb kaputt (JS-Namen trafen die CSS-Namen nie) —
// hier zaehlt er wirklich. "auto" folgt dem App-Theme (Creme/Dunkel).
private val PinboardLight = PinboardColors(
    cream = Color(0xFFF5F0E8), paper = Color(0xFFFFFDF8),
    ink = Color(0xFF2C2416), inkLight = Color(0xFF6B5C47),
    gold = Color(0xFFC9A84C), goldLight = Color(0xFFE8D5A3),
)

private val PinboardDark = PinboardColors(
    cream = Color(0xFF1A1A1A), paper = Color(0xFF252525),
    ink = Color(0xFFE8E0D0), inkLight = Color(0xFFA89880),
    gold = Color(0xFFC8922A), goldLight = Color(0xFF3A2E1A),
)

private val PinboardSepia = PinboardColors(
    cream = Color(0xFFF0E6D0), paper = Color(0xFFFAF3E6),
    ink = Color(0xFF3A2A18), inkLight = Color(0xFF7A5C38),
    gold = Color(0xFFB8822A), goldLight = Color(0xFFE8D5A3),
)

private val PinboardNordic = PinboardColors(
    cream = Color(0xFF2E3440), paper = Color(0xFF3B4252),
    ink = Color(0xFFECEFF4), inkLight = Color(0xFFD8DEE9),
    gold = Color(0xFF88C0D0), goldLight = Color(0xFF2E3440),
)

private val PinboardSunset = PinboardColors(
    cream = Color(0xFF1A1220), paper = Color(0xFF2A1E30),
    ink = Color(0xFFF0E0F0), inkLight = Color(0xFFC090B0),
    gold = Color(0xFFE08050), goldLight = Color(0xFF3A1A20),
)

/**
 * PINNWAND — die einzige Wahl, die mehr aendert als die Farbe.
 *
 * 13.09.2026: „in Webview hatte My Memories grundsaetzlich
 * verschiedenes Aussehen??? Liesse sich das nicht uebertragen?" Ja — die
 * stillgelegte `frontend/memories.html` hatte neben den vier Farbtoenen ein
 * zweites, echtes Theme: Korkwand, Jahreszahl als Notizzettel mit
 * Klebeband, Polaroids mit Reissnagel, staerker verdreht und ueberlappend.
 * Es galt dort erst ab 768 px Fensterbreite und war als Machbarkeitsstudie
 * angelegt; am Handy blieb der schlichte Look.
 *
 * Hier wird es fertig gebaut, aber gestaffelt: die Ueberlappung braucht
 * Flaeche und bleibt dem Tablet vorbehalten. Am Handy (384 dp) liegen die
 * Karten weiter nebeneinander — Kork, Zettel und Nagel gibt es trotzdem.
 * Anders waeren die Bilder voneinander verdeckt statt gestapelt.
 */
private val PinboardKork = PinboardColors(
    cream = Color(0xFFA87648), paper = Color(0xFFFFFDF8),
    ink = Color(0xFF2C2416), inkLight = Color(0xFFF5D68A),
    gold = Color(0xFFF5D68A), goldLight = Color(0xFF8A5E38),
)


@Composable
private fun pinboardColors(theme: String): PinboardColors = when (theme) {
    "creme" -> PinboardLight
    "dunkel" -> PinboardDark
    "sepia" -> PinboardSepia
    "nordic" -> PinboardNordic
    "sunset" -> PinboardSunset
    "pinnwand" -> PinboardKork
    else -> { // auto: Luminanz des App-Themes entscheidet
        val bg = Mpd.colors.bg
        if ((0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f) {
            PinboardDark
        } else PinboardLight
    }
}

@Composable
fun MemoriesScreen(vm: AppViewModel, imageLoader: ImageLoader) {
    val pin = pinboardColors(vm.memories.theme)
    // Die Pinnwand ist die einzige Wahl, die den Aufbau aendert, nicht nur
    // die Farbe — siehe PinboardKork.
    val pinnwand = vm.memories.theme == "pinnwand" 
    LaunchedEffect(Unit) {
        vm.memories.ladeWennVeraltet()
        if (vm.userSettings == null) vm.loadUserSettings()
    }


    // Seit 05.09. eine Vollbild-Ansicht statt eines Tabs — sie traegt
    // ihren Kopf und die Systemleisten-Abstaende daher selbst. Abweichend
    // von den uebrigen Ansichten OHNE navigationBarsPadding (die Liste
    // hat ihre eigene Luft nach unten) und in den Pinnwand-Farben.
    MpdScreen(
        backDescription = stringResource(Res.string.memories_close),
        onClose = { vm.memories.open = false },
        background = pin.cream,
        backgroundBrush = if (pinnwand) korkBrush() else null,
        backTint = if (pinnwand) Color(0xFFFFF8E7) else pin.ink,
        headerPadding = PaddingValues(end = 12.dp),
        navigationBarsPadding = false,
        header = {
            Spacer(Modifier.weight(1f))
            // Quelle: Familie / Meine / Beides (26.09.2026: „man bei Memories
            // auswaehlen koennen ob beruecksichtigt werden geteilt/
            // persoenlich/beides"). Schreibt die Kontoeinstellung — dieselbe
            // wie in den Einstellungen und fuer den Morgengruss.
            MemoriesScopePill(vm)
            // Die Farbwelt sass in der App-Kopfzeile, solange Memories ein
            // Tab war — jetzt gehoert sie hierher.
            MemoriesThemePill(vm, compact = true)
        },
    ) {
        // Die Farbwelt-Wahl wohnt seit 03.09. in den Einstellungen
        // („Memories-Farbwelt") — die zweite Kopfzeile war am Handy
        // hochkant zu viel. Tagesblaettern bleibt am Datum.
        val data = vm.memories.data
        // AM HANDY EIN VIERTEL KLEINER (13.09.2026): „Heute vor…"
        // und „Vor 6 Jahren" nahmen zusammen die halbe Bildschirmhoehe,
        // darunter blieb Platz fuer ein einziges Polaroid. Die Schriftwahl
        // stimmt — Playwrite ist laut CI genau fuer diesen Moment da —,
        // nur der Grad war fuer 1440 px zu gross. Am Tablet ist der Platz
        // vorhanden, dort bleibt es beim grossen Auftritt. Geprueft wird
        // die kuerzere Bildschirmkante, dieselbe Regel wie im Kopf.
        val kompakt = !istTablet()
        when {
            vm.memories.error != null -> Center("${vm.memories.error}", MpdRed)

            // NOCH NICHTS DA = Ladekreis, nicht Leere. Bis zum 24.09.2026
            // stand hier ein zweiter Zweig `data == null -> Unit`: eine
            // Pinnwand ohne Inhalt, ohne Hinweis, ohne Kreis — in den
            // dunklen Farbwelten (und „auto" bei dunklem App-Theme) eine
            // schwarze Flaeche. Sichtbar wurde das ueber den Morgengruss,
            // dessen Deep-Link am Ziel vorbeilief; der Ladezustand darf
            // aber auch danach nie wieder wie ein Absturz aussehen.
            data == null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp), color = pin.gold) }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                // Luft nach unten: die Fusszeile stand am Handy unter der
                // Leiste (2x moniert).
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = 72.dp,
                ),
            ) {
                item(key = "header") {
                    Column(
                        Modifier.fillMaxWidth().padding(
                            top = if (kompakt) 4.dp else 10.dp,
                            bottom = if (kompakt) 10.dp else 18.dp,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Tagesblaettern direkt am Datum: ‹ 2. September ›
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DayArrow("‹", pin) { vm.memories.load(vm.memories.offsetDays - 1) }
                            Text(
                                data.label,
                                color = pin.inkLight,
                                fontFamily = Handwriting,
                                fontSize = if (kompakt) 18.sp else 22.sp,
                                letterSpacing = 1.2.sp,
                                textAlign = TextAlign.Center,
                                // MINDESTBREITE STATT FESTBREITE. Die 170 dp
                                // sollten verhindern, dass die Pfeile beim
                                // Blaettern springen — sie zwangen aber
                                // „13. September" in zwei Zeilen, waehrend
                                // daneben Platz frei blieb. Jetzt waechst das
                                // Feld bei Bedarf, und maxLines haelt es in
                                // einer Zeile.
                                maxLines = 1,
                                modifier = Modifier.widthIn(min = 170.dp),
                            )
                            DayArrow("›", pin) { vm.memories.load(vm.memories.offsetDays + 1) }
                        }
                        if (vm.memories.offsetDays != 0) {
                            Text(
                                stringResource(Res.string.memories_back_today),
                                color = pin.gold,
                                fontFamily = Handwriting,
                                fontSize = if (kompakt) 16.sp else 19.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { vm.memories.load(0) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        Text(
                            if (vm.memories.offsetDays == 0) stringResource(Res.string.memories_title_today)
                            else stringResource(Res.string.memories_title_day),
                            color = pin.ink,
                            fontFamily = Handwriting,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (kompakt) 33.sp else 44.sp,
                            lineHeight = if (kompakt) 36.sp else 46.sp,
                        )
                        Box(
                            Modifier
                                .padding(top = if (kompakt) 8.dp else 12.dp)
                                .width(48.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(pin.gold),
                        )
                    }
                }

                if (data.years.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("📷", fontSize = 40.sp)
                            Text(
                                stringResource(Res.string.memories_empty),
                                color = pin.inkLight,
                                fontFamily = Handwriting,
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 30.sp,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                } else {
                    items(data.years.withIndex().toList(), key = { it.value.year }) { (index, block) ->
                        FadeUpBlock(delayMs = 100L + index * 150L) {
                            Column(Modifier.padding(bottom = 26.dp)) {
                                val jahrText = @Composable {
                                    Text(
                                        when (block.yearsAgo) {
                                            0 -> stringResource(Res.string.memories_this_year)
                                            else -> pluralStringResource(
                                                Res.plurals.memories_years_ago, block.yearsAgo, block.yearsAgo,
                                            )
                                        },
                                        color = if (pinnwand) Color(0xFF2C2416) else pin.ink,
                                        fontFamily = Handwriting,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = if (kompakt) 23.sp else 30.sp,
                                    )
                                }
                                if (pinnwand) {
                                    Notizzettel { jahrText() }
                                    Text(
                                        "${block.year}",
                                        color = pin.inkLight,
                                        fontSize = 13.sp,
                                        fontStyle = FontStyle.Italic,
                                        modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                                    )
                                } else {
                                    Column(Modifier.padding(horizontal = 22.dp)) {
                                        jahrText()
                                        Text(
                                            "${block.year}",
                                            color = pin.inkLight,
                                            fontSize = 13.sp,
                                            fontStyle = FontStyle.Italic,
                                        )
                                    }
                                }
                                LazyRow(
                                    contentPadding = androidx.compose.foundation.layout
                                        .PaddingValues(
                                            horizontal = 14.dp,
                                            vertical = if (pinnwand) 20.dp else 14.dp,
                                        ),
                                    // UEBERLAPPUNG NUR AM TABLET. Im Web galt
                                    // die Pinnwand erst ab 768 px, und das aus
                                    // gutem Grund: ineinandergeschobene
                                    // Polaroids brauchen Flaeche. Am Handy
                                    // (384 dp) wuerden sie einander verdecken
                                    // statt sich zu stapeln.
                                    horizontalArrangement =
                                        if (pinnwand && !kompakt) {
                                            androidx.compose.foundation.layout
                                                .Arrangement.spacedBy((-12).dp)
                                        } else {
                                            androidx.compose.foundation.layout
                                                .Arrangement.spacedBy(0.dp)
                                        },
                                ) {
                                    items(
                                        block.photos.withIndex().toList(),
                                        key = { it.value.album + it.value.filename },
                                    ) { (pi, photo) ->
                                        Polaroid(
                                            vm, imageLoader, pin,
                                            index = pi + 1, // 1-basiert wie nth-child
                                            url = vm.baseUrl + photo.thumbnailUrl,
                                            // WAS UNTER DEM BILD STEHT
                                            // (13.09.2026: „wie
                                            // entscheidet My Memories, ob
                                            // Ort oder Datum drunter
                                            // steht?"). Ort zuerst, dann
                                            // die Uhrzeit, erst zuletzt das
                                            // Datum.
                                            //
                                            // Bis hierher war der Rueckfall
                                            // gleich das Tagesdatum — und
                                            // das steht schon zweimal auf
                                            // dem Schirm: gross im Kopf und
                                            // als Jahr ueber den Karten.
                                            // Bei mehreren Fotos ohne Ort
                                            // sagte jede Karte dasselbe und
                                            // nichts Neues. Die Uhrzeit
                                            // liefert der Server seit dem
                                            // 08.09. mit, sie lag bloss
                                            // ungenutzt herum: sie erzaehlt
                                            // etwas ueber den Tag und
                                            // ordnet mehrere Aufnahmen von
                                            // selbst.
                                            caption = photo.location
                                                .ifEmpty { photo.time.orEmpty() }
                                                .ifEmpty { data.label },
                                            pinnwand = pinnwand,
                                        ) { vm.openMemory(photo) }
                                    }
                                }
                                // Der Server liefert seit dem 08.09.2026 eine
                                // Auswahl je Jahr (Tagesabschnitte, dann Ort —
                                // Rueckmeldung: „nicht 100 Bilder, sondern nur
                                // Auswahl"). Die volle Zahl steht in total;
                                // ehrlich dazuschreiben, und der Tipp fuehrt
                                // ins Album an diesem Tag, wo alle liegen.
                                val rest = block.total - block.photos.size
                                if (rest > 0) {
                                    val first = block.photos.first()
                                    Text(
                                        pluralStringResource(Res.plurals.memories_more, rest, rest),
                                        color = pin.inkLight,
                                        fontFamily = Handwriting,
                                        fontSize = 20.sp,
                                        modifier = Modifier
                                            .padding(start = 22.dp)
                                            .clickable {
                                                vm.openMemory(first, nurFoto = false)
                                            }
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    item(key = "footer") {
                        Text(
                            stringResource(Res.string.memories_footer),
                            color = pin.inkLight,
                            fontFamily = Handwriting,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Polaroid-Karte: Papierrahmen, Foto quadratisch, Handschrift-Zeile,
 *  leichte Drehung je Position (nth-child-Rotationen der Webseite). */
@Composable
private fun Polaroid(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    pin: PinboardColors,
    index: Int,
    url: String,
    caption: String,
    /** Pinnwand: staerker verdreht, mit Reissnagel. */
    pinnwand: Boolean = false,
    onClick: () -> Unit,
) {
    // Die Pinnwand haengt die Bilder schiefer — im Web waren es rund vier
    // Grad statt zwei. Die Werte wechseln je Position, damit keine zwei
    // Nachbarn gleich haengen.
    val rotation = when {
        index % 4 == 0 -> if (pinnwand) 3.9f else 2.2f
        index % 3 == 0 -> if (pinnwand) -1.4f else -0.6f
        index % 2 == 1 -> if (pinnwand) -3.8f else -1.8f
        else -> if (pinnwand) 2.6f else 1.4f
    }
    val topOffset = when {
        index % 4 == 0 -> 2.dp
        index % 3 == 0 -> 10.dp
        index % 2 == 1 -> 6.dp
        else -> 0.dp
    }
    Box(Modifier.offset(y = topOffset), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            .graphicsLayer { rotationZ = rotation }
            .padding(horizontal = 3.dp)
            .shadow(if (pinnwand) 10.dp else 6.dp, RoundedCornerShape(2.dp))
            .clip(RoundedCornerShape(2.dp))
            .background(pin.paper)
            .clickable(onClick = onClick)
            .width(175.dp)
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = caption,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(pin.goldLight),
        )
        // ZWEI ZEILEN, PLATZ FEST RESERVIERT (13.09.2026: „auch
        // wenn die Orte unter den Bildern etwas laenger als nur paar
        // Buchstaben hat — was dann?").
        //
        // Vorher stand hier maxLines = 1 ohne overflow, also die
        // Voreinstellung Clip: „Berlin-Charlottenburg" wurde mitten im
        // Buchstaben gekappt, ohne jeden Hinweis. Auf 175 dp Karte bleiben
        // rund 155 dp Text, in der Handschrift etwa zwoelf bis vierzehn
        // Zeichen — und der Rueckfall ohne Ortsangabe ist das DATUM
        // („13. September"), liegt also selbst schon an der Kante. Der Fall
        // war damit nicht der Ausnahme-, sondern der Normalfall.
        //
        // Die Hoehe ist auf zwei Zeilen festgelegt, auch wenn nur eine
        // gebraucht wird: sonst stuenden die Karten einer Reihe
        // unterschiedlich hoch. Bei einem echten Polaroid ist der untere
        // Rand ohnehin der breite, auf dem von Hand geschrieben wird — die
        // reservierte Zeile sieht also nicht nach Luecke aus, sondern nach
        // Papier.
        val zeile = 21.sp
        Text(
            caption,
            color = pin.ink,
            fontFamily = Handwriting,
            fontSize = 17.sp,
            lineHeight = zeile,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = with(LocalDensity.current) { (zeile * 2).toDp() }),
        )
    }
        // Der Nagel sitzt AUSSERHALB der gedrehten Karte, sonst kippte er
        // mit ihr — ein Reissnagel steht immer gerade in der Wand.
        if (pinnwand) {
            Box(Modifier.offset(y = (-6).dp)) { Reissnagel(index) }
        }
    }
}

/** Einblendung von unten, gestaffelt wie die fadeUp-Keyframes des Webs. */
@Composable
private fun FadeUpBlock(delayMs: Long, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val shift = remember { Animatable(28f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        launch { alpha.animateTo(1f, androidx.compose.animation.core.tween(500)) }
        shift.animateTo(0f, androidx.compose.animation.core.tween(500))
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = shift.value * density
        },
    ) { content() }
}

@Composable
private fun Center(text: String, color: Color) {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 14.sp) }
}

@Composable
private fun DayArrow(symbol: String, pin: PinboardColors, onClick: () -> Unit) {
    // 48 dp Mindest-Tippflaeche (Android-Richtwert) — die alten Textpfeile
    // waren am Handy kaum treffbar (03.09.).
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = pin.ink, fontSize = 26.sp) }
}

// -- Pinnwand: Kork, Notizzettel, Reissnagel ---------------------------------

/**
 * Korkwand als Farbverlauf plus feines Rauschen.
 *
 * Das Web erzeugte sie mit einem SVG-Turbulenzfilter — den gibt es in
 * Compose nicht. Stattdessen einmal eine kleine Kachel mit zufaelligen
 * Punkten zeichnen und wiederholen: derselbe Effekt, ein Bruchteil der
 * Kosten, und nichts muss als Bilddatei mitgeliefert werden. Der feste
 * Startwert sorgt dafuer, dass die Wand bei jedem Start gleich aussieht —
 * ein Kork, der sich beim Blaettern neu wuerfelt, faellt sofort auf.
 */
@Composable
internal fun korkBrush(): androidx.compose.ui.graphics.Brush {
    val kachel = 96
    val bitmap = remember {
        // Compose-Canvas statt android.graphics, damit die Wand auch auf dem
        // Desktop entsteht (22.09.2026); Zeichnung und Startwert unveraendert.
        val bmp = androidx.compose.ui.graphics.ImageBitmap(kachel, kachel)
        val zufall = java.util.Random(20260913L)
        val leinwand = androidx.compose.ui.graphics.Canvas(bmp)
        val farbe = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true }
        farbe.color = androidx.compose.ui.graphics.Color(0xFFA87648)
        leinwand.drawRect(0f, 0f, kachel.toFloat(), kachel.toFloat(), farbe)
        repeat(kachel * kachel / 5) {
            val hell = zufall.nextInt(60) - 30
            farbe.color = androidx.compose.ui.graphics.Color(
                red = (0xA8 + hell).coerceIn(0, 255), green = (0x76 + hell).coerceIn(0, 255),
                blue = (0x48 + hell).coerceIn(0, 255), alpha = 40 + zufall.nextInt(50),
            )
            leinwand.drawCircle(
                androidx.compose.ui.geometry.Offset(zufall.nextFloat() * kachel, zufall.nextFloat() * kachel),
                0.6f + zufall.nextFloat() * 1.4f,
                farbe,
            )
        }
        bmp
    }
    return remember(bitmap) {
        androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(
                bitmap,
                androidx.compose.ui.graphics.TileMode.Repeated,
                androidx.compose.ui.graphics.TileMode.Repeated,
            ),
        )
    }
}

/** Gelber Notizzettel mit Klebestreifen — traegt die Jahresueberschrift. */
@Composable
private fun Notizzettel(inhalt: @Composable () -> Unit) {
    Box(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .graphicsLayer { rotationZ = -1.6f }
                .shadow(6.dp)
                .background(Color(0xFFFEF6A8))
                .padding(horizontal = 22.dp, vertical = 9.dp),
        ) { inhalt() }
        // Der Streifen sitzt oben links ueber der Kante, wie im Web.
        Box(
            Modifier
                .offset(x = 30.dp, y = (-7).dp)
                .graphicsLayer { rotationZ = -3f }
                .size(width = 54.dp, height = 16.dp)
                .background(Color(0x8CF0E6B4)),
        )
    }
}

/** Reissnagel oben mittig auf einem Polaroid; jeder dritte golden. */
@Composable
private fun Reissnagel(index: Int) {
    val golden = index % 3 == 0
    val kopf = if (golden) Color(0xFFE8C24A) else Color(0xFFD14842)
    val licht = if (golden) Color(0xFFFFE9A0) else Color(0xFFFF7A6E)
    val schatten = if (golden) Color(0xFF6B4E10) else Color(0xFF7A1C18)
    Box(
        Modifier
            .size(16.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(licht, kopf, schatten),
                    center = androidx.compose.ui.geometry.Offset(5f, 4.5f),
                    radius = 22f,
                ),
            ),
    )
}
