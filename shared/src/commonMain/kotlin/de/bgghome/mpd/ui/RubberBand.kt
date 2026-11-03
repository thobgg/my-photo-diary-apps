package de.bgghome.mpd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Kraeftiger Gummiband-Overscroll (02.09.2026: der System-Stretch
 * gefaellt — „geht das etwas mehr?"). Androids Stretch-Effekt ist nicht
 * einstellbar, darum eigener Effekt: unverbrauchte Scroll-Deltas an den
 * Listenenden verschieben den Inhalt mit zunehmendem Widerstand, beim
 * Loslassen federt er mit leichtem Nachschwingen zurueck.
 *
 * Wiederverwendbar fuer jede Scroll-Flaeche:
 *     LazyColumn(modifier = Modifier.rubberBandOverscroll())
 */

private const val MAX_STRETCH_DP = 140f   // weiter dehnen als der System-Stretch
private const val RESISTANCE = 0.55f      // Grundwiderstand des Bandes

/**
 * System-Stretch abschalten, wo das eigene Gummiband uebernimmt. Auf dem
 * Desktop gibt es keinen System-Overscroll (und LocalOverscrollConfiguration
 * nicht), darum expect/actual (Aufteilung 22.09.2026).
 */
@Composable
expect fun NoSystemOverscroll(content: @Composable () -> Unit)

@Composable
fun Modifier.rubberBandOverscroll(): Modifier {
    val maxStretchPx = with(LocalDensity.current) { MAX_STRETCH_DP.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val connection = remember {
        object : NestedScrollConnection {

            private fun damped(current: Float, delta: Float): Float {
                // Je weiter gedehnt, desto zaeher — asymptotisch gegen MAX
                val resistance = RESISTANCE * (1f - abs(current) / maxStretchPx)
                return (current + delta * resistance.coerceAtLeast(0.05f))
                    .coerceIn(-maxStretchPx, maxStretchPx)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Gedehnt und Finger zieht zurueck → erst das Band entspannen
                val current = offset.value
                if (source == NestedScrollSource.UserInput && current != 0f &&
                    (current > 0f) != (available.y > 0f) && available.y != 0f
                ) {
                    val target = (current + available.y)
                        .let { if (current > 0f) it.coerceAtLeast(0f) else it.coerceAtMost(0f) }
                    val used = target - current
                    scope.launch { offset.snapTo(target) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource,
            ): Offset {
                // Liste konnte nicht weiter → Rest wird zur Dehnung
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    scope.launch { offset.snapTo(damped(offset.value, available.y)) }
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value != 0f) {
                    // Nachschwingen: dampingRatio < 1 laesst es kurz federn
                    offset.animateTo(
                        0f,
                        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                        initialVelocity = available.y,
                    )
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (offset.value != 0f) {
                    offset.animateTo(
                        0f,
                        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                        initialVelocity = available.y,
                    )
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer { translationY = offset.value }
}
