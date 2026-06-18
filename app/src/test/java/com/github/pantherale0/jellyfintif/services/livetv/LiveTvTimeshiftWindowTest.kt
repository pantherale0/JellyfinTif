package com.github.pantherale0.jellyfintif.services.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvTimeshiftWindowTest {
    @Test
    fun `window is capped by uptime`() {
        val now = 1_000_000L
        val tuneStart = now - 30_000L
        val window =
            LiveTvTimeshiftWindow.from(
                nowMs = now,
                tuneStartedAtMs = tuneStart,
                serverBufferMs = 3_600_000,
            )
        assertEquals(30_000L, window.effectiveBufferMs)
        assertEquals(now - 30_000L, window.seekableStartUtcMs)
        assertEquals(now, window.seekableEndUtcMs)
    }

    @Test
    fun `window is capped by server buffer`() {
        val now = 2_000_000L
        val tuneStart = now - 300_000L
        val window =
            LiveTvTimeshiftWindow.from(
                nowMs = now,
                tuneStartedAtMs = tuneStart,
                serverBufferMs = 90_000,
            )
        assertEquals(90_000L, window.effectiveBufferMs)
        assertEquals(now - 90_000L, window.seekableStartUtcMs)
    }

    @Test
    fun `utc is converted to startTimeTicks offset`() {
        val window =
            LiveTvTimeshiftWindow(
                tuneStartedAtMs = 0L,
                seekableStartUtcMs = 1_000L,
                seekableEndUtcMs = 11_000L,
                effectiveBufferMs = 10_000L,
            )
        val ticks = window.utcToStartTimeTicks(6_000L)
        assertEquals(50_000_000L, ticks)
    }

    @Test
    fun `program bounds clamp seekable range`() {
        val now = 500_000L
        val window =
            LiveTvTimeshiftWindow.from(
                nowMs = now,
                tuneStartedAtMs = now - 120_000L,
                serverBufferMs = 120_000,
                programStartUtcMs = now - 60_000L,
                programEndUtcMs = now + 10_000L,
            )
        assertEquals(now - 60_000L, window.seekableStartUtcMs)
        assertEquals(now, window.seekableEndUtcMs)
        assertTrue(window.isAvailable)
    }
}
