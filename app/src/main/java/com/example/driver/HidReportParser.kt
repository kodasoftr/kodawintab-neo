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
        if (length < 6) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x02 && reportId != 0x07 && reportId != 0x01 &&
            reportId != 0x06 && reportId != 0x08 && reportId != 0x0A
        ) {
            return false
        }

        val status = packet[1].toInt() and 0xFF

        // Express key report frame (0xF0 or 0xF1 keypad)
        if ((status == 0xF0 || status == 0xF1) && length >= 5) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
            out.isExpressKeyFrame = true
            return true
        }

        // Pen out of proximity is 0xC0 or 0x00 with zero pressure; inside magnetic field keeps coords active
        val rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        val rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val rawP = if (length >= 8) {
            (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        } else 0
        val explicitlyOutOfRange = status == 0xC0 || (status == 0x00 && rawP == 0)
        val inRange = !explicitlyOutOfRange && (
            ((status and 0x80) != 0 && (status and 0x40) == 0) ||
                rawP > 0 ||
                (status and 0x0F) != 0
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
        // In Ugee/XP-Pen 0xA0..0xAF status frames, bit 5 (0x20) is normal pen-in-range flag, NOT eraser!
        out.isEraser = inRange && (status and 0x08) != 0
        out.expressKeyMask = 0
        out.isExpressKeyFrame = false

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
            out.isExpressKeyFrame = true
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
        val explicitlyOut = status == 0x00 && rawP == 0
        val inRange = !explicitlyOut && ((status and 0x20) != 0 || (status and 0x80) != 0 || rawP > 0 || (status and 0x0F) != 0)

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
        if (reportId == 0x10 && length >= 12) {
            out.tiltXDeg = packet[10].toInt().toFloat().coerceIn(-60f, 60f)
            out.tiltYDeg = packet[11].toInt().toFloat().coerceIn(-60f, 60f)
        } else {
            out.tiltXDeg = 0f
            out.tiltYDeg = 0f
        }
        out.expressKeyMask = 0
        out.isExpressKeyFrame = false
        return true
    }
}

/**
 * Parser for Huion / UC-Logic / Gaomon / Parblo / Bosto tablets (VID 0x256C / 0x5543 / 0x2179 / 0x21A9).
 */
class HuionParser(
    private val maxX: Int = 32000,
    private val maxY: Int = 20000,
    private val maxPressure: Int = 8191,
    private val bit5IsEraser: Boolean = true,
    override val brand: TabletBrand = TabletBrand.HUION
) : HidReportParser {
    override val name: String = if (brand == TabletBrand.UCLOGIC) "UC-Logic / Gaomon / Parblo" else "Huion UC-Logic"

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 8) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x08 && reportId != 0x07 && reportId != 0x0A && reportId != 0x0D) return false

        val status = packet[1].toInt() and 0xFF
        if ((status and 0xE0) == 0xE0 && length >= 6) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
            out.isExpressKeyFrame = true
            return true
        }

        var rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        var rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val rawP = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)

        val needsHighBytes = (maxX > 65535 || maxY > 65535 || length >= 12)
        if (length >= 10 && needsHighBytes) {
            val hx = packet[8].toInt() and 0xFF
            val hy = packet[9].toInt() and 0xFF
            if (hx <= 3 && hy <= 3) {
                rawX = rawX or (hx shl 16)
                rawY = rawY or (hy shl 16)
            }
        }
        val explicitlyOutOfRange = status == 0xC0 || (status == 0x00 && rawP == 0)
        val inRange = !explicitlyOutOfRange && (
            (status and 0x80) != 0 ||
                rawP > 0 ||
                (status != 0x00 && (rawX > 0 || rawY > 0))
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
        out.isEraser = inRange && ((bit5IsEraser && (status and 0x20) != 0) || (status and 0x08) != 0)
        out.expressKeyMask = 0
        out.isExpressKeyFrame = false

        if (length >= 12) {
            out.tiltXDeg = packet[10].toInt().toFloat().coerceIn(-60f, 60f)
            out.tiltYDeg = packet[11].toInt().toFloat().coerceIn(-60f, 60f)
        } else if (length in 10..11 && maxX <= 65535 && maxY <= 65535) {
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
 * Parser for Veikk S640 / A30 / A50 / VK series tablets (VID 0x2FEB).
 * Supports both legacy 0x40..0x47 (In-Range bit 6) and modern 0xA0..0xAF / 0xC0 reports.
 */
class VeikkParser(
    private val maxX: Int = 32767,
    private val maxY: Int = 32767,
    private val maxPressure: Int = 8191
) : HidReportParser {
    override val name: String = "Veikk S640 / A30 Dedicado"
    override val brand: TabletBrand = TabletBrand.VEIKK

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (length < 4) return false
        val reportId = packet[0].toInt() and 0xFF
        if (reportId != 0x01 && reportId != 0x02 && reportId != 0x03 &&
            reportId != 0x07 && reportId != 0x08 && reportId != 0x09
        ) {
            return false
        }

        val status = packet[1].toInt() and 0xFF
        if (reportId == 0x03 || ((status == 0xF0 || status == 0xF1 || (status and 0xE0) == 0xE0) && length >= 5)) {
            out.reset()
            out.valid = true
            out.maxX = maxX
            out.maxY = maxY
            out.maxPressure = maxPressure
            out.expressKeyMask = (packet[2].toInt() and 0xFF) or
                (if (length >= 4) (packet[3].toInt() and 0xFF) shl 8 else 0)
            out.isExpressKeyFrame = true
            return true
        }

        if (length < 6) return false
        val rawX = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        val rawY = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val rawP = if (length >= 8) {
            (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        } else 0

        val explicitlyOut = status == 0xC0 || (status == 0x00 && rawP == 0)
        val inRange = !explicitlyOut && (
            ((status and 0x80) != 0 && (status and 0x40) == 0) ||
                (status and 0x40) != 0 ||
                rawP > 0 ||
                (status and 0x0F) != 0
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
        out.isEraser = inRange && (status and 0x08) != 0
        out.expressKeyMask = 0
        out.isExpressKeyFrame = false

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
     * Scans raw USB descriptors / HID Report Descriptor (0x22) for Digitizer Usage Page (0x0D)
     * and 16-bit/32-bit Logical Maximum items to automatically configure unlisted or wide tablets.
     */
    fun decode(rawDescriptors: ByteArray?): HidDigitizerLayout {
        if (rawDescriptors == null || rawDescriptors.isEmpty()) return HidDigitizerLayout()
        var hasDigitizer = false
        var currentUsagePage = 0
        var lastUsage = 0
        var maxX = 0
        var maxY = 0
        var maxP = 0
        var fallbackMax = 32767

        var i = 0
        while (i < rawDescriptors.size) {
            val prefix = rawDescriptors[i].toInt() and 0xFF
            if (prefix == 0xFE) {
                // Long HID item
                val dataSize = if (i + 1 < rawDescriptors.size) (rawDescriptors[i + 1].toInt() and 0xFF) else 0
                i += 3 + dataSize
                continue
            }
            val sizeCode = prefix and 0x03
            val dataLen = when (sizeCode) {
                3 -> 4
                else -> sizeCode
            }
            if (i + dataLen >= rawDescriptors.size) break
            var value = 0
            for (b in 0 until dataLen) {
                value = value or ((rawDescriptors[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            }
            val typeTag = prefix and 0xFC
            when (typeTag) {
                0x04 -> { // Usage Page
                    currentUsagePage = value
                    if (value == 0x0D) hasDigitizer = true
                }
                0x08 -> { // Local Usage
                    lastUsage = value
                }
                0x24 -> { // Logical Maximum (16-bit 0x26 or 32-bit 0x27)
                    if (value in 1024..262144) {
                        fallbackMax = value
                        when {
                            (currentUsagePage == 0x01 || currentUsagePage == 0x0D) && lastUsage == 0x30 -> {
                                if (currentUsagePage == 0x0D && maxX > 0) {
                                    maxP = value
                                } else {
                                    maxX = value
                                }
                            }
                            (currentUsagePage == 0x01 || currentUsagePage == 0x0D) && lastUsage == 0x31 -> {
                                maxY = value
                            }
                        }
                    }
                }
            }
            i += 1 + dataLen
        }
        val finalX = if (maxX >= 1024) maxX else fallbackMax
        val finalY = if (maxY >= 1024) maxY else fallbackMax
        val finalP = when {
            maxP >= 1023 -> maxP
            maxOf(finalX, finalY) >= 8191 -> 8191
            else -> 4095
        }
        return HidDigitizerLayout(
            logicalMaxX = finalX,
            logicalMaxY = finalY,
            logicalMaxPressure = finalP,
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
        if (length < 6) return false
        val reportId = packet[0].toInt() and 0xFF
        val status = packet[1].toInt() and 0xFF
        // Reject auxiliary Express-Key / battery / status packets so they never drop active strokes
        if (reportId == 0x11 || reportId == 0xF0 || reportId == 0xF1 ||
            status == 0xF0 || status == 0xF1 || (status and 0xE0) == 0xE0
        ) {
            return false
        }

        val xOff = layout.xByteOffset
        val yOff = layout.yByteOffset
        val pOff = layout.pressureByteOffset
        if (yOff + 1 >= length) return false

        val rawX = (packet[xOff].toInt() and 0xFF) or
            ((packet[xOff + 1].toInt() and 0xFF) shl 8)
        val rawY = (packet[yOff].toInt() and 0xFF) or
            ((packet[yOff + 1].toInt() and 0xFF) shl 8)
        val rawP = if (pOff + 1 < length) {
            (packet[pOff].toInt() and 0xFF) or
                ((packet[pOff + 1].toInt() and 0xFF) shl 8)
        } else 0

        val spanX = (calMaxX - calMinX).coerceAtLeast(1)
        val spanY = (calMaxY - calMinY).coerceAtLeast(1)
        val explicitlyOut = status == 0xC0 || (status == 0x00 && rawP == 0)

        out.valid = true
        out.maxX = spanX
        out.maxY = spanY
        out.maxPressure = layout.logicalMaxPressure
        out.rawX = (rawX - calMinX).coerceIn(0, spanX)
        out.rawY = (rawY - calMinY).coerceIn(0, spanY)
        out.rawPressure = rawP.coerceIn(0, layout.logicalMaxPressure)
        out.isHovering = !explicitlyOut && (((status and 0x80) != 0 && (status and 0x40) == 0) || rawP > 0 || (status and 0x0F) != 0)
        out.isTipDown = out.isHovering && ((status and 0x01) != 0 || rawP > 0)
        out.barrelButton1 = out.isHovering && (status and 0x02) != 0
        out.barrelButton2 = out.isHovering && (status and 0x04) != 0
        out.isEraser = out.isHovering && (status and 0x08) != 0
        if (length >= 10) {
            out.tiltXDeg = packet[8].toInt().toFloat().coerceIn(-60f, 60f)
            out.tiltYDeg = packet[9].toInt().toFloat().coerceIn(-60f, 60f)
        } else {
            out.tiltXDeg = 0f
            out.tiltYDeg = 0f
        }
        out.expressKeyMask = 0
        out.isExpressKeyFrame = false
        return true
    }
}

/**
 * Resilient wrapper trying the primary brand/profile parser first and automatically falling back
 * to compatible digitizer parsers when a firmware revision emits alternate Report IDs or layouts.
 */
class ResilientTabletParser(
    private val primary: HidReportParser,
    private val fallbacks: List<HidReportParser>
) : HidReportParser {
    override val name: String = primary.name
    override val brand: TabletBrand = primary.brand
    @Volatile private var confirmedNonGenericStream = false

    override fun parse(packet: ByteArray, length: Int, out: TabletReport): Boolean {
        if (primary.parse(packet, length, out)) {
            if (!out.isExpressKeyFrame && primary !is GenericHidDigitizerParser) {
                confirmedNonGenericStream = true
            }
            return true
        }
        val reportId = if (length > 0) packet[0].toInt() and 0xFF else 0
        val status = if (length > 1) packet[1].toInt() and 0xFF else 0
        val isLikelyAuxiliaryFrame = length < 8 ||
            reportId == 0x03 || reportId == 0x04 || reportId == 0x05 || reportId == 0x11 ||
            status == 0xF0 || status == 0xF1 || (status and 0xE0) == 0xE0

        for (i in fallbacks.indices) {
            val fb = fallbacks[i]
            if (fb is GenericHidDigitizerParser && confirmedNonGenericStream && isLikelyAuxiliaryFrame) {
                continue
            }
            if (fb.parse(packet, length, out)) {
                if (!out.isExpressKeyFrame && fb !is GenericHidDigitizerParser) {
                    confirmedNonGenericStream = true
                }
                return true
            }
        }
        return false
    }
}
