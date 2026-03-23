package com.andropadpro.client.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Full controller state — serialises to the 22-byte packet expected by the server.
 *
 * Byte layout (little-endian):
 *  [0:2]   buttons       uint16  — bitmask of 14 buttons
 *  [2]     leftStickX    int8    — signed -128..127
 *  [3]     leftStickY    int8
 *  [4]     rightStickX   int8
 *  [5]     rightStickY   int8
 *  [6]     leftTrigger   uint8   — 0..255  (analog)
 *  [7]     rightTrigger  uint8
 *  [8]     sequence      uint8   — wraps 0-255
 *  [9:11]  mouseDx       int16   — touchpad mouse delta X
 *  [11:13] mouseDy       int16
 *  [13]    mouseButtons  uint8   — bits 0-4: mouse btns+scroll  bits 5-6: h-scroll
 *  [14]    keyboardKey   uint8
 *  [15]    gestureCode   uint8   — touchpad gesture (pinch/swipe)
 *  [16:18] gyroX         int16   — normalised pitch × 32767
 *  [18:20] gyroY         int16   — normalised roll  × 32767
 *  [20:22] gyroZ         int16   — normalised yaw   × 32767
 */
data class ControllerState(
    var buttons: Int = 0,
    var leftStickX: Int = 0,
    var leftStickY: Int = 0,
    var rightStickX: Int = 0,
    var rightStickY: Int = 0,
    var leftTrigger: Int = 0,
    var rightTrigger: Int = 0,
    var sequence: Int = 0,
    // Extended 22-byte fields
    var mouseDx: Int = 0,
    var mouseDy: Int = 0,
    var mouseButtons: Int = 0,
    var keyboardKey: Int = 0,
    var gestureCode: Int = 0,   // byte 15: was reserved, now touchpad gestures
    var gyroX: Float = 0f,
    var gyroY: Float = 0f,
    var gyroZ: Float = 0f
) {
    companion object {
        const val DPAD_UP    = 0
        const val DPAD_DOWN  = 1
        const val DPAD_LEFT  = 2
        const val DPAD_RIGHT = 3
        const val START      = 4
        const val SELECT     = 5
        const val L3         = 6
        const val R3         = 7
        const val LB         = 8
        const val RB         = 9
        const val A          = 10
        const val B          = 11
        const val X          = 12
        const val Y          = 13
        const val PACKET_SIZE = 22
    }

    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(buttons.toShort())
        buf.put(leftStickX.toByte())
        buf.put(leftStickY.toByte())
        buf.put(rightStickX.toByte())
        buf.put(rightStickY.toByte())
        buf.put(leftTrigger.toByte())
        buf.put(rightTrigger.toByte())
        buf.put(sequence.toByte())
        buf.putShort(mouseDx.coerceIn(-32768, 32767).toShort())
        buf.putShort(mouseDy.coerceIn(-32768, 32767).toShort())
        buf.put(mouseButtons.toByte())
        buf.put(keyboardKey.toByte())
        buf.put(gestureCode.toByte())   // byte 15: was reserved
        buf.putShort((gyroX * 32767f).toInt().coerceIn(-32767, 32767).toShort())
        buf.putShort((gyroY * 32767f).toInt().coerceIn(-32767, 32767).toShort())
        buf.putShort((gyroZ * 32767f).toInt().coerceIn(-32767, 32767).toShort())
        return buf.array()
    }

    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or (1 shl bit)
                  else         buttons and (1 shl bit).inv()
    }

    fun isButtonPressed(bit: Int) = (buttons and (1 shl bit)) != 0

    fun setLeftStick(x: Float, y: Float) {
        leftStickX = (x * 127).toInt().coerceIn(-128, 127)
        leftStickY = (y * 127).toInt().coerceIn(-128, 127)
    }

    fun setRightStick(x: Float, y: Float) {
        rightStickX = (x * 127).toInt().coerceIn(-128, 127)
        rightStickY = (y * 127).toInt().coerceIn(-128, 127)
    }

    fun setLeftTrigger(value: Float) {
        leftTrigger = (value * 255).toInt().coerceIn(0, 255)
    }

    fun setRightTrigger(value: Float) {
        rightTrigger = (value * 255).toInt().coerceIn(0, 255)
    }
}
