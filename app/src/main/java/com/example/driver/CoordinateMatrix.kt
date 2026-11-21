package com.example.driver

/**
 * Pre-calculated 2x3 affine transformation matrix for sub-pixel coordinate mapping.
 *
 * v2.0 adds DPI-compatible isotropic screen-to-tablet scaling (`dpiMode`: AUTO / MANUAL X-Y) so tall
 * smartphone screens (e.g. 20:9 where Y pixels >> X pixels) have identical horizontal and vertical
 * physical stroke response without clipping or reducing native tablet pressure levels.
 *
 * Hot-path transform performs only 4 multiplications, 2 additions, and 0 heap allocations.
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
        // - Vertical (displayRotationDeg == 0): autoDelta = 0 (unchanged).
        // - Horizontal when autoRotation && rotateAxesWithScreen: Android Surface.ROTATION_90 is 90 CCW,
        //   so compensating tablet axes uses (360 - displayRotationDeg) % 360 (matching CursorOverlayManager).
        // - When !autoRotation || !rotateAxesWithScreen: keeps tablet desk-aligned (manualRotationDeg)
        //   regardless of phone orientation, while adapting to rotated display width/height & DPI.
        val autoDelta = if (autoRotation && rotateAxesWithScreen) ((360 - displayRotationDeg) % 360) else 0
        val totalRot = (((manualRotationDeg + autoDelta) % 360) + 360) % 360
        effectiveRotationDeg = totalRot

        // Compute screen bounds
        val sw = (screenArea.widthNorm.coerceIn(0.05f, 1f)) * displayWidthPx
        val sh = (screenArea.heightNorm.coerceIn(0.05f, 1f)) * displayHeightPx
        val screenX = (screenArea.leftNorm.coerceIn(0f, 0.95f)) * displayWidthPx
        val screenY = (screenArea.topNorm.coerceIn(0f, 0.95f)) * displayHeightPx

        // When DPI normalization or aspect lock is active, normalize physical tablet X/Y ratio
        // against the physical screen aspect (in inches/mm via DPI X & DPI Y) so Y strokes are
        // never heavier/faster than X strokes on tall mobile screens.
        if (dpiMode != DpiNormalizationMode.OFF || lockAspectRatio) {
            val screenPhysW = sw / safeDpiX
            val screenPhysH = sh / safeDpiY
            val screenAspect = (screenPhysW / screenPhysH.coerceAtLeast(0.01f)).coerceIn(0.2f, 5f)

            val rawTabW = tabletWidthMm.coerceAtLeast(10f) * aw
            val rawTabH = tabletHeightMm.coerceAtLeast(10f) * ah
            val rotatedIsSideways = totalRot == 90 || totalRot == 270
            val effTabW = if (rotatedIsSideways) rawTabH else rawTabW
            val effTabH = if (rotatedIsSideways) rawTabW else rawTabH
            val tabletAspect = (effTabW / effTabH.coerceAtLeast(1f)).coerceIn(0.2f, 5f)

            if (dpiMode != DpiNormalizationMode.OFF && kotlin.math.abs(tabletAspect - screenAspect) > 0.04f) {
                if (tabletAspect > screenAspect) {
                    // Tablet is wider than screen: trim tablet width symmetrically so X/Y DPI density matches
                    val targetRatio = screenAspect / tabletAspect
                    val newAw = (aw * targetRatio).coerceIn(0.15f, 1f)
                    if (!rotatedIsSideways) {
                        ax += (aw - newAw) * 0.5f
                        aw = newAw
                    } else {
                        val newAh = (ah * targetRatio).coerceIn(0.15f, 1f)
                        ay += (ah - newAh) * 0.5f
                        ah = newAh
                    }
                } else {
                    // Screen is wider than tablet: trim tablet height symmetrically
                    val targetRatio = tabletAspect / screenAspect
                    if (!rotatedIsSideways) {
                        val newAh = (ah * targetRatio).coerceIn(0.15f, 1f)
                        ay += (ah - newAh) * 0.5f
                        ah = newAh
                    } else {
                        val newAw = (aw * targetRatio).coerceIn(0.15f, 1f)
                        ax += (aw - newAw) * 0.5f
                        aw = newAw
                    }
                }
            }
        }

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

    fun getSnapshotCoefficients(): FloatArray = floatArrayOf(m00, m01, m02, m10, m11, m12)
}
