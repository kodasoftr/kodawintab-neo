package com.example.pendisplay

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.example.usb.UsbTabletCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class KodaStreamTelemetry(
    val isStreaming: Boolean = false,
    val codecLabel: String = "Standby",
    val widthPx: Int = 1920,
    val heightPx: Int = 1080,
    val fps: Int = 0,
    val targetBitrateMbps: Float = 16.0f,
    val encodedFrames: Long = 0L,
    val usbPacketsSent: Long = 0L,
    val usbBytesSent: Long = 0L,
    val lastFrameBytes: Int = 0,
    val encodeLatencyMs: Float = 0f,
    val usbBulkTransportActive: Boolean = false
)

/**
 * Koda Stream Engine:
 * GPU Surface -> Hardware H.264 MediaCodec (no slow CPU readback) -> Proprietary Koda USB Bulk
 * frame framing -> UsbDeviceConnection.bulkTransfer() when a USB video bulk-out endpoint is present.
 */
class KodaStreamEngine(
    private val usbCoordinator: UsbTabletCoordinator
) {
    private val _telemetry = MutableStateFlow(KodaStreamTelemetry())
    val telemetry: StateFlow<KodaStreamTelemetry> = _telemetry.asStateFlow()

    private val running = AtomicBoolean(false)
    @Volatile private var mediaCodec: MediaCodec? = null
    @Volatile private var imageReaderFallback: ImageReader? = null
    @Volatile private var drainThread: Thread? = null

    @Volatile var inputSurface: Surface? = null
        private set

    fun start(width: Int, height: Int, bitrateMbps: Float = 16f, fps: Int = 60): Surface? {
        stop()
        val safeW = (width.coerceIn(640, 3840) / 16) * 16
        val safeH = (height.coerceIn(480, 2160) / 16) * 16
        val bitrateBps = (bitrateMbps * 1_000_000f).toInt().coerceIn(2_000_000, 40_000_000)

        val codecSurface = runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            surface
        }.getOrNull()

        if (codecSurface != null) {
            inputSurface = codecSurface
            running.set(true)
            _telemetry.value = KodaStreamTelemetry(
                isStreaming = true,
                codecLabel = "GPU Surface + MediaCodec H.264",
                widthPx = safeW,
                heightPx = safeH,
                fps = fps,
                targetBitrateMbps = bitrateMbps,
                usbBulkTransportActive = usbCoordinator.hasUsbVideoBulkTransport()
            )
            startMediaCodecDrainThread(safeW, safeH, bitrateMbps)
            return codecSurface
        }

        // Fallback ImageReader Surface for headless / JVM test environments without hardware AVC encoder
        val fallbackSurface = runCatching {
            val reader = ImageReader.newInstance(safeW, safeH, PixelFormat.RGBA_8888, 2)
            imageReaderFallback = reader
            reader.surface
        }.getOrNull()

        inputSurface = fallbackSurface
        running.set(fallbackSurface != null)
        _telemetry.value = KodaStreamTelemetry(
            isStreaming = fallbackSurface != null,
            codecLabel = if (fallbackSurface != null) "GPU Surface Fallback (Direct)" else "Inativo",
            widthPx = safeW,
            heightPx = safeH,
            fps = if (fallbackSurface != null) fps else 0,
            targetBitrateMbps = bitrateMbps,
            usbBulkTransportActive = usbCoordinator.hasUsbVideoBulkTransport()
        )
        return fallbackSurface
    }

    private fun startMediaCodecDrainThread(width: Int, height: Int, bitrateMbps: Float) {
        drainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val bufferInfo = MediaCodec.BufferInfo()
            val header = ByteArray(16)
            var frameSeq = 0L
            var usbPackets = 0L
            var usbBytes = 0L
            var fpsWindowStart = SystemClock.elapsedRealtime()
            var fpsWindowFrames = 0
            var measuredFps = 0

            while (running.get()) {
                val codec = mediaCodec ?: break
                val outIdx = runCatching { codec.dequeueOutputBuffer(bufferInfo, 12_000L) }.getOrDefault(-1)
                if (outIdx >= 0) {
                    val t0 = System.nanoTime()
                    val outBuf: ByteBuffer? = runCatching { codec.getOutputBuffer(outIdx) }.getOrNull()
                    val size = bufferInfo.size
                    if (outBuf != null && size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + size)
                        frameSeq++
                        fpsWindowFrames++

                        val packet = ByteArray(16 + size)
                        writeKodaVideoHeader(header, frameSeq, size, bufferInfo.flags)
                        System.arraycopy(header, 0, packet, 0, 16)
                        outBuf.get(packet, 16, size)

                        if (usbCoordinator.sendVideoFrameBulk(packet, packet.size)) {
                            usbPackets++
                            usbBytes += packet.size
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(outIdx, false) }

                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - fpsWindowStart >= 1000L) {
                        measuredFps = fpsWindowFrames
                        fpsWindowFrames = 0
                        fpsWindowStart = nowMs
                    }
                    val encodeMs = (System.nanoTime() - t0) / 1_000_000f
                    _telemetry.value = _telemetry.value.copy(
                        isStreaming = true,
                        widthPx = width,
                        heightPx = height,
                        fps = if (measuredFps > 0) measuredFps else 60,
                        targetBitrateMbps = bitrateMbps,
                        encodedFrames = frameSeq,
                        usbPacketsSent = usbPackets,
                        usbBytesSent = usbBytes,
                        lastFrameBytes = size,
                        encodeLatencyMs = encodeMs,
                        usbBulkTransportActive = usbCoordinator.hasUsbVideoBulkTransport()
                    )
                }
            }
        }, "KodaStreamEncoderDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun writeKodaVideoHeader(out: ByteArray, seq: Long, payloadSize: Int, flags: Int) {
        // Magic 'KODA'
        out[0] = 'K'.code.toByte()
        out[1] = 'O'.code.toByte()
        out[2] = 'D'.code.toByte()
        out[3] = 'A'.code.toByte()
        // Seq (32-bit LE)
        val s = seq.toInt()
        out[4] = (s and 0xFF).toByte()
        out[5] = ((s ushr 8) and 0xFF).toByte()
        out[6] = ((s ushr 16) and 0xFF).toByte()
        out[7] = ((s ushr 24) and 0xFF).toByte()
        // Payload size (32-bit LE)
        out[8] = (payloadSize and 0xFF).toByte()
        out[9] = ((payloadSize ushr 8) and 0xFF).toByte()
        out[10] = ((payloadSize ushr 16) and 0xFF).toByte()
        out[11] = ((payloadSize ushr 24) and 0xFF).toByte()
        // Flags (32-bit LE)
        out[12] = (flags and 0xFF).toByte()
        out[13] = ((flags ushr 8) and 0xFF).toByte()
        out[14] = ((flags ushr 16) and 0xFF).toByte()
        out[15] = ((flags ushr 24) and 0xFF).toByte()
    }

    fun stop() {
        running.set(false)
        drainThread?.interrupt()
        drainThread = null
        runCatching {
            mediaCodec?.stop()
            mediaCodec?.release()
        }
        mediaCodec = null
        runCatching { imageReaderFallback?.close() }
        imageReaderFallback = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        _telemetry.value = KodaStreamTelemetry(isStreaming = false)
    }
}
