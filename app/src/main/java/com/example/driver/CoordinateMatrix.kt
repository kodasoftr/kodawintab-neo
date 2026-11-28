package com.example.driver

/**
 * Pre-calculated 2x3 affine transformation matrix for sub-pixel coordinate mapping.
 *
 * v2.1:
 * - True physical DPI mapping (`DpiNormalizationMode.MANUAL`) where changing DPI (160..1200)
 *   genuinely scales the tablet's physical active mm-to-pixel precision ratio.
 * - Constant-density Landscape/Portrait scaling (`DpiNormalizationMode.AUTO`) so laying the phone
 *   sideways never slows down or makes the tablet feel heavy (identical px/mm in both orientations).
 * - Preserves 1:1 circular aspect symmetry & zero-allocation hot path (4 muls + 2 adds).
 */
class CoordinateMatrix {

    @Volatile private var m00 = 1080f
    @Volatile private var m01 = 0f
    @Volatile private var m02 = 0f
    @Volatile private var m10 = 0f
    @Volatile private var m11 = 1920f
    @Volatile private var m12 = 0f

    @Volatile private var invMaxX = 1f / 32767f
    @Volatile private var invMaxY = 1f / 32767f
    @Volatile private var outMinX = 0f
    @Volatile private var outMaxX = 1080f
    @Volatile private var outMinY = 0f
    @Volatile private var outMaxY = 1920f

    @Volatile var effectiveRotationDeg: Int = 0
        private set
    @Volatile var effectiveDpiX: Float = 420f
        private set
    @Volatile var effectiveDpiY: Float = 420f
        private set
    @Volatile var effectivePxPerMmX: Float = 16.5f
        private set
    @Volatile var effectivePxPerMmY: Float = 16.5f
        private set
    @Volatile var activeWidthMm: Float = 160f
        private set
    @Volatile var activeHeightMm: Float = 100f
        private set

    fun rebuild(
        tabletMaxX: Int,
        tabletMaxY: Int,
        activeArea: ActiveAreaRect,
        screenArea: ActiveAreaRect,
        displayWidthPx: Float,
        displayHeightPx: Float,
        manualRotationDeg: Int,
        autoRotation: Boolean,
        displayRotationDeg: Int,
        invertX: Boolean,
        invertY: Boolean,
        rotateAxesWithScreen: Boolean = true,
        lockAspectRatio: Boolean = false,
        dpiMode: DpiNormalizationMode = DpiNormalizationMode.OFF,
        targetDpiX: Float = 420f,
        targetDpiY: Float = 420f,
        tabletWidthMm: Float = 160f,
        tabletHeightMm: Float = 100f
    ) {
        val safeMaxX = tabletMaxX.coerceAtLeast(1)
        val safeMaxY = tabletMaxY.coerceAtLeast(1)
        invMaxX = 1f / safeMaxX.toFloat()
        invMaxY = 1f / safeMaxY.toFloat()

        // Active tablet region normalized [0..1]
        var aw = activeArea.widthNorm.coerceIn(0.05f, 1f)
        var ah = activeArea.heightNorm.coerceIn(0.05f, 1f)
        var ax = activeArea.leftNorm.coerceIn(0f, 1f - aw)
        var ay = activeArea.topNorm.coerceIn(0f, 1f - ah)

        val safeDpiX = targetDpiX.coerceIn(72f, 1200f)
        val safeDpiY = targetDpiY.coerceIn(72f, 1200f)
        effectiveDpiX = safeDpiX
        effectiveDpiY = safeDpiY

        // Effective rotation:
        // - Horizontal when autoRotation && rotateAxesWithScreen: compensates Android CCW screen rotation.
        // - When rotateAxesWithScreen == false (default: tablet sits horizontally on desk):
        //   tablet keeps manualRotationDeg (0°) so left/right on tablet ALWAYS stays left/right on phone
        //   without flipping upside down when laying phone sideways.
        val autoDelta = if (autoRotation && rotateAxesWithScreen) ((360 - displayRotationDeg) % 360) else 0
        val totalRot = (((manualRotationDeg + autoDelta) % 360) + 360) % 360
        effectiveRotationDeg = totalRot

        // Compute target screen bounds
        val safeDispW = displayWidthPx.coerceAtLeast(1f)
        val safeDispH = displayHeightPx.coerceAtLeast(1f)
        val sw = (screenArea.widthNorm.coerceIn(0.05f, 1f)) * safeDispW
        val sh = (screenArea.heightNorm.coerceIn(0.05f, 1f)) * safeDispH
        val screenX = (screenArea.leftNorm.coerceIn(0f, 0.95f)) * safeDispW
        val screenY = (screenArea.topNorm.coerceIn(0f, 0.95f)) * safeDispH

        val fullTabW = tabletWidthMm.coerceAtLeast(10f)
        val fullTabH = tabletHeightMm.coerceAtLeast(10f)
        val availTabW = fullTabW * aw
        val availTabH = fullTabH * ah
        val rotatedIsSideways = totalRot == 90 || totalRot == 270
        val effTabW = if (rotatedIsSideways) availTabH else availTabW
        val effTabH = if (rotatedIsSideways) availTabW else availTabH

        when {
            dpiMode == DpiNormalizationMode.AUTO -> {
                // Constant physical density (px/mm) across BOTH Portrait & Landscape orientations:
                // Anchors stroke speed to the tightest screen-to-tablet ratio so laying the phone
                // sideways never stretches active area across full width and never feels heavy.
                val maxScreenPx = maxOf(sw, sh)
                val minScreenPx = minOf(sw, sh)
                val maxTabMm = maxOf(effTabW, effTabH).coerceAtLeast(1f)
                val minTabMm = minOf(effTabW, effTabH).coerceAtLeast(1f)
                val portraitDensity = maxScreenPx / minTabMm
                val landscapeDensity = maxScreenPx / maxTabMm
                val baselinePxPerMm = if (minScreenPx / portraitDensity <= minTabMm &&
                    maxScreenPx / portraitDensity <= maxTabMm
                ) {
                    portraitDensity
                } else {
                    maxOf(portraitDensity.coerceAtMost(maxScreenPx / minTabMm), landscapeDensity)
                }.coerceAtLeast(0.1f)

                var targetW = (sw / baselinePxPerMm).coerceAtLeast(1f)
                var targetH = (sh / baselinePxPerMm).coerceAtLeast(1f)
                val fitScale = minOf(effTabW / targetW, effTabH / targetH, 1f)
                targetW *= fitScale
                targetH *= fitScale

                val newAw = if (!rotatedIsSideways) (targetW / fullTabW).coerceIn(minOf(0.12f, aw), aw) else (targetH / fullTabW).coerceIn(minOf(0.12f, aw), aw)
                val newAh = if (!rotatedIsSideways) (targetH / fullTabH).coerceIn(minOf(0.12f, ah), ah) else (targetW / fullTabH).coerceIn(minOf(0.12f, ah), ah)
                ax += (aw - newAw) * 0.5f
                ay += (ah - newAh) * 0.5f
                aw = newAw
                ah = newAh
            }

            dpiMode == DpiNormalizationMode.MANUAL -> {
                // Ergonomic physical DPI scale anchored cleanly inside the tablet's 1:1 fitting box
                // (240 DPI -> full ergonomic span, 440 DPI -> ~55% balanced, 640+ DPI -> compact agile)
                // while preserving constant density across Portrait & Landscape and independent X/Y tuning.
                val maxScreenPx = maxOf(sw, sh)
                val minScreenPx = minOf(sw, sh)
                val maxTabMm = maxOf(effTabW, effTabH).coerceAtLeast(1f)
                val minTabMm = minOf(effTabW, effTabH).coerceAtLeast(1f)
                val portraitDensity = maxScreenPx / minTabMm
                val baseDensity = if (minScreenPx / portraitDensity <= minTabMm && maxScreenPx / portraitDensity <= maxTabMm) {
                    portraitDensity
                } else {
                    maxOf(portraitDensity.coerceAtMost(maxScreenPx / minTabMm), maxScreenPx / maxTabMm)
                }.coerceAtLeast(0.1f)
                val baseBoxW = (sw / baseDensity).coerceAtLeast(1f)
                val baseBoxH = (sh / baseDensity).coerceAtLeast(1f)

                val dpiScaleX = (240f / safeDpiX).coerceIn(0.16f, 1.0f)
                val dpiScaleY = (240f / safeDpiY).coerceIn(0.16f, 1.0f)
                var desiredW = (baseBoxW * dpiScaleX).coerceAtLeast(1f)
                var desiredH = (baseBoxH * dpiScaleY).coerceAtLeast(1f)

                val fitScale = minOf(effTabW / desiredW, effTabH / desiredH, 1f)
                desiredW *= fitScale
                desiredH *= fitScale

                val newAw = if (!rotatedIsSideways) (desiredW / fullTabW).coerceIn(minOf(0.08f, aw), aw) else (desiredH / fullTabW).coerceIn(minOf(0.08f, aw), aw)
                val newAh = if (!rotatedIsSideways) (desiredH / fullTabH).coerceIn(minOf(0.08f, ah), ah) else (desiredW / fullTabH).coerceIn(minOf(0.08f, ah), ah)
                ax += (aw - newAw) * 0.5f
                ay += (ah - newAh) * 0.5f
                aw = newAw
                ah = newAh
            }

            lockAspectRatio -> {
                // Simple 1:1 aspect ratio crop inside activeArea without DPI overrides
                val screenAspect = (sw / sh.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
                val tabletAspect = (effTabW / effTabH.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
                if (kotlin.math.abs(tabletAspect - screenAspect) > 0.04f) {
                    if (tabletAspect > screenAspect) {
                        val targetRatio = screenAspect / tabletAspect
                        if (!rotatedIsSideways) {
                            val newAw = (aw * targetRatio).coerceIn(minOf(0.12f, aw), aw)
                            ax += (aw - newAw) * 0.5f
                            aw = newAw
                        } else {
                            val newAh = (ah * targetRatio).coerceIn(minOf(0.12f, ah), ah)
                            ay += (ah - newAh) * 0.5f
                            ah = newAh
                        }
                    } else {
                        val targetRatio = tabletAspect / screenAspect
                        if (!rotatedIsSideways) {
                            val newAh = (ah * targetRatio).coerceIn(minOf(0.12f, ah), ah)
                            ay += (ah - newAh) * 0.5f
                            ah = newAh
                        } else {
                            val newAw = (aw * targetRatio).coerceIn(minOf(0.12f, aw), aw)
                            ax += (aw - newAw) * 0.5f
                            aw = newAw
                        }
                    }
                }
            }
        }

        val finalMmW = fullTabW * aw
        val finalMmH = fullTabH * ah
        val mappedMmX = if (rotatedIsSideways) finalMmH else finalMmW
        val mappedMmY = if (rotatedIsSideways) finalMmW else finalMmH
        activeWidthMm = mappedMmX
        activeHeightMm = mappedMmY
        effectivePxPerMmX = sw / mappedMmX.coerceAtLeast(1f)
        effectivePxPerMmY = sh / mappedMmY.coerceAtLeast(1f)

        // Normalized -> active-area local [0..1]: u = (nx - ax) / aw, v = (ny - ay) / ah
        val scaleU = 1f / aw
        val offU = -ax / aw
        val scaleV = 1f / ah
        val offV = -ay / ah

        var a00 = scaleU
        var a01 = 0f
        var a02 = offU
        var a10 = 0f
        var a11 = scaleV
        var a12 = offV

        if (invertX) {
            a00 = -a00
            a01 = -a01
            a02 = 1f - a02
        }
        if (invertY) {
            a10 = -a10
            a11 = -a11
            a12 = 1f - a12
        }

        val r00: Float
        val r01: Float
        val r02: Float
        val r10: Float
        val r11: Float
        val r12: Float
        when (totalRot) {
            90 -> {
                r00 = -a10; r01 = -a11; r02 = 1f - a12
                r10 = a00;  r11 = a01;  r12 = a02
            }
            180 -> {
                r00 = -a00; r01 = -a01; r02 = 1f - a02
                r10 = -a10; r11 = -a11; r12 = 1f - a12
            }
            270 -> {
                r00 = a10;  r01 = a11;  r02 = a12
                r10 = -a00; r11 = -a01; r12 = 1f - a02
            }
            else -> {
                r00 = a00; r01 = a01; r02 = a02
                r10 = a10; r11 = a11; r12 = a12
            }
        }

        outMinX = screenX
        outMaxX = screenX + sw
        outMinY = screenY
        outMaxY = screenY + sh

        m00 = r00 * sw
        m01 = r01 * sw
        m02 = r02 * sw + screenX
        m10 = r10 * sh
        m11 = r11 * sh
        m12 = r12 * sh + screenY
    }

    /**
     * Sub-pixel coordinate transform with zero allocations.
     * Writes [x, y] into [outXY] (length >= 2).
     */
    fun transform(rawX: Float, rawY: Float, outXY: FloatArray) {
        val nx = rawX * invMaxX
        val ny = rawY * invMaxY
        val px = m00 * nx + m01 * ny + m02
        val py = m10 * nx + m11 * ny + m12
        outXY[0] = px.coerceIn(outMinX, outMaxX)
        outXY[1] = py.coerceIn(outMinY, outMaxY)
    }

    /**
     * Sub-pixel transform from normalized [0..1] tablet coordinates (e.g. getevent global stream).
     */
    fun transformNormalized(nx: Float, ny: Float, outXY: FloatArray) {
        val px = m00 * nx + m01 * ny + m02
        val py = m10 * nx + m11 * ny + m12
        outXY[0] = px.coerceIn(outMinX, outMaxX)
        outXY[1] = py.coerceIn(outMinY, outMaxY)
    }

    fun getSnapshotCoefficients(): FloatArray = floatArrayOf(m00, m01, m02, m10, m11, m12)
}
