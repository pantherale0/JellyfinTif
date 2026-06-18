package com.github.pantherale0.jellyfintif.services

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory

/**
 * Routes Jellyfin live HLS through a playlist tracker tuned for slow transcode segment generation.
 */
@UnstableApi
internal class TifMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
) : MediaSource.Factory {
    private val defaultFactory =
        DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)

    private val hlsFactory =
        HlsMediaSource
            .Factory(DefaultHlsDataSourceFactory(dataSourceFactory))
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory { hlsDataSourceFactory, loadErrorHandlingPolicy, playlistParserFactory, cmcdConfiguration, downloadExecutorSupplier ->
                DefaultHlsPlaylistTracker(
                    hlsDataSourceFactory,
                    loadErrorHandlingPolicy,
                    playlistParserFactory,
                    cmcdConfiguration,
                    PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
                    downloadExecutorSupplier,
                )
            }

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (isHlsMediaItem(mediaItem)) {
            return hlsFactory.createMediaSource(mediaItem)
        }
        return defaultFactory.createMediaSource(mediaItem)
    }

    private fun isHlsMediaItem(mediaItem: MediaItem): Boolean {
        val localConfiguration = mediaItem.localConfiguration ?: return false
        if (localConfiguration.mimeType == MimeTypes.APPLICATION_M3U8) return true
        return localConfiguration.uri?.toString()?.contains(".m3u8", ignoreCase = true) == true
    }

    companion object {
        /**
         * Jellyfin transcodes can take longer than 3.5× target duration to append the next segment.
         */
        private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 12.0
        private const val LIVE_TARGET_OFFSET_MS = 30_000L
    }
}
