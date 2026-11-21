package com.example.driver

import kotlin.math.roundToInt

/**
 * Pre-baked Lookup Table (LUT) for O(1) pressure curve evaluation in the USB hot path.
 * Supports native 2048, 4096, and 8192 pressure levels without runtime spline math or GC.
 */
class PressureCurveLut(private val maxTableIndex: Int = 8192) {

    private val lut = FloatArray(maxTableIndex + 1)

    init {
        rebuild(
            points = listOf(
                PressureControlPoint(0f, 0f),
                PressureControlPoint(1f, 1f)
            ),
            minThreshold = 0.01f,
            maxCap = 1.0f
        )
    }

    fun rebuild(
        points: List<PressureControlPoint>,
        minThreshold: Float,
        maxCap: Float
    ) {
        val sorted = (if (points.size >= 2) points else listOf(
            PressureControlPoint(0f, 0f),
            PressureControlPoint(1f, 1f)
        )).sortedBy { it.inputNorm }

        val minT = minThreshold.coerceIn(0f, 0.9f)
        val cap = maxCap.coerceIn(minT + 0.05f, 1f)

        for (i in 0..maxTableIndex) {
            val x = i.toFloat() / maxTableIndex.toFloat()
            if (x < minT) {
                lut[i] = 0f
                continue
            }
            val remappedX = ((x - minT) / (1f - minT)).coerceIn(0f, 1f)
            val curved = evaluateCatmullOrLinear(sorted, remappedX)
            lut[i] = (curved * cap).coerceIn(0f, 1f)
        }
    }

    private fun evaluateCatmullOrLinear(pts: List<PressureControlPoint>, x: Float): Float {
        if (x <= pts.first().inputNorm) return pts.first().outputNorm
        if (x >= pts.last().inputNorm) return pts.last().outputNorm

        var seg = 0
        for (i in 0 until pts.size - 1) {
            if (x >= pts[i].inputNorm && x <= pts[i + 1].inputNorm) {
                seg = i
                break
            }
        }

        val p0 = pts[(seg - 1).coerceAtLeast(0)]
        val p1 = pts[seg]
        val p2 = pts[seg + 1]
        val p3 = pts[(seg + 2).coerceAtMost(pts.size - 1)]

        val span = (p2.inputNorm - p1.inputNorm).coerceAtLeast(1e-5f)
        val t = ((x - p1.inputNorm) / span).coerceIn(0f, 1f)

        // Smooth Catmull-Rom interpolation for responsive OTD-style pressure curve
        val t2 = t * t
        val t3 = t2 * t
        val y = 0.5f * (
            (2f * p1.outputNorm) +
                (-p0.outputNorm + p2.outputNorm) * t +
                (2f * p0.outputNorm - 5f * p1.outputNorm + 4f * p2.outputNorm - p3.outputNorm) * t2 +
                (-p0.outputNorm + 3f * p1.outputNorm - 3f * p2.outputNorm + p3.outputNorm) * t3
            )
        return y.coerceIn(0f, 1f)
    }

    /**
     * Zero-allocation O(1) pressure evaluation from native tablet raw pressure.
     */
    fun lookup(rawPressure: Int, nativeMaxPressure: Int): Float {
        if (rawPressure <= 0 || nativeMaxPressure <= 0) return 0f
        val idx = ((rawPressure.toFloat() / nativeMaxPressure.toFloat()) * maxTableIndex)
            .roundToInt()
            .coerceIn(0, maxTableIndex)
        return lut[idx]
    }
}
