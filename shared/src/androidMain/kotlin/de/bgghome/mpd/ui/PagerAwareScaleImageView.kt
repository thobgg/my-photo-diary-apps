package de.bgghome.mpd.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.abs

/**
 * SubsamplingScaleImageView fuer den Einsatz im HorizontalPager.
 *
 * Die Bibliothek beansprucht jede Beruehrung fuer sich; bei einem komplett
 * eingepassten Bild gibt sie horizontale Zuege nie an den Pager weiter — man
 * kommt nie zum naechsten Foto (am Emulator reproduziert, Zaehler blieb auf
 * 1/24). Auch das Aufheben von requestDisallowInterceptTouchEvent reicht
 * unter Compose nicht: der Pager steigt in eine laufende, anfangs
 * konsumierte Geste nicht mehr ein.
 *
 * Darum uebernimmt diese Unterklasse das Blaettern selbst: Erkennt sie bei
 * nicht gezoomtem Bild einen ueberwiegend horizontalen Ein-Finger-Zug,
 * entzieht sie der Bibliothek die Geste (ACTION_CANCEL) und meldet die
 * Bewegung an einen Listener, der den Pager direkt mitzieht — die Seite
 * folgt dem Finger. Pinchen, Doppeltipp und Pannen im gezoomten Bild bleiben
 * unveraendert bei der Bibliothek.
 */
class PagerAwareScaleImageView(context: Context) : SubsamplingScaleImageView(context) {

    interface PagerDragListener {
        /** Fingerbewegung in Bildschirmpixeln seit dem letzten Aufruf. */
        fun onDrag(deltaX: Float)
        /** Finger gehoben; velocityX in px/s (positiv = nach rechts). */
        fun onDragEnd(velocityX: Float)
        fun onDragCancel()
    }

    var pagerDragListener: PagerDragListener? = null

    /**
     * true, solange ein Finger auf der View liegt. Der Tausch aufs Original
     * darf nur dazwischen passieren — setImage waehrend einer laufenden
     * Geste bricht sie ab und blitzt („Blitz beim Ansetzen").
     */
    var isTouching = false
        private set

    /** Wird nach jedem Gestenende (UP/CANCEL) aufgerufen. */
    var onGestureEnd: (() -> Unit)? = null

    /**
     * Hochwischen bei ungezoomtem Bild → Metadaten (Web-Geste aus
     * lightbox-core.js: > 80 px Weg, ueberwiegend vertikal, >= 0,3 px/ms —
     * ein Abrutschen beim Umgreifen reicht bewusst nicht).
     */
    var onSwipeUp: (() -> Unit)? = null

    /** Runterwischen bei ungezoomtem Bild → Lightbox schliessen
     *  (Google-Fotos-Geste); gleiche Schwellen wie onSwipeUp. */
    var onSwipeDown: (() -> Unit)? = null

    /** Kontinuierlicher Runterzieh-Fortschritt in px (>= 0) — das Bild
     *  folgt dem Finger (Google-Fotos-Schliessanimation). */
    var onDismissDrag: ((Float) -> Unit)? = null

    /** Gestenende des Runterziehens: (Gesamtweg px, y-Geschwindigkeit px/s).
     *  Ist gesetzt → ersetzt onSwipeDown (Entscheidung beim Loslassen). */
    var onDismissDragEnd: ((Float, Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var paging = false
    private var vertSwiping = false
    /** Richtung der laufenden vertikalen Geste, beim ersten Ausschlag
     *  festgelegt: +1 hoch (Info), -1 runter (schliessen), 0 keine.
     *  Google Fotos sperrt das ebenso — was runter losgeht, bleibt
     *  schliessen, auch wenn der Finger danach wieder hochwandert. */
    private var vertDir = 0
    private var swipeFired = false
    private var velocityTracker: VelocityTracker? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // VelocityTracker soll Bildschirmkoordinaten sehen — die View bewegt
        // sich waehrend des Blaetterns mit, lokale Koordinaten wackeln.
        val raw = MotionEvent.obtain(event).apply {
            offsetLocation(event.rawX - event.x, event.rawY - event.y)
        }
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouching = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    paging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(raw) }
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(raw)
                    val notZoomed = isReady && scale <= minScale * 1.01f
                    if (!paging && !vertSwiping && notZoomed && event.pointerCount == 1) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        // Richtung erst nach anderthalb Slop entscheiden und
                        // senkrecht bevorzugen: ein Daumen zieht anfangs im
                        // Bogen. Bis 10.10.2026 fiel die Wahl nach ~8 dp,
                        // waagrecht zuerst geprueft (je 1,2-fach) — ein
                        // leicht schraeger Hochwisch wurde zum Blaettern
                        // und blieb es („geht sehr schwer, meist
                        // wird Wisch nach links oder rechts erkannt").
                        // Jetzt: senkrecht ab ~37 Grad, blaettern darunter.
                        val weit = abs(dx) + abs(dy) > touchSlop * 1.5f
                        val senkrecht = weit && abs(dy) > abs(dx) * 0.75f
                        if (weit && !senkrecht) {
                            paging = true
                            lastRawX = event.rawX
                            val cancel = MotionEvent.obtain(event)
                                .apply { action = MotionEvent.ACTION_CANCEL }
                            super.onTouchEvent(cancel)
                            cancel.recycle()
                        } else if (senkrecht) {
                            // Hoch-/Runterwischen: Geste selbst uebernehmen —
                            // am Gestenende kam sie oft gar nicht mehr an
                            // (Bibliothek/Pager verschluckten sie vorher).
                            vertSwiping = true
                            vertDir = if (dy < 0) 1 else -1
                            swipeFired = false
                            parent?.requestDisallowInterceptTouchEvent(true)
                            val cancel = MotionEvent.obtain(event)
                                .apply { action = MotionEvent.ACTION_CANCEL }
                            super.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                    if (paging) {
                        pagerDragListener?.onDrag(event.rawX - lastRawX)
                        lastRawX = event.rawX
                        return true
                    }
                    if (vertSwiping) {
                        val dyUp = downRawY - event.rawY
                        val threshold = 80f * resources.displayMetrics.density
                        if (vertDir > 0) {
                            if (!swipeFired && dyUp > threshold) {
                                swipeFired = true
                                onSwipeUp?.invoke()
                            }
                        } else if (onDismissDrag != null) {
                            // Runterziehen: Bild folgt dem Finger, nie unter
                            // den Startpunkt zurueck; Entscheidung erst beim
                            // Loslassen (ACTION_UP).
                            onDismissDrag?.invoke((-dyUp).coerceAtLeast(0f))
                        } else if (!swipeFired && dyUp < -threshold) {
                            swipeFired = true
                            onSwipeDown?.invoke()
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (paging) {
                        velocityTracker?.addMovement(raw)
                        velocityTracker?.computeCurrentVelocity(1000)
                        pagerDragListener?.onDragEnd(velocityTracker?.xVelocity ?: 0f)
                        endGesture()
                        return true
                    }
                    if (vertSwiping) {
                        val dyDown = event.rawY - downRawY
                        if (vertDir < 0 && dyDown > 0 && onDismissDragEnd != null) {
                            velocityTracker?.addMovement(raw)
                            velocityTracker?.computeCurrentVelocity(1000)
                            onDismissDragEnd?.invoke(
                                dyDown, velocityTracker?.yVelocity ?: 0f,
                            )
                        } else {
                            onDismissDragEnd?.invoke(0f, 0f)
                        }
                        endGesture()
                        return true
                    }
                    endGesture()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (paging) pagerDragListener?.onDragCancel()
                    if (vertSwiping) onDismissDragEnd?.invoke(0f, 0f)
                    endGesture()
                }

                // Zweiter Finger waehrend uebernommener Geste: dabei bleiben
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                    if (paging || vertSwiping) return true
            }
        } finally {
            raw.recycle()
        }
        return super.onTouchEvent(event)
    }

    private fun endGesture() {
        paging = false
        vertSwiping = false
        vertDir = 0
        swipeFired = false
        velocityTracker?.recycle()
        velocityTracker = null
        isTouching = false
        onGestureEnd?.invoke()
    }
}
