package com.andropadpro.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

class DPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var centerX = 0f
    private var centerY = 0f
    private var armWidth = 0f
    private var armLength = 0f

    enum class Direction { NONE, UP, DOWN, LEFT, RIGHT }
    private var currentDirection = Direction.NONE

    private var isUpPressed    = false
    private var isDownPressed  = false
    private var isLeftPressed  = false
    private var isRightPressed = false

    var listener: DPadListener? = null

    interface DPadListener {
        fun onDirectionChanged(up: Boolean, down: Boolean, left: Boolean, right: Boolean)
    }

    private val paint = Paint().apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pressedPaint = Paint().apply {
        color = Color.parseColor("#107C10")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // ── Step 3A — theming ──────────────────────────────────────────────────────

    fun setColors(baseColor: Int, pressedColor: Int) {
        paint.color        = baseColor
        pressedPaint.color = pressedColor
        borderPaint.color  = adjustAlpha(baseColor, 0.6f)
        invalidate()
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX   = w / 2f
        centerY   = h / 2f
        armWidth  = w / 4f
        armLength = h / 2f - armWidth / 2
    }

    override fun onDraw(canvas: Canvas) {
        val vLeft   = centerX - armWidth / 2
        val vRight  = centerX + armWidth / 2
        val vTop    = centerY - armLength
        val vBottom = centerY + armLength
        val hLeft   = centerX - armLength
        val hRight  = centerX + armLength
        val hTop    = centerY - armWidth / 2
        val hBottom = centerY + armWidth / 2

        canvas.drawRect(vLeft, vTop,  vRight,  vBottom, paint)
        canvas.drawRect(hLeft, hTop,  hRight,  hBottom, paint)

        if (isUpPressed)    canvas.drawRect(vLeft, vTop,                     vRight, centerY - armWidth / 2, pressedPaint)
        if (isDownPressed)  canvas.drawRect(vLeft, centerY + armWidth / 2,  vRight, vBottom,                pressedPaint)
        if (isLeftPressed)  canvas.drawRect(hLeft, hTop,                     centerX - armWidth / 2, hBottom, pressedPaint)
        if (isRightPressed) canvas.drawRect(centerX + armWidth / 2, hTop,   hRight, hBottom,                pressedPaint)

        canvas.drawCircle(centerX, centerY, armWidth / 2, borderPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val (up, down, left, right) = getDirectionsFromTouch(event.x, event.y)
                val changed = (up != isUpPressed) || (down != isDownPressed) ||
                              (left != isLeftPressed) || (right != isRightPressed)
                if (changed) {
                    isUpPressed = up; isDownPressed = down
                    isLeftPressed = left; isRightPressed = right
                    currentDirection = calculateDirection()
                    listener?.onDirectionChanged(up, down, left, right)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUpPressed = false; isDownPressed = false
                isLeftPressed = false; isRightPressed = false
                currentDirection = Direction.NONE
                listener?.onDirectionChanged(false, false, false, false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getDirectionsFromTouch(x: Float, y: Float): Quadruple<Boolean, Boolean, Boolean, Boolean> {
        val dx = x - centerX
        val dy = y - centerY
        if (abs(dx) < armWidth / 2 && abs(dy) < armWidth / 2)
            return Quadruple(false, false, false, false)
        return Quadruple(
            dy < -armWidth / 2,
            dy >  armWidth / 2,
            dx < -armWidth / 2,
            dx >  armWidth / 2
        )
    }

    private fun calculateDirection(): Direction = when {
        isUpPressed    && !isDownPressed && !isLeftPressed && !isRightPressed -> Direction.UP
        isDownPressed  && !isUpPressed   && !isLeftPressed && !isRightPressed -> Direction.DOWN
        isLeftPressed  && !isUpPressed   && !isDownPressed && !isRightPressed -> Direction.LEFT
        isRightPressed && !isUpPressed   && !isDownPressed && !isLeftPressed  -> Direction.RIGHT
        isUpPressed    && isLeftPressed  -> Direction.UP
        isUpPressed    && isRightPressed -> Direction.UP
        isDownPressed  && isLeftPressed  -> Direction.DOWN
        isDownPressed  && isRightPressed -> Direction.DOWN
        else -> Direction.NONE
    }

    fun getDirection(): Direction = currentDirection
}
