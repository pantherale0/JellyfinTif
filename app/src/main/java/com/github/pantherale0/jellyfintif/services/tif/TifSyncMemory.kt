package com.github.pantherale0.jellyfintif.services.tif

import com.github.pantherale0.jellyfintif.LiveTvConstants

/**
 * Pure helpers for keeping EPG / logo sync within a TV-friendly memory budget.
 */
object TifSyncMemory {
    fun truncateDescription(
        text: String?,
        maxChars: Int = LiveTvConstants.PROGRAM_DESCRIPTION_MAX_CHARS,
    ): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars - 1).trimEnd() + "…"
    }

    fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (height <= 0 || width <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun guideWindowHours(lowRamDevice: Boolean): Long =
        if (lowRamDevice) LiveTvConstants.MAX_HOURS_LOW_RAM else LiveTvConstants.MAX_HOURS
}
