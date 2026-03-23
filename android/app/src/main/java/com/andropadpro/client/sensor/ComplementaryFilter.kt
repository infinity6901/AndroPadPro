package com.andropadpro.client.sensor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Complementary filter fusing GYROSCOPE + ACCELEROMETER for stable pitch/roll.
 *
 * Fix v3.1 — gyro was stiff and unresponsive:
 *
 *   BUG: normalizedPitch/Roll() divided by Math.PI (~3.14 radians).
 *        A typical gaming tilt is 20–45°= 0.35–0.79 rad → only 11–25% stick
 *        deflection. The stick felt glued to centre.
 *   FIX: Divide by PI/2 (~1.57 rad). Now 90° = 100% deflection, which means
 *        a comfortable 30–45° tilt gives 33–50% — responsive and natural.
 *
 *   BUG: Pitch Y-axis was not negated for landscape orientation.
 *        Tilting phone forward pushed the stick in the wrong direction.
 *   FIX: normalizedPitch() negates the result. Tilt forward = stick down,
 *        which matches how a real gamepad stick feels.
 *
 *   BUG: stillThreshold = 30 frames at SENSOR_DELAY_GAME (~5 ms each) = 150 ms.
 *        Any momentary pause snapped the stick to zero, causing jerky movement.
 *   FIX: Raised to 60 frames (~300 ms). Brief pauses no longer zero the output.
 *
 *   BUG: gyroThreshold = 0.1 rad/s filtered out slow deliberate tilts (~6°/s).
 *   FIX: Lowered to 0.03 rad/s. Slow steering inputs now register correctly.
 */
class ComplementaryFilter(val alpha: Float = 0.96f) {

    // Radians — range roughly -PI to PI, but clamped at normalisation
    var pitch = 0f   // rotation around X axis (tilt forward/back)
        private set
    var roll  = 0f   // rotation around Y axis (tilt left/right)
        private set

    private var lastTimestampNs = 0L
    private var initialised     = false

    // Increased from 0.1 → 0.03 so slow tilts are no longer filtered out
    private val gyroThreshold = 0.03f

    // Stillness detection
    private var stillCounter = 0
    // Raised from 30 → 60 frames so brief pauses don't snap to zero
    private val stillThreshold = 60
    var isPhoneStill: Boolean = true
        private set

    /**
     * Feed one pair of sensor readings into the filter.
     *
     * Call this from onSensorChanged for BOTH TYPE_GYROSCOPE and TYPE_ACCELEROMETER.
     * Buffer gyro values and call update() on each accelerometer event.
     *
     * @param gyroX  rad/s around X axis  (GYROSCOPE sensor)
     * @param gyroY  rad/s around Y axis
     * @param accelX m/s² on X axis       (ACCELEROMETER sensor)
     * @param accelY m/s² on Y axis
     * @param accelZ m/s² on Z axis
     * @param timestampNs  event.timestamp in nanoseconds
     */
    fun update(
        gyroX: Float, gyroY: Float,
        accelX: Float, accelY: Float, accelZ: Float,
        timestampNs: Long,
    ) {
        // ── dt ────────────────────────────────────────────────────────────────
        val dtSec = if (!initialised || lastTimestampNs == 0L) {
            initialised     = true
            lastTimestampNs = timestampNs
            0f
        } else {
            ((timestampNs - lastTimestampNs) / 1_000_000_000.0)
                .toFloat()
                .coerceIn(0f, 0.1f)  // cap at 100 ms to ignore huge gaps
        }
        lastTimestampNs = timestampNs
        if (dtSec == 0f) return

        // ── Gyro threshold (noise gate) ───────────────────────────────────────
        val gx = if (abs(gyroX) < gyroThreshold) 0f else gyroX
        val gy = if (abs(gyroY) < gyroThreshold) 0f else gyroY

        // ── Stillness detection ───────────────────────────────────────────────
        val gyroMag = abs(gx) + abs(gy)
        if (gyroMag < 0.05f) stillCounter++ else stillCounter = 0
        isPhoneStill = stillCounter >= stillThreshold

        if (isPhoneStill) {
            // Gently return to zero rather than snapping, so resumed motion
            // starts from near-zero rather than from wherever drift landed.
            pitch *= 0.85f
            roll  *= 0.85f
            return
        }

        // ── Gyro integration ──────────────────────────────────────────────────
        pitch += gx * dtSec
        roll  += gy * dtSec

        // ── Accel reference angles ────────────────────────────────────────────
        val accelPitch = atan2(accelY.toDouble(), accelZ.toDouble()).toFloat()
        val accelRoll  = atan2(-accelX.toDouble(),
            sqrt((accelY * accelY + accelZ * accelZ).toDouble())).toFloat()

        // ── Blend ─────────────────────────────────────────────────────────────
        pitch = alpha * pitch + (1f - alpha) * accelPitch
        roll  = alpha * roll  + (1f - alpha) * accelRoll
    }

    /**
     * Normalised pitch in [-1, 1].
     *
     * Divides by PI/2 so 90° tilt = full deflection (was PI = 180° needed).
     * Result is negated so tilting the phone forward = stick DOWN, matching
     * real gamepad feel in landscape orientation.
     */
    fun normalizedPitch(): Float =
        -(pitch / (PI / 2).toFloat()).coerceIn(-1f, 1f)

    /**
     * Normalised roll in [-1, 1].
     *
     * Divides by PI/2 so 90° tilt = full deflection.
     * Tilting the phone right (roll positive) = stick RIGHT.
     */
    fun normalizedRoll(): Float =
        (roll / (PI / 2).toFloat()).coerceIn(-1f, 1f)

    /** Resets all state — call when user presses CAL. */
    fun reset() {
        pitch           = 0f
        roll            = 0f
        lastTimestampNs = 0L
        initialised     = false
        stillCounter    = 0
        isPhoneStill    = true
    }
}
