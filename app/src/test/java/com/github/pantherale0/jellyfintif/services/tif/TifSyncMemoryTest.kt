package com.github.pantherale0.jellyfintif.services.tif

import com.github.pantherale0.jellyfintif.LiveTvConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TifSyncMemoryTest {
    @Test
    fun truncateDescription_nullOrBlank_returnsNull() {
        assertNull(TifSyncMemory.truncateDescription(null))
        assertNull(TifSyncMemory.truncateDescription(""))
        assertNull(TifSyncMemory.truncateDescription("   "))
    }

    @Test
    fun truncateDescription_shortText_unchanged() {
        assertEquals("Evening news", TifSyncMemory.truncateDescription("Evening news"))
    }

    @Test
    fun truncateDescription_longText_capped() {
        val long = "a".repeat(LiveTvConstants.PROGRAM_DESCRIPTION_MAX_CHARS + 50)
        val truncated = TifSyncMemory.truncateDescription(long)!!
        assertEquals(LiveTvConstants.PROGRAM_DESCRIPTION_MAX_CHARS, truncated.length)
        assertTrue(truncated.endsWith("…"))
    }

    @Test
    fun calculateInSampleSize_alreadySmall_returnsOne() {
        assertEquals(1, TifSyncMemory.calculateInSampleSize(160, 90, 320, 320))
    }

    @Test
    fun calculateInSampleSize_largeImage_downsamples() {
        val sample = TifSyncMemory.calculateInSampleSize(1920, 1080, 320, 320)
        assertTrue("expected sample>=2, got $sample", sample >= 2)
        assertTrue("sample should be power of 2, got $sample", sample and (sample - 1) == 0)
    }

    @Test
    fun guideWindowHours_lowRamUsesShorterWindow() {
        assertEquals(LiveTvConstants.MAX_HOURS_LOW_RAM, TifSyncMemory.guideWindowHours(true))
        assertEquals(LiveTvConstants.MAX_HOURS, TifSyncMemory.guideWindowHours(false))
    }
}
