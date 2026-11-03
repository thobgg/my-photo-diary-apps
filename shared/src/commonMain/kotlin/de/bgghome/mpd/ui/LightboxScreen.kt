package de.bgghome.mpd.ui


import de.bgghome.mpd.api.Photo
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.OpenAlbum
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * Lightbox (Schritt 1): Wischen zwischen den Fotos eines Albums, stufenloses
 * Pinch-Zoomen ohne Verpixeln.
 *
 * Zwei Stufen pro Bild:
 *  1. `xl` (2048 px) vom Server — schnell, reicht fuer die Vollansicht.
 *  2. Ab ~1,3-fachem Zoom wird einmalig das Original in den App-Cache
 *     geladen und von SubsamplingScaleImageView kachelweise dekodiert
 *     (BitmapRegionDecoder) — scharf bis 1:1, ohne das ganze Bild in den
 *     Speicher zu holen. Kein Serverumbau noetig (STAND.md).
 */
@Composable
fun LightboxScreen(
    vm: AppViewModel,
    title: String,
    photos: List<Photo>,
    readOnly: Boolean,
    imageLoader: ImageLoader,
    startIndex: Int = 0,
    onClose: () -> Unit,
    /**
     * Aus einem Album geoeffnet? Der Screen ist derselbe, der Kontext
     * nicht: aus dem Album heraus DARF geloescht werden („nur eben in den
     * Alben", Thomas 06.09.2026), aus der Timeline nicht — dort ist eine
     * Album-Datei geschuetzt. Die Lightbox selbst kann das nicht wissen;
     * die beiden Aufrufer wissen es (MainActivity: Albumweg / Timelineweg).
     *
     * Der Server bekommt daraus spaeter `from_album` — es ist die Absicht
     * des Aufrufs, nicht die Eigenschaft des Screens.
     */
    fromAlbum: Boolean = false,
) {
    var showExif by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    // Google-Fotos-Verhalten: Tipp aufs Bild blendet die Kopfleiste um
    var chromeVisible by remember { mutableStateOf(true) }

    // Echtes Fullscreen: System-Leisten weg, solange die Lightbox offen ist
    // (Android; auf dem Desktop gibt es keine).
    SystemleistenAusblenden()

    // Favoritenstand des Space einmalig laden; Fehler als Snackbar melden
    LaunchedEffect(photos.firstOrNull()?.space) {
        photos.firstOrNull()?.space?.let { vm.favorites.ensureLoaded(it) }
    }
    LaunchedEffect(vm.favorites.error) {
        vm.favorites.error?.let {
            vm.meldung(it)
            vm.favorites.error = null
        }
    }

    var deleteAsk by remember { mutableStateOf<Photo?>(null) }
    var shareMenuFor by remember { mutableStateOf<Photo?>(null) }

    BackHandler {
        when {
            deleteAsk != null -> deleteAsk = null
            showEditor -> showEditor = false
            showExif -> showExif = false
            else -> onClose()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    ) { photos.size }
    val currentPhoto = photos.getOrNull(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    // Google-Fotos-Schliessgeste: das Bild folgt dem Finger nach unten,
    // schrumpft leicht, der Grund wird durchsichtig; Loslassen ueber der
    // Schwelle (oder mit Schwung) schliesst, sonst federt es zurueck.
    val dismissOffset = remember {
        androidx.compose.animation.core.Animatable(0f)
    }
    val screenHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toFloat()
    val dismissProgress = (dismissOffset.value / screenHeightPx).coerceIn(0f, 1f)

    // Tastatur (Web-Idiom): EIN Fokus auf der Lightbox, nicht je Bildseite -
    // ein Fokus auf einer Seite ausserhalb des Sichtfelds scrollt den Pager
    // dorthin, Seite fuer Seite (22.09.2026: „bei Klick auf Foto
    // laufen alle Fotos in Windeseile komplett einmal durch").
    val tastenFokus = remember { androidx.compose.ui.focus.FocusRequester() }
    val tastatur = if (!LocalSystemDienste.current.webIdiom) Modifier else Modifier
        .focusRequester(tastenFokus)
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.DirectionRight -> { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(photos.lastIndex)) }; true }
                Key.DirectionLeft -> { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }; true }
                Key.DirectionUp, Key.I -> { showExif = !showExif; true }
                // Pos1/Ende: erstes/letztes Foto (02.11.2026). Sonst liefen
                // die Tasten zum Fenster durch und scrollten die Liste
                // unter der Lightbox.
                Key.MoveHome -> { scope.launch { pagerState.scrollToPage(0) }; true }
                Key.MoveEnd -> { scope.launch { pagerState.scrollToPage(photos.lastIndex.coerceAtLeast(0)) }; true }
                Key.PageUp, Key.PageDown -> true
                else -> false
            }
        }
    if (LocalSystemDienste.current.webIdiom) {
        LaunchedEffect(Unit) { runCatching { tastenFokus.requestFocus() } }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.8f))
            .then(tastatur),
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { photos[it].originalUrl },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffset.value
                    val s = 1f - dismissProgress * 0.18f
                    scaleX = s; scaleY = s
                },
        ) { page ->
            PhotoPage(vm, photos[page], imageLoader, pagerState,
                onSwipeUp = { showExif = true },
                onTap = { chromeVisible = !chromeVisible },
                onDismissDrag = { dy ->
                    scope.launch { dismissOffset.snapTo(dy) }
                },
                onDismissDragEnd = { total, velocity ->
                    if (total > screenHeightPx * 0.2f || velocity > 2200f) {
                        onClose()
                    } else {
                        scope.launch {
                            dismissOffset.animateTo(
                                0f,
                                androidx.compose.animation.core.spring(
                                    dampingRatio = 0.7f, stiffness = 400f,
                                ),
                            )
                        }
                    }
                })
        }

        // Schlanke Kopf-Zone wie Google Fotos: nur zwei runde, klar
        // tappbare Knoepfe auf Scrim — kein Balken, kein Zaehler, kein
        // Info-Knopf (Hochwischen = Info, Runterwischen = schliessen).
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightboxRoundButton(MpdIcons.ArrowBack, stringResource(Res.string.lb_back_to, title), onClick = onClose)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                // Web-Idiom (Desktop): Metadaten per Knopf - der Wisch nach
                // oben ist eine Fingergeste, mit der Maus gibt es sie nicht
                // (22.09.2026: „exif fehlt auch noch"). Taste i ebenso.
                if (LocalSystemDienste.current.webIdiom) {
                    LightboxRoundButton(MpdIcons.Info, "EXIF") { showExif = !showExif }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                }
                // Geraete-Aufnahmen kennen weder Space noch Album auf der
                // NAS: Favorit, Teilen und Bearbeiten wuerden mit leerem
                // Space an den Server gehen — im harmlosen Fall ein 404, im
                // unangenehmen trifft der Albumname des Geraeteordners
                // zufaellig einen echten Ordner. Was nicht geht, wird auch
                // nicht angeboten; die Datei muss erst gesichert sein.
                val serverPhoto = currentPhoto?.takeIf { !it.isLocal }
                serverPhoto?.let { p ->
                    val fav = vm.favorites.isFavorite(p)
                    LightboxRoundButton(
                        MpdIcons.Star,
                        stringResource(if (fav) Res.string.lb_fav_remove else Res.string.lb_fav_add),
                        tint = if (fav) MpdGoldLt else Color.White,
                    ) { vm.favorites.toggle(p) }
                }
                // Einzelfoto teilen (Teilen v3). Photo.album ist bereits
                // der aufgeloeste Quellordner — genau das, was der
                // Share-Vertrag fuer referenzierte Fotos verlangt.
                // Zwei Arten zu teilen, die man nicht verwechseln darf: das
                // BILD geht an eine andere App (Chat, Mail) — der LINK
                // verlangt vom Empfaenger Zugang zu MPD. Frueher gab es nur
                // den Link; eine Galerie muss das Bild selbst weitergeben
                // koennen. Geraete-Aufnahmen kennt der Server nicht, dort
                // gibt es folgerichtig nur das Bild.
                currentPhoto?.let { p ->
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    LightboxRoundButton(MpdIcons.Link, stringResource(Res.string.lb_share)) {
                        shareMenuFor = p
                    }
                }
                // Bearbeiten ist Kurator-Sache. Die Timeline-Lightbox setzte
                // readOnly=false und liess den Server ablehnen — dann stand
                // der Stift da und fuehrte in einen 403.
                // Bearbeiten geht jetzt auch fuer Geraete-Aufnahmen —
                // dieselben Regler, gerechnet auf dem Geraet (Vorgabe:
                // „aber Google Fotos kann es doch auch"). Bei Server-
                // Dateien bleibt es Kurator-Sache.
                // Drei Bedingungen, drei verschiedene Gruende:
                //  - Geraete-Aufnahme? Dann rechnet die App selbst.
                //  - Darf dieses Konto? Kommt aus rights (canCurate).
                //  - Kann der Server dieses FORMAT zurueckschreiben?
                //    Seit 07.09.2026 zeigt MPD Formate an, die es nicht
                //    ueberschreiben darf — ein mehrseitiges .tif kaeme
                //    einseitig zurueck. Der Server sagt es je Datei
                //    (editable_format); die Endungen stehen bewusst
                //    NIRGENDS in der App, sonst laufen zwei Listen
                //    auseinander (das ist am 06.09. mit den Rechteregeln
                //    genau so passiert).
                val mayEdit = currentPhoto?.let {
                    // Lokal bearbeiten kann nur Android (MediaStore-Schreiben);
                    // am Desktop gibt es fuer PC-Dateien keinen Editor.
                    (it.isLocal && vm.plattform.hatGeraetefotos) ||
                        (serverPhoto != null && vm.canCurate(it.space) && it.editableFormat)
                } == true
                if (!readOnly && mayEdit) {
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.width(10.dp))
                    LightboxRoundButton(MpdIcons.Pencil, stringResource(Res.string.lb_edit)) {
                        showEditor = true
                    }
                }
                // Wegwerfen gehoert hierher: im Vollbild entscheidet man,
                // dass ein Foto Ausschuss ist — vorher musste man zurueck
                // in die Timeline, es dort suchen und markieren. Es gelten
                // dieselben Regeln wie im Raster: was zu einem Album
                // gehoert, wird im Album geloescht, nicht hier.
                // Desktop: Original in den Downloads-Ordner (04.10.2026) —
                // wie der Download-Knopf der Web-Lightbox, auch in Alben
                // ohne Schreibrecht. PC-Dateien liegen schon hier.
                val dl = LocalSystemDienste.current
                if (dl.kannHerunterladen) {
                    currentPhoto?.takeIf { !it.isLocal }?.let { p ->
                        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                        LightboxRoundButton(MpdIcons.Download, stringResource(Res.string.lb_download)) {
                            scope.launch {
                                try {
                                    val pfad = dl.ladeInDownloads(vm, p.originalUrl, p.file)
                                    if (pfad != null) vm.meldung(Texte.t(Res.string.lb_downloaded, pfad), kurz = true)
                                } catch (e: de.bgghome.mpd.api.MpdHttpException) {
                                    vm.meldung(vm.explain(e))
                                } catch (e: java.io.IOException) {
                                    vm.meldung(e.message ?: Texte.t(Res.string.err_generic))
                                }
                            }
                        }
                    }
                }
                if (!readOnly) {
                    currentPhoto?.takeIf { vm.isDeletable(it, fromAlbum) }?.let { p ->
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.width(10.dp))
                        LightboxRoundButton(MpdIcons.Trash, stringResource(Res.string.lb_trash)) {
                            if (p.isLocal && vm.plattform.papierkorbFragtSelbst) {
                                // Geraete-Aufnahme: Android fragt gleich
                                // selbst nach. Eine eigene Rueckfrage davor
                                // waere zweimal dasselbe (06.09.:
                                // „da muss aber nicht bei Gerätefotos
                                // kommen, sonst muss man ja 2x bestätigen").
                                vm.deleteSinglePhoto(p, fromAlbum) { onClose() }
                            } else {
                                if (!p.isLocal) vm.loadRecycleStatusForSpaces(setOf(p.space))
                                deleteAsk = p
                            }
                        }
                    }
                }
            }
        }

        // Metadaten: breite Fenster (Tablet quer) bekommen die rechte
        // Sidebar des Webs (lb-sidebar), schmale die Vollflaeche von unten.
        val wide = fensterBreiteDp() >= 840
        if (wide) {
            // Tipp neben die offene Sidebar schliesst sie (Scrim-Verhalten).
            //
            // WISCHEN MUSS TROTZDEM DURCH (12.09.2026: „wenn in
            // Lightbox Exif-Seitenleiste offen, soll man mit links/rechts
            // Wischen trotzdem bei offener Leiste Bild wechseln koennen").
            // Bis dahin lag hier ein bildschirmfuellendes `clickable`. In
            // Compose gewinnt beim Hit-Test das oberste Geschwisterkind —
            // die Bildansicht darunter bekam gar keinen MotionEvent mehr,
            // und mit ihr der Pager. Die Leiste offen zu haben hiess: kein
            // Blaettern, obwohl links davon das ganze Foto sichtbar ist.
            //
            // Jetzt zwei getrennte Gesten auf derselben Flaeche: ein Tipp
            // schliesst (detectTapGestures feuert nur ohne Bewegung), ein
            // waagerechter Zug schiebt den Pager mit und rastet beim
            // Loslassen ein — dieselbe Mechanik wie der Zug auf dem Foto.
            if (showExif) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x22000000))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                showExif = false
                            }
                        }
                        .pointerInput(pagerState) {
                            var startPage = 0
                            var gezogen = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    startPage = pagerState.currentPage
                                    gezogen = 0f
                                },
                                onDragEnd = {
                                    // Ueber ein Viertel der Breite gezogen
                                    // heisst „weiter", darunter zurueck auf
                                    // die Ausgangsseite.
                                    val schwelle = size.width / 4f
                                    val ziel = when {
                                        gezogen < -schwelle -> startPage + 1
                                        gezogen > schwelle -> startPage - 1
                                        else -> startPage
                                    }.coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                                    scope.launch { pagerState.animateScrollToPage(ziel) }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage)
                                    }
                                },
                            ) { change, dx ->
                                change.consume()
                                gezogen += dx
                                pagerState.dispatchRawDelta(-dx)
                            }
                        },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showExif && currentPhoto != null,
                enter = androidx.compose.animation.slideInHorizontally { it },
                exit = androidx.compose.animation.slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                currentPhoto?.let {
                    ExifPanel(
                        vm, it, readOnly, onDismiss = { showExif = false },
                        modifier = Modifier
                            .width(420.dp)
                            .fillMaxHeight(),
                        sideways = true,
                    )
                }
            }
        } else {
            androidx.compose.animation.AnimatedVisibility(
                visible = showExif && currentPhoto != null,
                enter = androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.slideOutVertically { it },
                // Ohne Ausrichtung landet das Blatt oben links in der Box —
                // es faehrt von unten herein und klebte dann mit dem Griff
                // am oberen Rand, mitten im Kameraloch.
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                currentPhoto?.let {
                    ExifPanel(
                        vm, it, readOnly, onDismiss = { showExif = false },
                        // Das Blatt geht fast bis oben — es weicht nur dem
                        // Kameraloch aus, und zwar auf dessen echtes Mass
                        // (S25 Ultra: 128 px = 34 dp). displayCutout misst
                        // auch dann noch, wenn die Systemleisten versteckt
                        // sind und statusBars 0 meldet. Ein Prozentwert der
                        // Bildschirmhoehe waere hier um das Vierfache daneben.
                        modifier = Modifier
                            .fillMaxSize()
                            .displayCutoutPadding()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
        // Filmstreifen am unteren Rand — Google Fotos auf dem Tablet;
        // huepft mit dem Pager mit, Tipp springt zur Seite.
        // Dieselbe Frage wie oben beim Metadaten-Blatt — einmal gemessen.
        // Web-Idiom (Desktop): kein Filmstreifen (22.09.2026: „genau da
        // stoert unten eben die Bilderleiste"), stattdessen wie die Web-Lightbox
        // Pfeile links/rechts und der Zaehler „n / N" unten.
        val webIdiom = LocalSystemDienste.current.webIdiom
        val wideStrip = wide && !webIdiom
        if (webIdiom && photos.size > 1) {
            androidx.compose.animation.AnimatedVisibility(
                visible = chromeVisible && dismissProgress == 0f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                LightboxRoundButton(MpdIcons.ArrowBack, "‹", modifier = Modifier.padding(start = 14.dp)) {
                    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = chromeVisible && dismissProgress == 0f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                LightboxRoundButton(MpdIcons.ArrowBack, "›", modifier = Modifier.padding(end = 14.dp).graphicsLayer { rotationZ = 180f }) {
                    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(photos.lastIndex)) }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = chromeVisible && dismissProgress == 0f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (wideStrip && photos.size > 1) {
            androidx.compose.animation.AnimatedVisibility(
                visible = chromeVisible && dismissProgress == 0f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Filmstrip(vm, photos, pagerState, imageLoader)
            }
        }

        if (showEditor && currentPhoto != null) {
            PhotoEditorOverlay(vm, currentPhoto, imageLoader, onClose = { showEditor = false })
        }

        shareMenuFor?.let { p ->
            val dienste = LocalSystemDienste.current
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { shareMenuFor = null },
                title = { Text(stringResource(Res.string.lb_share)) },
                text = {
                    Column {
                        androidx.compose.material3.TextButton(onClick = {
                            // Reihenfolge egal, Scope nicht: das Laden laeuft
                            // im ViewModel weiter, auch nachdem dieser Dialog
                            // verschwunden ist. Vorher starb es genau hier.
                            shareMenuFor = null
                            dienste.teileFoto(vm, p)
                        }) { Text(stringResource(Res.string.lb_share_send)) }
                        Text(
                            stringResource(Res.string.lb_share_send_hint),
                            color = MpdMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                        )
                        // Teilen folgt weder curate noch contribute: ein
                        // Mitwirkender darf im Familienbestand teilen, ohne
                        // dort zu kuratieren. Und es ist je Konto
                        // abschaltbar — beides steckt in `share`.
                        if (!p.isLocal && vm.canShare(p.space)) {
                            androidx.compose.material3.TextButton(onClick = {
                                shareMenuFor = null
                                vm.photoShare = Triple(p.space, p.album, p.file)
                            }) { Text(stringResource(Res.string.lb_share_link)) }
                            Text(
                                stringResource(Res.string.lb_share_link_hint),
                                color = MpdMuted, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { shareMenuFor = null },
                    ) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }

        deleteAsk?.let { p ->
            // Ein „Rueckgaengig" im Server wird es nicht geben (Vorgabe,
            // 07.09.2026): Wiederhergestellt wird ausserhalb von MPD, in
            // File Station. Die Rueckfrage bleibt damit die einzige
            // Sicherung — und sie sagt jetzt beides, wohin die Datei geht
            // und wie man sie zurueckholt. Der Zusatz „unsortiert" gilt nur
            // im Album: dort faellt beim Loeschen der Eintrag in der
            // album.json weg, die zurueckgeholte Datei liegt also im Ordner,
            // aber ohne ihren Platz. Ausserhalb eines Albums gibt es keinen
            // Platz, den sie verlieren koennte.
            val fate = stringResource(
                when {
                    p.isLocal && vm.plattform.papierkorbFragtSelbst -> Res.string.lb_fate_local
                    p.isLocal -> Res.string.lb_fate_pc
                    vm.recycleBySpace[p.space] == true && fromAlbum -> Res.string.delete_hint_recycle
                    vm.recycleBySpace[p.space] == true -> Res.string.lb_fate_recycle
                    vm.recycleBySpace[p.space] == false -> Res.string.lb_fate_norecycle
                    else -> Res.string.lb_fate_default
                },
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteAsk = null },
                title = { Text(stringResource(Res.string.lb_trash_title, p.file)) },
                text = { Text(fate) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        deleteAsk = null
                        vm.deleteSinglePhoto(p, fromAlbum) { onClose() }
                    }) { Text(stringResource(Res.string.lb_trash), color = MpdRed) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { deleteAsk = null }) {
                        Text(stringResource(Res.string.common_cancel))
                    }
                },
            )
        }
    }
}

/** Filmstreifen (Tablet): kleine Thumbs, aktuelles Bild mit Goldrahmen. */
@Composable
private fun Filmstrip(
    vm: AppViewModel,
    photos: List<Photo>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    imageLoader: ImageLoader,
) {
    val stripState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Aktuelles Bild im Streifen mittig halten
    LaunchedEffect(pagerState.currentPage) {
        stripState.animateScrollToItem(
            pagerState.currentPage.coerceAtLeast(0),
            scrollOffset = -300,
        )
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = stripState,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(vertical = 8.dp)
            .navigationBarsPadding(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.originalUrl }) { index, photo ->
            val current = index == pagerState.currentPage
            coil3.compose.AsyncImage(
                model = photo.mUrl,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(width = 52.dp, height = 52.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .then(
                        if (current) Modifier.border(
                            2.dp, MpdGoldLt,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        ) else Modifier
                    )
                    .androidClickableNoRipple {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
            )
        }
    }
}

/** Runder Knopf auf halbtransparentem Scrim — erkennbare Tapflaeche. */
@Composable
private fun LightboxRoundButton(
    iconVector: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .background(
                Color.Black.copy(alpha = 0.45f),
                androidx.compose.foundation.shape.CircleShape,
            )
            .androidClickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconVector, description, Modifier.size(21.dp), tint = tint)
    }
}

private fun Modifier.androidClickableNoRipple(onClick: () -> Unit): Modifier =
    composed {
        clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        )
    }

