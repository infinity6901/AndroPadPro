package com.andropadpro.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen touchpad surface.
 *
 * Gestures:
 *   1-finger drag          → mouse move
 *   1-finger tap           → left click
 *   1-finger double-tap    → double left click
 *   1-finger long-press    → toggle drag-lock (hold left button)
 *   2-finger tap           → right click
 *   2-finger drag V        → vertical scroll
 *   2-finger drag H        → horizontal scroll  ← new
 *   2-finger pinch/spread  → zoom out / zoom in  ← new
 *   3-finger tap           → middle click
 *   3-finger swipe L/R/U/D → directional gesture  ← new
 *
 * Gesture encoding (sent via onGesture callback, mapped to gestureCode byte):
 *   GESTURE_ZOOM_IN  = 1   → server fires Ctrl++
 *   GESTURE_ZOOM_OUT = 2   → server fires Ctrl+-
 *   GESTURE_SWIPE_L  = 3   → server fires Alt+Left  (browser back)
 *   GESTURE_SWIPE_R  = 4   → server fires Alt+Right (browser forward)
 *   GESTURE_SWIPE_U  = 5   → server fires Win+Tab   (task view)
 *   GESTURE_SWIPE_D  = 6   → server fires Win+D     (show desktop)
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ── Gesture codes (match server GESTURE_MAP keys) ─────────────────────────
    companion object {
        const val GESTURE_ZOOM_IN = 1
        const val GESTURE_ZOOM_OUT = 2
        const val GESTURE_SWIPE_L = 3
        const val GESTURE_SWIPE_R = 4
        const val GESTURE_SWIPE_U = 5
        const val GESTURE_SWIPE_D = 6
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onMove:        ((dx: Int, dy: Int) -> Unit)? = null
    var onLeftClick:   (() -> Unit)? = null
    var onRightClick:  (() -> Unit)? = null
    var onMiddleClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    var onScroll:      ((delta: Int) -> Unit)? = null   // vertical scroll ticks
    var onHScroll:     ((delta: Int) -> Unit)? = null   // horizontal scroll ticks
    var onGesture:     ((code: Int) -> Unit)? = null    // zoom / swipe codes
    var onDragState:   ((held: Boolean) -> Unit)? = null

    var sensitivity: Float = 1.8f
    var scrollSensitivity: Float = 0.4f

    // ── Timing / slop ─────────────────────────────────────────────────────────
    private val TAP_TIMEOUT_MS  = 180L
    private val DOUBLE_TAP_MS   = 280L
    private val LONG_PRESS_MS   = 500L
    private val MOVE_SLOP_PX    = 12f
    private val AXIS_DECIDE_PX  = 18f   // px before 2-finger axis is committed
    private val SWIPE_MIN_PX    = 70f   // min 3-finger movement to count as swipe
    private val PINCH_STEP      = 40f   // px of distance change per zoom step

    // ── Single-finger state ────────────────────────────────────────────────────
    private var dragLocked      = false
    private var lastX           = 0f;  private var lastY          = 0f
    private var fingerDownX     = 0f;  private var fingerDownY    = 0f
    private var fingerDownTime  = 0L;  private var fingerMoved    = false
    private var lastTapTime     = 0L

    private val longPressHandler  = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!fingerMoved && !multiTouchActive) {
            dragLocked = !dragLocked
            onDragState?.invoke(dragLocked)
            invalidate()
        }
    }

    // ── Multi-touch state ──────────────────────────────────────────────────────
    private var multiTouchActive = false
    private var peakFingerCount  = 1

    // 2-finger gesture state
    private enum class TwoFingerMode { UNDECIDED, SCROLL_V, SCROLL_H, PINCH }
    private var twoFingerMode    = TwoFingerMode.UNDECIDED
    private var scrollAnchorX    = 0f;  private var scrollAnchorY = 0f
    private var vScrollAccum     = 0f;  private var hScrollAccum  = 0f
    private var pinchBaseDist    = 0f;  private var pinchAccumPx  = 0f
    private var totalMidDeltaX   = 0f;  private var totalMidDeltaY = 0f
    private var totalDistDelta   = 0f
    private var scrollMoved      = false  // any 2-finger movement that consumed scroll/pinch

    // 3-finger swipe tracking
    private var swipeStartX      = 0f;  private var swipeStartY   = 0f
    private var swipeLastX       = 0f;  private var swipeLastY    = 0f

    // ── Paint ──────────────────────────────────────────────────────────────────
    /**
     * When true the solid background is not drawn, making the TouchpadView
     * fully transparent so the PC screen stream (or any background layer)
     * shows through.  The border and label are brightened for readability.
     */
    var screenBackground: Boolean = false
        set(value) { field = value; updatePaintsForMode(); invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2A1A"); style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#107C10"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44107C10")
        textAlign = Paint.Align.CENTER; textSize = 40f; isFakeBoldText = true
    }
    private val dragOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 200, 100); style = Paint.Style.FILL
    }
    // Semi-transparent dark scrim — drawn only when screenBackground=true so
    // touches are still easy to register on a busy screen frame underneath.
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0); style = Paint.Style.FILL
    }
    private val rect = RectF()

    private fun updatePaintsForMode() {
        if (screenBackground) {
            // Bright border + label so they read on any background
            borderPaint.color = Color.argb(200, 20, 200, 20)
            labelPaint.color  = Color.argb(180, 30, 255, 30)
        } else {
            borderPaint.color = Color.parseColor("#107C10")
            labelPaint.color  = Color.parseColor("#44107C10")
        }
    }

    // ── Draw ───────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        if (screenBackground) {
            // Transparent mode: light dark scrim so gestures are still
            // registerable on a busy background, then drag-lock tint on top
            canvas.drawRoundRect(rect, 16f, 16f, scrimPaint)
            if (dragLocked) canvas.drawRoundRect(rect, 16f, 16f, dragOverlayPaint)
        } else {
            // Solid mode: normal opaque background
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
            if (dragLocked) canvas.drawRoundRect(rect, 16f, 16f, dragOverlayPaint)
        }

        val inset = borderPaint.strokeWidth / 2
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, 15f, 15f, borderPaint)
        rect.inset(-inset, -inset)

        canvas.drawText(
            if (dragLocked) "🔒 DRAG" else "TOUCHPAD",
            w / 2f, h / 2f + labelPaint.textSize / 3f, labelPaint
        )

        // Scroll hint lines
        val cx = w / 2f
        val savedAlpha = borderPaint.alpha
        borderPaint.alpha = 60
        canvas.drawLine(cx - 20f, h * 0.3f, cx + 20f, h * 0.3f, borderPaint)
        canvas.drawLine(cx - 20f, h * 0.7f, cx + 20f, h * 0.7f, borderPaint)
        borderPaint.alpha = savedAlpha
    }

    // ── Touch ──────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val count = event.pointerCount

        when (event.actionMasked) {

            // ── First finger down ──────────────────────────────────────────────
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x;  lastY = event.y
                fingerDownX = event.x;  fingerDownY = event.y
                fingerDownTime  = System.currentTimeMillis()
                fingerMoved     = false
                multiTouchActive = false
                peakFingerCount  = 1
                twoFingerMode    = TwoFingerMode.UNDECIDED
                scrollMoved      = false
                vScrollAccum     = 0f;  hScrollAccum = 0f
                pinchAccumPx     = 0f
                totalMidDeltaX   = 0f;  totalMidDeltaY = 0f
                totalDistDelta   = 0f
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }

            // ── Additional finger ──────────────────────────────────────────────
            MotionEvent.ACTION_POINTER_DOWN -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                multiTouchActive = true
                peakFingerCount  = maxOf(peakFingerCount, count)

                if (count == 2) {
                    // Initialise 2-finger tracking
                    scrollAnchorX  = midX(event);  scrollAnchorY = midY(event)
                    pinchBaseDist  = fingerDist(event)
                    twoFingerMode  = TwoFingerMode.UNDECIDED
                    vScrollAccum   = 0f;  hScrollAccum = 0f
                    pinchAccumPx   = 0f
                    totalMidDeltaX = 0f;  totalMidDeltaY = 0f
                    totalDistDelta = 0f
                    scrollMoved    = false
                }
                if (count == 3) {
                    // Record swipe start centroid
                    swipeStartX = centroidX(event);  swipeStartY = centroidY(event)
                    swipeLastX  = swipeStartX;        swipeLastY  = swipeStartY
                }
            }

            // ── Movement ───────────────────────────────────────────────────────
            MotionEvent.ACTION_MOVE -> {
                when {
                    // 3+ finger — track swipe centroid
                    count >= 3 -> {
                        swipeLastX = centroidX(event)
                        swipeLastY = centroidY(event)
                    }

                    // 2-finger gesture
                    multiTouchActive && count == 2 -> {
                        val mx    = midX(event);   val my    = midY(event)
                        val dist  = fingerDist(event)
                        val dMidX = mx - scrollAnchorX
                        val dMidY = my - scrollAnchorY
                        val dDist = dist - pinchBaseDist

                        totalMidDeltaX += dMidX;  totalMidDeltaY += dMidY
                        totalDistDelta += dDist

                        // Commit to a mode once enough movement has accumulated
                        if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                            val midMag  = abs(totalMidDeltaX) + abs(totalMidDeltaY)
                            val distMag = abs(totalDistDelta)
                            if (midMag > AXIS_DECIDE_PX || distMag > AXIS_DECIDE_PX) {
                                twoFingerMode = when {
                                    distMag > midMag * 0.6f -> TwoFingerMode.PINCH
                                    abs(totalMidDeltaY) >= abs(totalMidDeltaX) -> TwoFingerMode.SCROLL_V
                                    else -> TwoFingerMode.SCROLL_H
                                }
                            }
                        }

                        when (twoFingerMode) {
                            TwoFingerMode.SCROLL_V -> {
                                vScrollAccum += dMidY * scrollSensitivity
                                val ticks = vScrollAccum.toInt()
                                if (ticks != 0) { onScroll?.invoke(ticks); vScrollAccum -= ticks; scrollMoved = true }
                            }
                            TwoFingerMode.SCROLL_H -> {
                                hScrollAccum += dMidX * scrollSensitivity
                                val ticks = hScrollAccum.toInt()
                                if (ticks != 0) { onHScroll?.invoke(ticks); hScrollAccum -= ticks; scrollMoved = true }
                            }
                            TwoFingerMode.PINCH -> {
                                // Accumulate pinch distance and fire discrete zoom steps
                                pinchAccumPx += dDist
                                if (pinchAccumPx >= PINCH_STEP) {
                                    onGesture?.invoke(GESTURE_ZOOM_IN)
                                    pinchAccumPx -= PINCH_STEP
                                    scrollMoved = true
                                } else if (pinchAccumPx <= -PINCH_STEP) {
                                    onGesture?.invoke(GESTURE_ZOOM_OUT)
                                    pinchAccumPx += PINCH_STEP
                                    scrollMoved = true
                                }
                            }
                            else -> { /* still deciding — keep accumulating */ }
                        }

                        // Update anchors for next delta
                        scrollAnchorX = mx;  scrollAnchorY = my
                        pinchBaseDist = dist
                    }

                    // Single finger — mouse move
                    !multiTouchActive && count == 1 -> {
                        val dx = ((event.x - lastX) * sensitivity).toInt()
                        val dy = ((event.y - lastY) * sensitivity).toInt()
                        if (dx != 0 || dy != 0) onMove?.invoke(dx, dy)
                        val totalMoved = abs(event.x - fingerDownX) + abs(event.y - fingerDownY)
                        if (!fingerMoved && totalMoved > MOVE_SLOP_PX) {
                            fingerMoved = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                        lastX = event.x;  lastY = event.y
                    }
                }
            }

            // ── Last finger up — classify gesture ──────────────────────────────
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                val now      = System.currentTimeMillis()
                val wasQuick = now - fingerDownTime < TAP_TIMEOUT_MS

                when {
                    // 3-finger swipe
                    peakFingerCount >= 3 -> {
                        val dx = swipeLastX - swipeStartX
                        val dy = swipeLastY - swipeStartY
                        if (abs(dx) > SWIPE_MIN_PX || abs(dy) > SWIPE_MIN_PX) {
                            val code = when {
                                abs(dx) > abs(dy) -> if (dx > 0) GESTURE_SWIPE_R else GESTURE_SWIPE_L
                                else              -> if (dy > 0) GESTURE_SWIPE_D else GESTURE_SWIPE_U
                            }
                            onGesture?.invoke(code)
                        } else {
                            // Barely moved → middle click
                            onMiddleClick?.invoke()
                        }
                    }
                    // 2-finger tap (no scroll/pinch)
                    peakFingerCount == 2 && !scrollMoved -> {
                        onRightClick?.invoke()
                    }
                    // Single-finger tap
                    peakFingerCount == 1 && !fingerMoved && wasQuick -> {
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            onDoubleClick?.invoke()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            onLeftClick?.invoke()
                        }
                    }
                }
                multiTouchActive = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                multiTouchActive = false
            }
        }
        return true
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun midX(e: MotionEvent): Float {
        var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount
    }
    private fun midY(e: MotionEvent): Float {
        var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount
    }
    private fun centroidX(e: MotionEvent) = midX(e)
    private fun centroidY(e: MotionEvent) = midY(e)
    private fun fingerDist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    fun releaseDragLock() {
        if (dragLocked) {
            dragLocked = false
            onDragState?.invoke(false)
            invalidate()
        }
    }
}
