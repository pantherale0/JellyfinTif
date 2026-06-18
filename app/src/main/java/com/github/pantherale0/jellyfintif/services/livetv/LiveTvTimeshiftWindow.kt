package com.github.pantherale0.jellyfintif.services.livetv

import androidx.media3.common.C
import kotlin.math.max
import kotlin.math.min

data class LiveTvTimeshiftWindow(
    val tuneStartedAtMs: Long,
    val seekableStartUtcMs: Long,
    val seekableEndUtcMs: Long,
    val effectiveBufferMs: Long,
) {
    val isAvailable: Boolean
        get() = effectiveBufferMs > 0L && seekableEndUtcMs >= seekableStartUtcMs

    fun clampUtc(targetUtcMs: Long): Long =
        targetUtcMs.coerceIn(seekableStartUtcMs, seekableEndUtcMs)

    fun utcToStartTimeTicks(targetUtcMs: Long): Long {
        val target = clampUtc(targetUtcMs)
        val deltaMs = seekableEndUtcMs - target
        if (deltaMs <= 0L) return 0L
        return (deltaMs * C.MICROS_PER_SECOND) / 100L
    }

    companion object {
        const val DEFAULT_MAX_BUFFER_MS = 60 * 60 * 1000L

        fun from(
            nowMs: Long,
            tuneStartedAtMs: Long,
            serverBufferMs: Int?,
            maxBufferMs: Long = DEFAULT_MAX_BUFFER_MS,
            programStartUtcMs: Long? = null,
            programEndUtcMs: Long? = null,
        ): LiveTvTimeshiftWindow {
            val uptimeMs = max(0L, nowMs - tuneStartedAtMs)
            val serverCap = (serverBufferMs?.toLong() ?: maxBufferMs).coerceAtLeast(0L)
            val effectiveBuffer = min(min(serverCap, maxBufferMs), uptimeMs)
            val unclampedStart = nowMs - effectiveBuffer
            val start = programStartUtcMs?.let { max(unclampedStart, it) } ?: unclampedStart
            val end = programEndUtcMs?.let { min(nowMs, it) } ?: nowMs
            return LiveTvTimeshiftWindow(
                tuneStartedAtMs = tuneStartedAtMs,
                seekableStartUtcMs = min(start, end),
                seekableEndUtcMs = max(start, end),
                effectiveBufferMs = max(0L, end - start),
            )
        }
    }
}
