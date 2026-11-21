package com.example.driver

/**
 * Nanosecond-accurate pipeline latency & polling rate profiler with zero allocations.
 */
class LatencyMeter {
    private var windowStartNs = 0L
    private var packetCounter = 0
    @Volatile var currentPollingRateHz: Int = 0
        private set

    private var emaParseNs = 0f
    private var emaTransformNs = 0f
    private var emaInjectNs = 0f
    private var totalPackets = 0L

    fun recordPacket(parseNs: Long, transformNs: Long, injectNs: Long, nowNs: Long) {
        totalPackets++
        packetCounter++
        if (windowStartNs == 0L) {
            windowStartNs = nowNs
        } else {
            val elapsed = nowNs - windowStartNs
            if (elapsed >= 500_000_000L) {
                currentPollingRateHz = ((packetCounter * 1_000_000_000L) / elapsed).toInt()
                packetCounter = 0
                windowStartNs = nowNs
            }
        }

        val alpha = 0.15f
        emaParseNs = if (emaParseNs == 0f) parseNs.toFloat() else emaParseNs + alpha * (parseNs - emaParseNs)
        emaTransformNs = if (emaTransformNs == 0f) transformNs.toFloat() else emaTransformNs + alpha * (transformNs - emaTransformNs)
        emaInjectNs = if (emaInjectNs == 0f) injectNs.toFloat() else emaInjectNs + alpha * (injectNs - emaInjectNs)
    }

    fun getParseUs(): Float = emaParseNs / 1_000f
    fun getMatrixUs(): Float = emaTransformNs / 1_000f
    fun getInjectUs(): Float = emaInjectNs / 1_000f
    fun getTotalLatencyMs(): Float = (emaParseNs + emaTransformNs + emaInjectNs) / 1_000_000f
    fun getTotalPackets(): Long = totalPackets

    fun reset() {
        windowStartNs = 0L
        packetCounter = 0
        currentPollingRateHz = 0
        emaParseNs = 0f
        emaTransformNs = 0f
        emaInjectNs = 0f
    }
}
