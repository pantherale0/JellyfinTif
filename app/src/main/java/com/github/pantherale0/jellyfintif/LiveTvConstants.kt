package com.github.pantherale0.jellyfintif

object LiveTvConstants {
    const val MAX_HOURS = 48L

    /** Shorter guide window on low-RAM TVs to limit EPG heap during sync. */
    const val MAX_HOURS_LOW_RAM = 24L

    const val DEFAULT_BITRATE = 100_000_000

    /** How often to refresh guide data in the background (WorkManager periodic interval). */
    const val EPG_SYNC_INTERVAL_HOURS = 6L

    /** Channels per Jellyfin getPrograms request — keeps peak heap bounded. */
    const val EPG_PROGRAM_CHANNEL_BATCH_SIZE = 20

    /** Max edge length for channel logos written into TvContract. */
    const val CHANNEL_LOGO_MAX_PX = 320

    /** Cap program descriptions stored in TvContract (characters). */
    const val PROGRAM_DESCRIPTION_MAX_CHARS = 256
}
