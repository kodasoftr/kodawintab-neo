package com.example.driver

import kotlin.math.abs

/**
 * Zero-allocation HID report parser contract.
 * Implementations write directly into [out] without creating objects.
 */
interface HidReportParser {
    val name: String
    val brand: TabletBrand
    fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean
}

/**
 * Dedicated high-precision parser for Ugee S640 (and compatible Hanvon-Ugee 0x28BD digitizers).
 *
 * Packet layout (10-12 bytes, Report ID 0x02 or 0x07):
 * - Byte 0: Report ID (0x02 / 0x07)
 * - Byte 1: Status flags (0xF0 = Express Keys frame, 0xA0..0xAF = Pen in range, bit 0 = Tip, bit 1 = Barrel1, bit 2 = Barrel2, bit 5 = Eraser)
 * - Byte 2..3: X coordinate (unsigned 16-bit LE, 0..32767)
 * - Byte 4..5: Y coordinate (unsigned 16-bit LE, 0..32767)
 * - Byte 6..7: Pressure (unsigned 16-bit LE, 0..8191)
 * - Byte 8: Tilt X (signed 8-bit, -60..+60)
 * - Byte 9: Tilt Y (signed 8-bit, -60..+60)
 */
class UgeeS640Parser(
    private val maxX: Int = 32767,
    private val maxY: Int = 32767,
    private val maxPressure: Int = 8191
) : HidReportParser {
    override val name: String = "Ugee S640 Dedicado"
    override val brand: TabletBrand = TabletBrand.UGEE

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 8) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x02 && reportId != 0x07 && reportId != 0x01) return false

        val status = packet[1].toInt() and 0xFF

        // Express key report frame (0xF0)
        if (status == 0xF0 && length >= 5) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
            return true
        }

        // Pen out of proximity is 0xC0 or 0x00 with zero coords; inside magnetic field keeps coords active
        val rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        val rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val rawP = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        val explicitlyOutOfRange = status == 0xC0 || (status == 0x00 && rawX == 0 && rawY == 0 && rawP == 0)
        val inRange = !explicitlyOutOfRange && (
            ((status and 0x80) != 0 && (status and 0x40) == 0) ||
                rawP > 0 ||
                (rawX > 0 || rawY > 0)
            )

        out.valid = true
        out.maxX = maxX
        out.maxY = maxY
        out.maxPressure = maxPressure
        out.rawX = rawX.coerceIn(0, maxX)
        out.rawY = rawY.coerceIn(0, maxY)
        out.rawPressure = rawP.coerceIn(0, maxPressure)
        out.isHovering = inRange
        out.isTipDown = inRange && ((status and 0x01) != 0 || rawP > 0)
        out.barrelButton1 = inRange && (status and 0x02) != 0
        out.barrelButton2 = inRange && (status and 0x04) != 0
        out.isEraser = inRange && (status and 0x20) != 0
        out.expressKeyMask = 0

        if (length >= 10) {
            out.tiltXDeg = packet[8].toInt().toFloat().coerceIn(-60f, 60f)
            out.tiltYDeg = packet[9].toInt().toFloat().coerceIn(-60f, 60f)
        } else {
            out.tiltXDeg = 0f
            out.tiltYDeg = 0f
        }
        return true
    }
}

/**
 * Parser for Wacom Intuos / One (CTL-472 / CTL-4100 / CTH series, VID 0x056A).
 */
class WacomParser(
    private val maxX: Int = 15200,
    private val maxY: Int = 9500,
    private val maxPressure: Int = 4095
) : HidReportParser {
    override val name: String = "Wacom Intuos / One"
    override val brand: TabletBrand = TabletBrand.WACOM

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 8) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x02 && reportId != 0x10 && reportId != 0x11) return false

        val status = packet[1].toInt() and 0xFF
        // Express keys on report 0x11
        if (reportId == 0x11 && length >= 4) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = packet[2].toInt() and 0xFF
            return true
        }

        val rawX: Int
        val rawY: Int
        val rawP: Int

        if (reportId == 0x10 && length >= 10) {
            // 24-bit extended coordinates on newer Intuos
            rawX = (packet[2].toInt() and 0xFF) or
                ((packet[3].toInt() and 0xFF) shl 8) or
                ((packet[4].toInt() and 0xFF) shl 16)
            rawY = (packet[5].toInt() and 0xFF) or
                ((packet[6].toInt() and 0xFF) shl 8) or
                ((packet[7].toInt() and 0xFF) shl 16)
            rawP = (packet[8].toInt() and 0xFF) or ((packet[9].toInt() and 0xFF) shl 8)
        } else {
            rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
            rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
            rawP = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        }
        val inRange = (status and 0x20) != 0 || (status and 0x80) != 0 || rawP > 0 || (rawX > 0 || rawY > 0)

        out.valid = true
        out.maxX = maxX
        out.maxY = maxY
        out.maxPressure = maxPressure
        out.rawX = rawX.coerceIn(0, maxX)
        out.rawY = rawY.coerceIn(0, maxY)
        out.rawPressure = rawP.coerceIn(0, maxPressure)
        out.isHovering = inRange
        out.isTipDown = inRange && ((status and 0x01) != 0 || rawP > 0)
        out.barrelButton1 = inRange && (status and 0x02) != 0
        out.barrelButton2 = inRange && (status and 0x04) != 0
        out.isEraser = inRange && (status and 0x08) != 0
        out.tiltXDeg = 0f
        out.tiltYDeg = 0f
        out.expressKeyMask = 0
        return true
    }
}

/**
 * Parser for Huion / UC-Logic tablets (VID 0x256C).
 */
class HuionParser(
    private val maxX: Int = 32000,
    private val maxY: Int = 20000,
    private val maxPressure: Int = 8191
) : HidReportParser {
    override val name: String = "Huion UC-Logic"
    override val brand: TabletBrand = TabletBrand.HUION

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 8) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x08 && reportId != 0x07 && reportId != 0x0A) return false

        val status = packet[1].toInt() and 0xFF
        if ((status and 0xE0) == 0xE0 && length >= 6) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
            return true
        }

        val inRange = (status and 0x80) != 0
        var rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        var rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val rawP = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)

        if (length >= 10) {
            rawX = rawX or ((packet[8].toInt() and 0xFF) shl 16)
            rawY = rawY or ((packet[9].toInt() and 0xFF) shl 16)
        }

        out.valid = true
        out.maxX = maxX
        out.maxY = maxY
        out.maxPressure = maxPressure
        out.rawX = rawX.coerceIn(0, maxX)
        out.rawY = rawY.coerceIn(0, maxY)
        out.rawPressure = rawP.coerceIn(0, maxPressure)
        out.isHovering = inRange
        out.isTipDown = inRange && ((status and 0x01) != 0 || rawP > 0)
        out.barrelButton1 = inRange && (status and 0x02) != 0
        out.barrelButton2 = inRange && (status and 0x04) != 0
        out.isEraser = false
        out.expressKeyMask = 0

        if (length >= 12) {
            out.tiltXDeg = packet[10].toInt().toFloat().coerceIn(-60f, 60f)
            out.tiltYDeg = packet[11].toInt().toFloat().coerceIn(-60f, 60f)
        } else {
            out.tiltXDeg = 0f
            out.tiltYDeg = 0f
        }
        return true
    }
}

/**
 * Parser for XP-Pen Star / Deco series (VID 0x28BD).
 */
class XpPenParser(
    maxX: Int = 32767,
    maxY: Int = 32767,
    maxPressure: Int = 8191
) : HidReportParser {
    private val delegate = UgeeS640Parser(maxX, maxY, maxPressure)
    override val name: String = "XP-Pen Star / Deco"
    override val brand: TabletBrand = TabletBrand.XP_PEN

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean =
        delegate.parse(packet, length, out)
}

/**
 * Discovered bit-field layout extracted from raw USB HID Report Descriptor (getRawDescriptors()).
 */
data class HidDigitizerLayout(
    val xByteOffset: Int = 2,
    val yByteOffset: Int = 4,
    val pressureByteOffset: Int = 6,
    val logicalMaxX: Int = 32767,
    val logicalMaxY: Int = 32767,
    val logicalMaxPressure: Int = 8191,
    val hasDigitizerUsagePage: Boolean = false
)

object HidDescriptorDecoder {
    /**
     * Scans raw USB descriptors for HID Report Descriptor (0x22) and Digitizer Usage Page (0x0D)
     * Logical Maximum items to automatically configure unlisted tablets.
     */
    fun decode(rawDescriptors: ByteArray?): HidDigitizerLayout {
        if (rawDescriptors == null || rawDescriptors.isEmpty()) return HidDigitizerLayout()
        var hasDigitizer = false
        var detectedMax = 32767
        var i = 0
        while (i < rawDescriptors.size - 2) {
            val b = rawDescriptors[i].toInt() and 0xFF
            // Usage Page (Global, 0x05) -> 0x0D (Digitizer)
            if (b == 0x05 && (rawDescriptors[i + 1].toInt() and 0xFF) == 0x0D) {
                hasDigitizer = true
            }
            // Logical Maximum 16-bit (0x26 low high)
            if (b == 0x26 && i + 2 < rawDescriptors.size) {
                val v = (rawDescriptors[i + 1].toInt() and 0xFF) or
                    ((rawDescriptors[i + 2].toInt() and 0xFF) shl 8)
                if (v in 1024..65535) {
                    detectedMax = v
                }
            }
            i++
        }
        return HidDigitizerLayout(
            logicalMaxX = detectedMax,
            logicalMaxY = detectedMax,
            logicalMaxPressure = if (detectedMax >= 8191) 8191 else 4095,
            hasDigitizerUsagePage = hasDigitizer
        )
    }
}

/**
 * Generic HID Digitizer fallback parser for non-catalogued tablets + 4-corner calibration support.
 */
class GenericHidDigitizerParser(
    private val layout: HidDigitizerLayout = HidDigitizerLayout()
) : HidReportParser {
    override val name: String = "HID Digitizer Genérico"
    override val brand: TabletBrand = TabletBrand.GENERIC

    private var calMinX = 0
    private var calMaxX = layout.logicalMaxX
    private var calMinY = 0
    private var calMaxY = layout.logicalMaxY

    fun applyFourCornerCalibration(minX: Int, maxX: Int, minY: Int, maxY: Int) {
        if (abs(maxX - minX) > 100 && abs(maxY - minY) > 100) {
            calMinX = minOf(minX, maxX)
            calMaxX = maxOf(minX, maxX)
            calMinY = minOf(minY, maxY)
            calMaxY = maxOf(minY, maxY)
        }
    }

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 8) return false
        val status = packet[1].toInt() and 0xFF
        val rawX = (packet[layout.xByteOffset].toInt() and 0xFF) or
            ((packet[layout.xByteOffset + 1].toInt() and 0xFF) shl 8)
        val rawY = (packet[layout.yByteOffset].toInt() and 0xFF) or
            ((packet[layout.yByteOffset + 1].toInt() and 0xFF) shl 8)
        val rawP = (packet[layout.pressureByteOffset].toInt() and 0xFF) or
            ((packet[layout.pressureByteOffset + 1].toInt() and 0xFF) shl 8)

        val spanX = (calMaxX - calMinX).coerceAtLeast(1)
        val spanY = (calMaxY - calMinY).coerceAtLeast(1)

        out.valid = true
        out.maxX = spanX
        out.maxY = spanY
        out.maxPressure = layout.logicalMaxPressure
        out.rawX = (rawX - calMinX).coerceIn(0, spanX)
        out.rawY = (rawY - calMinY).coerceIn(0, spanY)
        out.rawPressure = rawP.coerceIn(0, layout.logicalMaxPressure)
        out.isHovering = (status and 0x80) != 0 || rawP > 0 || rawX > 0
        out.isTipDown = (status and 0x01) != 0 || rawP > 0
        out.barrelButton1 = (status and 0x02) != 0
        out.barrelButton2 = (status and 0x04) != 0
        out.isEraser = false
        out.tiltXDeg = 0f
        out.tiltYDeg = 0f
        out.expressKeyMask = 0
        return true
    }
}
