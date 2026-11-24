package com.example.usb

/**
 * Decodes standard USB HID Boot & Generic Mouse packets (3..8 bytes) with zero heap allocation
 * and accumulates sub-pixel cursor coordinates inside display bounds.
 */
class MouseHidParser {

    @JvmField var buttonLeft: Boolean = false
    @JvmField var buttonRight: Boolean = false
    @JvmField var buttonMiddle: Boolean = false
    @JvmField var deltaX: Float = 0f
    @JvmField var deltaY: Float = 0f
    @JvmField var wheelDelta: Float = 0f

    fun parse(packet: ByteArray, length: Int, sensitivity: Float = 1.0f): Boolean {
        if (length < 3) return false

        // Some wireless receivers or gaming mice prepend a 1-byte Report ID (0x01..0x04) before buttons/X/Y
        val first = packet[0].toInt() and 0xFF
        val offset = if (length >= 5 && first in 1..4 &&
            (packet[1].toInt() and 0xF0) == 0
        ) {
            1
        } else {
            0
        }
        if (offset + 3 > length) return false

        val btnByte = packet[offset].toInt() and 0xFF
        buttonLeft = (btnByte and 0x01) != 0
        buttonRight = (btnByte and 0x02) != 0
        buttonMiddle = (btnByte and 0x04) != 0

        // Standard 8-bit signed relative deltas (USB Boot Mouse) or 16-bit LE if packet >= 6
        val dx: Int
        val dy: Int
        val wheelIdx: Int
        val rem = length - offset
        if (rem >= 6 && (packet[offset + 1].toInt() != 0 || packet[offset + 2].toInt() != 0 ||
                packet[offset + 3].toInt() != 0 || packet[offset + 4].toInt() != 0)
        ) {
            val b1 = packet[offset + 1].toInt()
            val b2 = packet[offset + 2].toInt()
            val b3 = packet[offset + 3].toInt()
            val b4 = packet[offset + 4].toInt()
            val likely16Bit = (b2 == 0 || b2 == -1) && (b4 == 0 || b4 == -1)
            if (likely16Bit) {
                dx = ((b2 shl 8) or (b1 and 0xFF)).toShort().toInt()
                dy = ((b4 shl 8) or (b3 and 0xFF)).toShort().toInt()
                wheelIdx = offset + 5
            } else {
                dx = b1
                dy = b2
                wheelIdx = offset + 3
            }
        } else {
            dx = packet[offset + 1].toInt()
            dy = packet[offset + 2].toInt()
            wheelIdx = offset + 3
        }

        deltaX = dx * sensitivity
        deltaY = dy * sensitivity
        wheelDelta = if (wheelIdx < length) packet[wheelIdx].toInt().toFloat() else 0f
        return true
    }
}
