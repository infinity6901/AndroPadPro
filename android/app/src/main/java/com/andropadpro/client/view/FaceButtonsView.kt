package com.andropadpro.client.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class FaceButtonsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var centerX = 0f
    private var centerY = 0f
    private var buttonRadius = 0f

    data class ButtonState(val x: Float, val y: Float, var pressed: Boolean = false)

    private lateinit var aButton: ButtonState
    private lateinit var bButton: ButtonState
    private lateinit var xButton: ButtonState
    private lateinit var yButton: ButtonState

    private val pointerToButton = mutableMapOf<Int, String>()

    var listener: FaceButtonsListener? = null

    interface FaceButtonsListener {
        fun onButtonPressed(button: String)
        fun onButtonReleased(button: String)
    }

    // ── Paints (one per button so each can have its own color) ────────────────

    private val paintA = Paint().apply { color = Color.parseColor("#107C10"); style = Paint.Style.FILL; isAntiAlias = true }
    private val paintB = Paint().apply { color = Color.parseColor("#D0393B"); style = Paint.Style.FILL; isAntiAlias = true }
    private val paintX = Paint().apply { color = Color.parseColor("#106EBE"); style = Paint.Style.FILL; isAntiAlias = true }
    private val paintY = Paint().apply { color = Color.parseColor("#FFBA00"); style = Paint.Style.FILL; isAntiAlias = true }

    private val pressedAlpha = 180  // darken when pressed

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 40f
        isAntiAlias = true
    }

    // ── Step 3A — theming ──────────────────────────────────────────────────────

    fun setColors(colorA: Int, colorB: Int, colorX: Int, colorY: Int, textColor: Int) {
        paintA.color    = colorA
        paintB.color    = colorB
        paintX.color    = colorX
        paintY.color    = colorY
        textPaint.color = textColor
        invalidate()
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX      = w / 2f
        centerY      = h / 2f
        buttonRadius = minOf(w, h) / 6f
        val offset   = w / 4f
        yButton = ButtonState(centerX,          centerY - offset)
        xButton = ButtonState(centerX - offset, centerY)
        aButton = ButtonState(centerX,          centerY + offset)
        bButton = ButtonState(centerX + offset, centerY)
    }

    override fun onDraw(canvas: Canvas) {
        drawButton(canvas, yButton, "Y", paintY)
        drawButton(canvas, xButton, "X", paintX)
        drawButton(canvas, aButton, "A", paintA)
        drawButton(canvas, bButton, "B", paintB)
    }

    private fun drawButton(canvas: Canvas, button: ButtonState, label: String, paint: Paint) {
        val alphaSaved = paint.alpha
        if (button.pressed) paint.alpha = pressedAlpha
        canvas.drawCircle(button.x, button.y, buttonRadius, paint)
        paint.alpha = alphaSaved
        canvas.drawText(label, button.x, button.y + textPaint.textSize / 3, textPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId    = event.getPointerId(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val btn = findButtonAt(event.getX(pointerIndex), event.getY(pointerIndex))
                if (btn != null) { pointerToButton[pointerId] = btn; pressButton(btn) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerToButton.remove(pointerId)?.let { releaseButton(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerToButton.clear(); resetAllButtons(); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id  = event.getPointerId(i)
                    val cur = pointerToButton[id]
                    val new = findButtonAt(event.getX(i), event.getY(i))
                    if (cur != new) {
                        cur?.let { releaseButton(it) }
                        if (new != null) { pointerToButton[id] = new; pressButton(new) }
                        else             { pointerToButton.remove(id) }
                    }
                }
                invalidate(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findButtonAt(x: Float, y: Float): String? {
        val all = listOf("Y" to yButton, "X" to xButton, "A" to aButton, "B" to bButton)
        return all.minByOrNull { (_, b) ->
            sqrt(((x - b.x) * (x - b.x) + (y - b.y) * (y - b.y)).toDouble()).toFloat()
        }?.takeIf { (_, b) ->
            sqrt(((x - b.x) * (x - b.x) + (y - b.y) * (y - b.y)).toDouble()).toFloat() < buttonRadius * 1.5f
        }?.first
    }

    private fun pressButton(button: String) {
        val b = btn(button)
        if (b != null && !b.pressed) { b.pressed = true; listener?.onButtonPressed(button); invalidate() }
    }

    private fun releaseButton(button: String) {
        val b = btn(button)
        if (b != null && b.pressed) { b.pressed = false; listener?.onButtonReleased(button); invalidate() }
    }

    private fun resetAllButtons() {
        listOf("Y" to yButton, "X" to xButton, "A" to aButton, "B" to bButton).forEach { (lbl, b) ->
            if (b.pressed) { b.pressed = false; listener?.onButtonReleased(lbl) }
        }
    }

    private fun btn(label: String) = when (label) {
        "Y" -> yButton; "X" -> xButton; "A" -> aButton; "B" -> bButton; else -> null
    }

    fun isAPressed() = aButton.pressed
    fun isBPressed() = bButton.pressed
    fun isXPressed() = xButton.pressed
    fun isYPressed() = yButton.pressed
}
