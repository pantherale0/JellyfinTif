package com.github.pantherale0.jellyfintif

object LiveTvConstants {
    const val MAX_HOURS = 48L
    const val DEFAULT_BITRATE = 100_000_000

    /** How often to refresh guide data in the background (WorkManager periodic interval). */
    const val EPG_SYNC_INTERVAL_HOURS = 6L
}
