package com.andropadpro.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Analog trigger view. Touch Y position maps to trigger value 0.0–1.0.
 *
 * - Finger at the top of the view  → 0.0  (not pressed)
 * - Finger at the bottom           → 1.0  (fully pressed)
 * - Release                        → snaps back to 0.0
 *
 * Draws a vertical fill bar that grows upward as the value increases,
 * matching the physical feel of pulling a trigger from rest to full press.
 */
class TriggerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var value = 0f
    private var label = "LT"

    /** Called on every value change, including release (value = 0). */
    var onValueChanged: ((Float) -> Unit)? = null

    // ── Colors ────────────────────────────────────────────────────────────────

    private var emptyColor  = Color.parseColor("#2D2D2D")
    private var fillColor   = Color.parseColor("#107C10")
    private var fillColor2  = Color.parseColor("#1AAD1A")   // gradient end

    /** Sync with theme system. */
    fun setColors(empty: Int, fill: Int) {
        emptyColor = empty
        fillColor  = fill
        // Derive lighter gradient end
        fillColor2 = blendColor(fill, Color.WHITE, 0.25f)
        gradientDirty = true
        invalidate()
    }

    fun setLabel(text: String) { label = text; invalidate() }
    fun getValue() = value

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#606060")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        alpha = 200
    }

    // ── Gradient (rebuilt when size or colors change) ─────────────────────────

    private var gradientDirty = true
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        val sp = w.coerceAtMost(h) / 2.5f
        labelPaint.textSize = sp
        pctPaint.textSize   = sp * 0.72f
        gradientDirty = true
    }

    private fun ensureGradient() {
        if (!gradientDirty) return
        if (width == 0 || height == 0) return
        fillPaint.shader = LinearGradient(
            0f, height.toFloat(), 0f, 0f,
            fillColor, fillColor2,
            Shader.TileMode.CLAMP
        )
        gradientDirty = false
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 10f

        ensureGradient()

        // Background
        bgPaint.color = emptyColor
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // Fill — grows from bottom
        if (value > 0.005f) {
            val fillH = h * value
            val fillRect = RectF(0f, h - fillH, w, h)
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }

        // Border
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // Label (top third)
        canvas.drawText(label, w / 2f, h * 0.32f + labelPaint.textSize / 3f, labelPaint)

        // Percentage (bottom third, only when active)
        if (value > 0.02f) {
            val pct = "${(value * 100).toInt()}%"
            canvas.drawText(pct, w / 2f, h * 0.70f + pctPaint.textSize / 3f, pctPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val newValue = (event.y / height.toFloat()).coerceIn(0f, 1f)
                if (newValue != value) {
                    value = newValue
                    onValueChanged?.invoke(value)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (value != 0f) {
                    value = 0f
                    onValueChanged?.invoke(0f)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(c1)   * (1 - r) + Color.red(c2)   * r).toInt(),
            (Color.green(c1) * (1 - r) + Color.green(c2) * r).toInt(),
            (Color.blue(c1)  * (1 - r) + Color.blue(c2)  * r).toInt()
        )
    }
}
