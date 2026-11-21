package com.example.driver

import java.util.concurrent.atomic.AtomicLong

/**
 * Pre-allocated lock-free single-producer / single-consumer ring buffer for USB HID packets.
 * Avoids any ByteArray or object allocation during high-rate 200-1000 Hz tablet strokes.
 */
class ZeroAllocRingBuffer(
    val capacity: Int = 256,
    val packetSize: Int = 64
) {
    private val slots = Array(capacity) { ByteArray(packetSize) }
    private val lengths = IntArray(capacity)
    private val timestampsNs = LongArray(capacity)

    private val writeIdx = AtomicLong(0L)
    private val readIdx = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)

    fun offer(source: ByteArray, length: Int, timestampNs: Long): Boolean {
        val w = writeIdx.get()
        val r = readIdx.get()
        if (w - r >= capacity) {
            droppedCount.incrementAndGet()
            return false
        }
        val slotIndex = (w % capacity).toInt()
        val copyLen = length.coerceIn(0, packetSize)
        System.arraycopy(source, 0, slots[slotIndex], 0, copyLen)
        lengths[slotIndex] = copyLen
        timestampsNs[slotIndex] = timestampNs
        writeIdx.lazySet(w + 1)
        return true
    }

    fun poll(dest: ByteArray, outMeta: LongArray): Int {
        val r = readIdx.get()
        val w = writeIdx.get()
        if (r >= w) return -1
        val slotIndex = (r % capacity).toInt()
        val len = lengths[slotIndex]
        System.arraycopy(slots[slotIndex], 0, dest, 0, len)
        outMeta[0] = timestampsNs[slotIndex]
        readIdx.lazySet(r + 1)
        return len
    }

    fun getDroppedCount(): Long = droppedCount.get()

    fun clear() {
        readIdx.set(writeIdx.get())
    }
}
