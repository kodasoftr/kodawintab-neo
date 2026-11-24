package com.example.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.PointerIcon
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DecodedCursor(
    val fileName: String,
    val bitmap: Bitmap,
    val hotspotX: Float,
    val hotspotY: Float,
    val pointerIcon: PointerIcon?,
    val sourceFormat: String,
    val frames: List<Bitmap> = listOf(bitmap),
    val frameDurationMs: Long = 80L
) {
    fun withCustomHotspot(newHotspotX: Float, newHotspotY: Float): DecodedCursor {
        val hx = newHotspotX.coerceIn(0f, (bitmap.width - 1).coerceAtLeast(0).toFloat())
        val hy = newHotspotY.coerceIn(0f, (bitmap.height - 1).coerceAtLeast(0).toFloat())
        return copy(
            hotspotX = hx,
            hotspotY = hy,
            pointerIcon = runCatching { PointerIcon.create(bitmap, hx, hy) }.getOrNull()
        )
    }
}

/**
 * Decodes .png, Windows .cur (32/24/8/4/1bpp + PNG frames with exact hotspot), and animated
 * .ani (RIFF ACON multi-frame cursors with anih rate) selected via SAF without storage permissions,
 * and builds a crisp high-contrast default Koda arrow cursor so overlays work immediately out of the box.
 */
object CursorDecoder {

    fun createDefaultKodaCursor(): DecodedCursor {
        val size = 56
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val arrowPath = Path().apply {
            moveTo(4f, 4f)
            lineTo(4f, 44f)
            lineTo(15f, 33f)
            lineTo(23f, 50f)
            lineTo(30f, 46f)
            lineTo(22f, 29f)
            lineTo(38f, 29f)
            close()
        }

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#0B0F1A")
        }
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#00E5FF")
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        canvas.drawPath(arrowPath, outlinePaint)
        canvas.drawPath(arrowPath, glowPaint)
        canvas.drawPath(arrowPath, fillPaint)

        val hx = 4f
        val hy = 4f
        return DecodedCursor(
            fileName = "Padrão Koda (Seta)",
            bitmap = bmp,
            hotspotX = hx,
            hotspotY = hy,
            pointerIcon = runCatching { PointerIcon.create(bmp, hx, hy) }.getOrNull(),
            sourceFormat = "KODA",
            frames = listOf(bmp),
            frameDurationMs = 80L
        )
    }

    fun decodeFromUri(context: Context, uri: Uri, displayName: String): DecodedCursor? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return decodeBytes(bytes, displayName)
    }

    fun decodeFromUriWithBytes(context: Context, uri: Uri, displayName: String): Pair<DecodedCursor, ByteArray>? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val decoded = decodeBytes(bytes, displayName) ?: return null
        return decoded to bytes
    }

    fun decodeBytes(bytes: ByteArray, fileName: String): DecodedCursor? {
        if (bytes.size < 12) return null

        // 1. Direct PNG
        if (isPng(bytes)) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = scaleCursorBitmapAspectPreserving(bmp)
            val (hx, hy) = detectAutoTipHotspot(scaled)
            return DecodedCursor(
                fileName = fileName,
                bitmap = scaled,
                hotspotX = hx,
                hotspotY = hy,
                pointerIcon = runCatching { PointerIcon.create(scaled, hx, hy) }.getOrNull(),
                sourceFormat = "PNG",
                frames = listOf(scaled),
                frameDurationMs = 80L
            )
        }

        // 2. RIFF .ani container -> extract all animation frames + rate
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            val aniResult = decodeAniContainer(bytes, fileName)
            if (aniResult != null) return aniResult
        }

        // 3. Windows .cur container
        val cur = decodeCurContainer(bytes, fileName, "CUR")
        if (cur != null) return cur

        // 4. Generic BitmapFactory fallback
        val fallbackBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = scaleCursorBitmapAspectPreserving(fallbackBmp)
        val (hx, hy) = detectAutoTipHotspot(scaled)
        return DecodedCursor(
            fileName = fileName,
            bitmap = scaled,
            hotspotX = hx,
            hotspotY = hy,
            pointerIcon = runCatching { PointerIcon.create(scaled, hx, hy) }.getOrNull(),
            sourceFormat = "IMG",
            frames = listOf(scaled),
            frameDurationMs = 80L
        )
    }

    private fun decodeAniContainer(bytes: ByteArray, fileName: String): DecodedCursor? {
        val iconChunks = ArrayList<ByteArray>()
        var jifRate = 5 // default 5/60s = ~83ms per frame

        fun walkRiff(start: Int, end: Int) {
            var idx = start
            while (idx + 8 <= end) {
                val tag = String(bytes, idx, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(bytes, idx + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (chunkSize < 0 || idx + 8 + chunkSize > end) break
                val dataStart = idx + 8
                val dataEnd = dataStart + chunkSize
                when (tag) {
                    "LIST" -> {
                        if (dataStart + 4 <= dataEnd) {
                            walkRiff(dataStart + 4, dataEnd)
                        }
                    }
                    "anih" -> {
                        if (chunkSize >= 32) {
                            val bb = ByteBuffer.wrap(bytes, dataStart, chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                            val rate = bb.getInt(28)
                            if (rate in 1..120) jifRate = rate
                        }
                    }
                    "icon" -> {
                        if (chunkSize >= 22) {
                            iconChunks.add(bytes.copyOfRange(dataStart, dataEnd))
                        }
                    }
                }
                idx += 8 + chunkSize + (chunkSize and 1)
            }
        }

        walkRiff(12, bytes.size)
        if (iconChunks.isEmpty()) return null

        val decodedFrames = iconChunks.mapNotNull { chunk ->
            decodeCurContainer(chunk, fileName, "ANI")
        }
        if (decodedFrames.isEmpty()) return null

        val primary = decodedFrames.first()
        val durationMs = ((jifRate * 1000L) / 60L).coerceIn(30L, 1000L)
        return primary.copy(
            sourceFormat = if (decodedFrames.size > 1) "ANI (${decodedFrames.size}f)" else "ANI",
            frames = decodedFrames.map { it.bitmap },
            frameDurationMs = durationMs
        )
    }

    private fun decodeCurContainer(bytes: ByteArray, fileName: String, formatLabel: String): DecodedCursor? {
        if (bytes.size < 22) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val reserved = bb.getShort(0).toInt() and 0xFFFF
        val type = bb.getShort(2).toInt() and 0xFFFF
        val count = bb.getShort(4).toInt() and 0xFFFF
        if (reserved != 0 || (type != 1 && type != 2) || count <= 0) return null

        // Pick best entry (prefer 32..64px with highest bpp if multiple entries exist)
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until count) {
            val entryOff = 6 + i * 16
            if (entryOff + 16 > bytes.size) break
            val w = (bytes[entryOff].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val bppOrHotY = bb.getShort(entryOff + 6).toInt() and 0xFFFF
            val size = bb.getInt(entryOff + 8)
            val off = bb.getInt(entryOff + 12)
            if (off < 0 || size <= 0 || off + size > bytes.size) continue
            val score = (if (w in 32..64) 100 else 0) + (bppOrHotY.coerceAtMost(32))
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        val entryOff = 6 + bestIdx * 16
        if (entryOff + 16 > bytes.size) return null
        val rawW = bytes[entryOff].toInt() and 0xFF
        val rawH = bytes[entryOff + 1].toInt() and 0xFF
        val w = if (rawW == 0) 256 else rawW
        val h = if (rawH == 0) 256 else rawH
        val hotspotX = (bb.getShort(entryOff + 4).toInt() and 0xFFFF).toFloat().coerceIn(0f, (w - 1).toFloat())
        val hotspotY = (bb.getShort(entryOff + 6).toInt() and 0xFFFF).toFloat().coerceIn(0f, (h - 1).toFloat())
        val sizeInBytes = bb.getInt(entryOff + 8)
        val offset = bb.getInt(entryOff + 12)

        if (offset < 0 || sizeInBytes <= 0 || offset + sizeInBytes > bytes.size) return null
        val payload = bytes.copyOfRange(offset, offset + sizeInBytes)

        val decodedBmp = if (isPng(payload)) {
            BitmapFactory.decodeByteArray(payload, 0, payload.size)
        } else {
            decodeDibBmpPayload(payload, w, h)
        } ?: return null

        val scaled = scaleCursorBitmapAspectPreserving(decodedBmp)
        val scaleFactorX = scaled.width.toFloat() / decodedBmp.width.coerceAtLeast(1)
        val scaleFactorY = scaled.height.toFloat() / decodedBmp.height.coerceAtLeast(1)
        val hx = (hotspotX * scaleFactorX).coerceIn(0f, (scaled.width - 1).coerceAtLeast(0).toFloat())
        val hy = (hotspotY * scaleFactorY).coerceIn(0f, (scaled.height - 1).coerceAtLeast(0).toFloat())

        return DecodedCursor(
            fileName = fileName,
            bitmap = scaled,
            hotspotX = hx,
            hotspotY = hy,
            pointerIcon = runCatching { PointerIcon.create(scaled, hx, hy) }.getOrNull(),
            sourceFormat = formatLabel,
            frames = listOf(scaled),
            frameDurationMs = 80L
        )
    }

    private fun decodeDibBmpPayload(payload: ByteArray, expectedW: Int, expectedH: Int): Bitmap? {
        if (payload.size < 40) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = bb.getInt(0)
        if (headerSize < 40) return null
        val width = bb.getInt(4).takeIf { it > 0 } ?: expectedW
        val totalHeight = bb.getInt(8)
        val height = (totalHeight / 2).takeIf { it > 0 } ?: expectedH
        val bpp = bb.getShort(14).toInt() and 0xFFFF
        val colorsUsed = bb.getInt(32).let { raw ->
            when {
                bpp >= 24 -> 0
                raw > 0 -> raw
                else -> 1 shl bpp
            }
        }

        val paletteOffset = headerSize
        val pixelDataOffset = paletteOffset + colorsUsed * 4
        if (pixelDataOffset > payload.size) return null

        val xorRowBytes = ((width * bpp + 31) / 32) * 4
        val andRowBytes = ((width + 31) / 32) * 4
        val andMaskOffset = pixelDataOffset + xorRowBytes * height

        val pixels = IntArray(width * height)

        for (yIdx in 0 until height) {
            val dstY = height - 1 - yIdx // DIB rows are bottom-up
            val xorRowStart = pixelDataOffset + yIdx * xorRowBytes
            if (xorRowStart + xorRowBytes > payload.size && bpp != 32) return null

            for (x in 0 until width) {
                var a = 255
                var r = 0
                var g = 0
                var b = 0

                when (bpp) {
                    32 -> {
                        val p = xorRowStart + x * 4
                        if (p + 3 >= payload.size) return null
                        b = payload[p].toInt() and 0xFF
                        g = payload[p + 1].toInt() and 0xFF
                        r = payload[p + 2].toInt() and 0xFF
                        a = payload[p + 3].toInt() and 0xFF
                    }
                    24 -> {
                        val p = xorRowStart + x * 3
                        if (p + 2 >= payload.size) return null
                        b = payload[p].toInt() and 0xFF
                        g = payload[p + 1].toInt() and 0xFF
                        r = payload[p + 2].toInt() and 0xFF
                    }
                    8 -> {
                        val p = xorRowStart + x
                        val palIdx = payload[p].toInt() and 0xFF
                        val cOff = paletteOffset + (palIdx % colorsUsed.coerceAtLeast(1)) * 4
                        if (cOff + 2 < payload.size) {
                            b = payload[cOff].toInt() and 0xFF
                            g = payload[cOff + 1].toInt() and 0xFF
                            r = payload[cOff + 2].toInt() and 0xFF
                        }
                    }
                    4 -> {
                        val byteVal = payload[xorRowStart + (x / 2)].toInt() and 0xFF
                        val palIdx = if (x % 2 == 0) (byteVal ushr 4) and 0x0F else byteVal and 0x0F
                        val cOff = paletteOffset + (palIdx % colorsUsed.coerceAtLeast(1)) * 4
                        if (cOff + 2 < payload.size) {
                            b = payload[cOff].toInt() and 0xFF
                            g = payload[cOff + 1].toInt() and 0xFF
                            r = payload[cOff + 2].toInt() and 0xFF
                        }
                    }
                    1 -> {
                        val byteVal = payload[xorRowStart + (x / 8)].toInt() and 0xFF
                        val bit = (byteVal ushr (7 - (x % 8))) and 0x01
                        val cOff = paletteOffset + (bit % colorsUsed.coerceAtLeast(1)) * 4
                        if (cOff + 2 < payload.size) {
                            b = payload[cOff].toInt() and 0xFF
                            g = payload[cOff + 1].toInt() and 0xFF
                            r = payload[cOff + 2].toInt() and 0xFF
                        } else {
                            val v = if (bit == 0) 0 else 255
                            r = v; g = v; b = v
                        }
                    }
                    else -> return null
                }

                // Apply 1bpp AND transparency mask for <=24bpp icons (or if 32bpp alpha is completely 0 with mask)
                if (bpp <= 24 && andMaskOffset + yIdx * andRowBytes + (x / 8) < payload.size) {
                    val maskByte = payload[andMaskOffset + yIdx * andRowBytes + (x / 8)].toInt() and 0xFF
                    val maskBit = (maskByte ushr (7 - (x % 8))) and 0x01
                    if (maskBit == 1) {
                        a = 0
                    }
                }

                pixels[dstY * width + x] = Color.argb(a, r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * For PNG cursors (which lack an ICONDIR hotspot header), locates the top-most opaque pixel
     * in the upper-left tip quadrant so arrow cursors align accurately at pixel 0,0 / tip corner.
     */
    private fun detectAutoTipHotspot(bmp: Bitmap): Pair<Float, Float> {
        val maxScanY = (bmp.height / 2).coerceAtLeast(1)
        val maxScanX = (bmp.width / 2).coerceAtLeast(1)
        for (y in 0 until maxScanY) {
            for (x in 0 until maxScanX) {
                val alpha = (bmp.getPixel(x, y) ushr 24) and 0xFF
                if (alpha > 40) {
                    return x.toFloat() to y.toFloat()
                }
            }
        }
        return 0f to 0f
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    private fun scaleCursorBitmapAspectPreserving(bmp: Bitmap): Bitmap {
        val maxEdge = 84
        if (bmp.width <= maxEdge && bmp.height <= maxEdge) return bmp
        val ratio = minOf(maxEdge.toFloat() / bmp.width, maxEdge.toFloat() / bmp.height)
        val targetW = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
    }
}
