package com.example.driver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Zero-allocation studio DSP filter chain for graphics tablets (v2.0).
 *
 * Includes:
 * 1. Tilt Compensation (angular offset correction)
 * 2. Anti-Jitter Deadzone (sub-threshold static tremor suppression)
 * 3. Hysteresis (low-velocity micro-wobble clamping)
 * 4. One-Euro (1€) Adaptive Low-Pass Filter (zero lag on fast strokes, silky stability on slow lines)
 * 5. Optional Exponential Moving Average (EMA) smoothing
 * 6. Velocity-based Motion Prediction (compensates Android display pipeline latency)
 */
class OptionalDspFilters {

    private var hasLast = false
    private var prevX = 0f
    private var prevY = 0f
    private var prevRawX = 0f
    private var prevRawY = 0f
    private var prevDxFilt = 0f
    private var prevDyFilt = 0f
    private var prevTimestampNs = 0L

    fun reset() {
        hasLast = false
        prevX = 0f
        prevY = 0f
        prevRawX = 0f
        prevRawY = 0f
        prevDxFilt = 0f
        prevDyFilt = 0f
        prevTimestampNs = 0L
    }

    /**
     * Applies enabled filters in-place on [outXY] (float array size >= 2).
     * Zero heap allocations in the hot loop.
     */
    fun process(
        rawX: Float,
        rawY: Float,
        tiltXDeg: Float,
        tiltYDeg: Float,
        settings: FilterSettings,
        outXY: FloatArray,
        timestampNs: Long = System.nanoTime()
    ) {
        var x = rawX
        var y = rawY

        // 1. Optional Tilt Compensation
        if (settings.tiltCompensationEnabled) {
            val clampedTx = tiltXDeg.coerceIn(-65f, 65f)
            val clampedTy = tiltYDeg.coerceIn(-65f, 65f)
            val radX = clampedTx * DEG_TO_RAD
            val radY = clampedTy * DEG_TO_RAD
            val offsetScale = settings.tiltFactor.coerceIn(0.05f, 1.0f) * 140f
            x += tan(radX) * offsetScale
            y += tan(radY) * offsetScale
        }

        if (!hasLast) {
            prevX = x
            prevY = y
            prevRawX = x
            prevRawY = y
            prevDxFilt = 0f
            prevDyFilt = 0f
            prevTimestampNs = timestampNs
            hasLast = true
            outXY[0] = x
            outXY[1] = y
            return
        }

        val dtSec = if (prevTimestampNs > 0L && timestampNs > prevTimestampNs) {
            ((timestampNs - prevTimestampNs) * 1e-9f).coerceIn(0.001f, 0.05f)
        } else {
            0.004f // ~250Hz nominal fallback
        }
        prevTimestampNs = timestampNs

        val dx = x - prevX
        val dy = y - prevY
        val dist = hypot(dx, dy)

        val preFilterX = x
        val preFilterY = y

        // 2. Optional Anti-Jitter Deadzone
        if (settings.antiJitterEnabled && dist < settings.antiJitterRadius) {
            prevRawX = preFilterX
            prevRawY = preFilterY
            outXY[0] = prevX
            outXY[1] = prevY
            return
        }

        // 3. Optional Hysteresis (pulls movement back by threshold radius to remove micro-wobble)
        if (settings.hysteresisEnabled) {
            val h = settings.hysteresisThreshold.coerceAtLeast(0.5f)
            if (dist <= h) {
                prevRawX = preFilterX
                prevRawY = preFilterY
                outXY[0] = prevX
                outXY[1] = prevY
                return
            } else {
                val factor = (dist - h) / dist
                x = prevX + dx * factor
                y = prevY + dy * factor
            }
        }

        // 4. Optional One-Euro (1€) Adaptive Velocity Filter
        if (settings.oneEuroEnabled) {
            val vxRaw = (x - prevRawX) / dtSec
            val vyRaw = (y - prevRawY) / dtSec
            val alphaD = oneEuroAlpha(dtSec, DERIVATE_CUTOFF_HZ)
            val vxFilt = prevDxFilt + alphaD * (vxRaw - prevDxFilt)
            val vyFilt = prevDyFilt + alphaD * (vyRaw - prevDyFilt)
            prevDxFilt = vxFilt
            prevDyFilt = vyFilt

            val speed = hypot(vxFilt, vyFilt)
            val cutoff = settings.oneEuroMinCutoff.coerceIn(0.2f, 10f) +
                settings.oneEuroBeta.coerceIn(0.001f, 1.0f) * speed * 0.01f
            val alpha = oneEuroAlpha(dtSec, cutoff)
            x = prevX + alpha * (x - prevX)
            y = prevY + alpha * (y - prevY)
        }

        // 5. Optional Exponential Moving Average (EMA) Smoothing
        if (settings.smoothingEnabled) {
            val a = settings.smoothingAlpha.coerceIn(0.05f, 1.0f)
            x = prevX + a * (x - prevX)
            y = prevY + a * (y - prevY)
        }

        val outVx = (x - prevX) / dtSec
        val outVy = (y - prevY) / dtSec

        prevRawX = preFilterX
        prevRawY = preFilterY
        prevX = x
        prevY = y

        // 6. Optional Predictive Latency Compensation (clamped for zero overshoot)
        if (settings.predictionEnabled) {
            val leadSec = (settings.predictionLeadMs.coerceIn(0.5f, 12.0f)) * 0.001f
            val predDx = (outVx * leadSec).coerceIn(-120f, 120f)
            val predDy = (outVy * leadSec).coerceIn(-120f, 120f)
            if (abs(predDx) > 0.1f || abs(predDy) > 0.1f) {
                x += predDx
                y += predDy
            }
        }

        outXY[0] = x
        outXY[1] = y
    }

    private fun oneEuroAlpha(dtSec: Float, cutoffHz: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(0.1f))
        return (1f / (1f + tau / dtSec)).coerceIn(0.02f, 1.0f)
    }

    companion object {
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()
        private const val DERIVATE_CUTOFF_HZ = 1.0f
    }
}
