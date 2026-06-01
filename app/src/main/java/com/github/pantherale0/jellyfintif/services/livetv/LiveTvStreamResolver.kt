package com.github.pantherale0.jellyfintif.services.livetv

import androidx.media3.common.MimeTypes
import com.github.pantherale0.jellyfintif.LiveTvConstants
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.DeviceProfileService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.dynamicHlsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class LiveTvStream(
    val url: String,
    val mimeType: String?,
    val liveStreamId: String?,
    val playSessionId: String?,
    val mediaSourceId: String?,
    val bufferMs: Int?,
    val mediaSourceInfo: MediaSourceInfo,
)

data class LiveTvResolveOptions(
    val startTimeTicks: Long? = null,
    val liveStreamId: String? = null,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val enableDirectPlay: Boolean = false,
    val enableDirectStream: Boolean = true,
)

@Singleton
class LiveTvStreamResolver
    @Inject
    constructor(
        private val api: ApiClient,
        private val deviceProfileService: DeviceProfileService,
    ) {
        suspend fun resolve(
            channelId: UUID,
            options: LiveTvResolveOptions = LiveTvResolveOptions(),
        ): LiveTvStream? {
            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                Timber.w("Live TV stream: API client is not authenticated")
                return null
            }
            val maxBitrate = LiveTvConstants.DEFAULT_BITRATE
            val response by
                api.mediaInfoApi.getPostedPlaybackInfo(
                    channelId,
                    PlaybackInfoDto(
                        startTimeTicks = options.startTimeTicks,
                        liveStreamId = options.liveStreamId,
                        mediaSourceId = options.mediaSourceId,
                        audioStreamIndex = options.audioStreamIndex,
                        subtitleStreamIndex = options.subtitleStreamIndex,
                        deviceProfile = deviceProfileService.getOrCreateDeviceProfile(),
                        alwaysBurnInSubtitleWhenTranscoding = false,
                        maxStreamingBitrate = maxBitrate,
                        enableDirectPlay = options.enableDirectPlay,
                        enableDirectStream = options.enableDirectStream,
                        allowVideoStreamCopy = options.enableDirectStream,
                        allowAudioStreamCopy = options.enableDirectStream,
                        enableTranscoding = true,
                        autoOpenLiveStream = true,
                    ),
                )
            if (response.errorCode != null) {
                Timber.w("Live TV stream: playback info error %s", response.errorCode)
                return null
            }
            val source = response.mediaSources.firstOrNull() ?: return null
            val resolved = resolveStreamUrl(channelId, source, response.playSessionId, options.startTimeTicks)
            return resolved?.copy(
                liveStreamId = source.liveStreamId,
                playSessionId = response.playSessionId,
                mediaSourceId = source.id,
                bufferMs = source.bufferMs,
                mediaSourceInfo = source,
            )
        }

        private fun resolveStreamUrl(
            channelId: UUID,
            source: MediaSourceInfo,
            playSessionId: String?,
            startTimeTicks: Long?,
        ): LiveTvStream? {
            val liveStreamSessionId =
                source.liveStreamId
                    ?.substringBefore('_')
                    ?.takeIf { it.isNotBlank() }
            val segmentContainer =
                source.container?.takeIf {
                    it.matches(Regex("""^[a-zA-Z0-9\-\._,|]{0,40}$"""))
                } ?: "ts"
            val hasMasterHls = !source.liveStreamId.isNullOrBlank() && !source.id.isNullOrBlank()
            val prefersTimeshiftHls = hasMasterHls && (startTimeTicks != null || source.bufferMs != null)
            val transcodingUrl = source.transcodingUrl
            val url: String
            val mimeType: String?
            when {
                prefersTimeshiftHls -> {
                    url =
                        api.dynamicHlsApi.getMasterHlsVideoPlaylistUrl(
                            itemId = channelId,
                            mediaSourceId = source.id!!,
                            liveStreamId = source.liveStreamId,
                            playSessionId = playSessionId,
                            tag = source.eTag,
                            static = false,
                            segmentContainer = segmentContainer,
                            enableAutoStreamCopy = true,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = true,
                            startTimeTicks = startTimeTicks,
                        )
                    mimeType = MimeTypes.APPLICATION_M3U8
                }

                !transcodingUrl.isNullOrBlank() -> {
                    url = api.createUrl(transcodingUrl)
                    mimeType = mimeTypeForStreamUrl(url, source.container)
                }

                hasMasterHls -> {
                    url =
                        api.dynamicHlsApi.getMasterHlsVideoPlaylistUrl(
                            itemId = channelId,
                            mediaSourceId = source.id!!,
                            liveStreamId = source.liveStreamId,
                            playSessionId = playSessionId,
                            tag = source.eTag,
                            static = false,
                            segmentContainer = segmentContainer,
                            enableAutoStreamCopy = true,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = true,
                            startTimeTicks = startTimeTicks,
                        )
                    mimeType = MimeTypes.APPLICATION_M3U8
                }

                liveStreamSessionId != null -> {
                    url = api.liveTvApi.getLiveStreamFileUrl(liveStreamSessionId, segmentContainer)
                    mimeType = MimeTypes.VIDEO_MP2T
                }

                source.supportsDirectPlay -> {
                    url =
                        api.createUrl(
                            api.videosApi.getVideoStreamUrl(
                                itemId = channelId,
                                mediaSourceId = source.id,
                                static = false,
                                tag = source.eTag,
                                playSessionId = playSessionId,
                            ),
                        ).toString()
                    mimeType = mimeTypeForContainer(source.container)
                }

                else -> return null
            }
            if (url.isBlank()) return null
            return LiveTvStream(
                url = url,
                mimeType = mimeType,
                liveStreamId = null,
                playSessionId = null,
                mediaSourceId = null,
                bufferMs = null,
                mediaSourceInfo = source,
            )
        }

        private fun mimeTypeForStreamUrl(
            url: String,
            container: String?,
        ): String? =
            when {
                url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> mimeTypeForContainer(container)
            }

        private fun mimeTypeForContainer(container: String?): String? =
            when (container?.lowercase()) {
                "hls", "m3u8" -> MimeTypes.APPLICATION_M3U8
                "dash", "mpd" -> MimeTypes.APPLICATION_MPD
                "ts", "mpegts", "mpeg-ts" -> MimeTypes.VIDEO_MP2T
                else -> null
            }
    }
