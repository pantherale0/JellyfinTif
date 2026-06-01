package com.github.pantherale0.jellyfintif.services.tif

import com.github.pantherale0.jellyfintif.services.livetv.LiveTvResolveOptions
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvStream
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvStreamResolver
import org.jellyfin.sdk.model.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backward-compatible wrapper for the shared Live TV stream resolver.
 */
@Singleton
class TifStreamHelper
    @Inject
    constructor(
        private val liveTvStreamResolver: LiveTvStreamResolver,
    ) {
        suspend fun getChannelStream(
            channelId: UUID,
            startTimeTicks: Long? = null,
            liveStreamId: String? = null,
            mediaSourceId: String? = null,
        ): LiveTvStream? =
            liveTvStreamResolver.resolve(
                channelId = channelId,
                options =
                    LiveTvResolveOptions(
                        startTimeTicks = startTimeTicks,
                        liveStreamId = liveStreamId,
                        mediaSourceId = mediaSourceId,
                        enableDirectPlay = false,
                        enableDirectStream = true,
                    ),
            )
    }
