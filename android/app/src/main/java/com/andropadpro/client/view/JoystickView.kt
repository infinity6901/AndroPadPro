package com.andropadpro.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var centerX = 0f
    private var centerY = 0f
    private var knobRadius = 0f
    private var maxDistance = 0f

    private var knobX = 0f
    private var knobY = 0f
    private var normalizedX = 0f
    private var normalizedY = 0f
    private var isPressed = false

    var listener: JoystickListener? = null

    interface JoystickListener {
        fun onJoystickMoved(x: Float, y: Float)
        fun onJoystickPressed()
        fun onJoystickReleased()
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#252525")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val knobPaint = Paint().apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val knobPressedPaint = Paint().apply {
        color = Color.parseColor("#107C10")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ── Step 3A — theming ──────────────────────────────────────────────────────

    /**
     * Apply colors from a [GamepadTheme].
     * @param base    outer circle fill
     * @param border  outer circle stroke
     * @param knob    knob fill when not pressed
     * @param pressed knob fill when pressed (accent color)
     */
    fun setColors(base: Int, border: Int, knob: Int, pressed: Int) {
        backgroundPaint.color = base
        borderPaint.color     = border
        knobPaint.color       = knob
        knobPressedPaint.color = pressed
        invalidate()
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX     = w / 2f
        centerY     = h / 2f
        knobRadius  = min(w, h) / 4f
        maxDistance = min(w, h) / 2f - knobRadius
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, maxDistance + knobRadius, backgroundPaint)
        canvas.drawCircle(centerX, centerY, maxDistance + knobRadius, borderPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, if (isPressed) knobPressedPaint else knobPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                listener?.onJoystickPressed()
                updateKnobPosition(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateKnobPosition(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                resetKnob()
                listener?.onJoystickReleased()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnobPosition(x: Float, y: Float) {
        if (maxDistance <= 0f) return
        
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (distance > maxDistance) {
            val angle = Math.atan2(dy.toDouble(), dx.toDouble())
            knobX = centerX + maxDistance * Math.cos(angle).toFloat()
            knobY = centerY + maxDistance * Math.sin(angle).toFloat()
        } else {
            knobX = x
            knobY = y
        }

        normalizedX = (knobX - centerX) / maxDistance
        normalizedY = -(knobY - centerY) / maxDistance
        listener?.onJoystickMoved(normalizedX, normalizedY)
    }

    private fun resetKnob() {
        knobX = centerX; knobY = centerY
        normalizedX = 0f; normalizedY = 0f
        listener?.onJoystickMoved(0f, 0f)
    }

    fun isButtonPressed()  = isPressed
    fun getNormalizedX()   = normalizedX
    fun getNormalizedY()   = normalizedY
}
