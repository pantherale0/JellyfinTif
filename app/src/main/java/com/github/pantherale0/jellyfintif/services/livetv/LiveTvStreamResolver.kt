package com.github.pantherale0.jellyfintif.services.livetv

import androidx.media3.common.MimeTypes
import com.github.pantherale0.jellyfintif.LiveTvConstants
import com.github.pantherale0.jellyfintif.services.DeviceProfileService
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.dynamicHlsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlaybackInfoDto
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
            val optionsSummary =
                "startTimeTicks=${options.startTimeTicks}, liveStreamId=${options.liveStreamId}, " +
                    "mediaSourceId=${options.mediaSourceId}, directPlay=${options.enableDirectPlay}, " +
                    "directStream=${options.enableDirectStream}"

            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                ConnectionLog.streamResolveFailure(channelId, "API client not authenticated")
                ConnectionLog.apiClient("stream.resolve", api)
                return null
            }

            return try {
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
                    ConnectionLog.streamResolveFailure(
                        channelId,
                        "playback info errorCode=${response.errorCode}",
                    )
                    return null
                }
                val source = response.mediaSources.firstOrNull()
                if (source == null) {
                    ConnectionLog.streamResolveFailure(channelId, "no media sources in playback info response")
                    return null
                }
                ConnectionLog.streamResolve(
                    channelId,
                    optionsSummary,
                    "playbackInfo: container=${source.container} transcode=${!source.transcodingUrl.isNullOrBlank()} " +
                        "liveStreamId=${source.liveStreamId} bufferMs=${source.bufferMs}",
                )
                val resolved = resolveStreamUrl(channelId, source, response.playSessionId, options.startTimeTicks)
                resolved?.copy(
                    liveStreamId = source.liveStreamId,
                    playSessionId = response.playSessionId,
                    mediaSourceId = source.id,
                    bufferMs = source.bufferMs,
                    mediaSourceInfo = source,
                )
            } catch (ex: Exception) {
                ConnectionLog.streamResolveFailure(channelId, "exception during resolve", ex)
                null
            }
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
            val strategy: String
            when {
                prefersTimeshiftHls -> {
                    strategy = "timeshift-hls"
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
                    strategy = "transcoding-url"
                    url = api.createUrl(transcodingUrl)
                    mimeType = mimeTypeForStreamUrl(url, source.container)
                }

                hasMasterHls -> {
                    strategy = "live-hls"
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
                    strategy = "live-ts"
                    url = api.liveTvApi.getLiveStreamFileUrl(liveStreamSessionId, segmentContainer)
                    mimeType = MimeTypes.VIDEO_MP2T
                }

                source.supportsDirectPlay -> {
                    strategy = "direct-play"
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

                else -> {
                    ConnectionLog.streamResolveFailure(
                        channelId,
                        "no compatible stream strategy (container=${source.container}, directPlay=${source.supportsDirectPlay})",
                    )
                    return null
                }
            }
            if (url.isBlank()) {
                ConnectionLog.streamResolveFailure(channelId, "strategy=$strategy returned blank URL")
                return null
            }
            ConnectionLog.streamResolve(
                channelId,
                "strategy=$strategy",
                "mimeType=$mimeType url=${ConnectionLog.sanitizeUrl(url)}",
            )
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
