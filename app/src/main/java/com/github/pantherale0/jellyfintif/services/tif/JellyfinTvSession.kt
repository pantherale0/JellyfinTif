package com.github.pantherale0.jellyfintif.services.tif

import android.content.Context
import android.media.PlaybackParams
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvStream
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.TifPlayerFactory
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import com.github.pantherale0.jellyfintif.util.launchIO
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvTimeshiftWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class JellyfinTvSession(
    serviceContext: Context,
    @Suppress("UNUSED_PARAMETER") inputId: String,
    private val playerFactory: TifPlayerFactory,
    private val streamHelper: TifStreamHelper,
    private val sessionRepository: SessionRepository,
    private val api: ApiClient,
) : TvInputService.Session(serviceContext) {
    private val appContext = serviceContext.applicationContext
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var tuneJob: Job? = null
    private var currentChannelId: UUID? = null
    private var currentChannelUri: Uri? = null
    private var contentAllowedNotified = false
    private var pendingSurface: Surface? = null
    private var attachedSurface: Surface? = null
    private var videoAvailableNotified = false
    private var firstFrameRendered = false
    private var hostSurfaceWidth = 0
    private var hostSurfaceHeight = 0
    private var tuneStartedAtMs = 0L
    private var currentLiveStream: LiveTvStream? = null
    private var timeshiftWindow: LiveTvTimeshiftWindow? = null
    private var playbackAnchorUtcMs: Long = 0L
    private val seekMutex = Mutex()

    override fun onRelease() {
        tuneJob?.cancel()
        val position = player?.currentPosition?.milliseconds ?: 0.milliseconds
        sessionScope.launchIO {
            reportPlaybackStopped(position)
        }
        notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_UNAVAILABLE)
        currentLiveStream = null
        timeshiftWindow = null
        player?.release()
        player = null
        sessionScope.cancel()
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {
        // Live TV captions are not configured for system input playback
    }

    override fun onSetStreamVolume(volume: Float) {
        player?.volume = volume
    }

    override fun onTimeShiftPause() {
        player?.pause()
    }

    override fun onTimeShiftResume() {
        player?.play()
    }

    override fun onTimeShiftSeekTo(timeMs: Long) {
        val channelId = currentChannelId ?: return
        val stream = currentLiveStream ?: return
        val window = timeshiftWindow ?: return
        sessionScope.launchIO {
            seekMutex.withLock {
                val targetUtc = window.clampUtc(timeMs)
                val ticks = window.utcToStartTimeTicks(targetUtc)
                val resolved =
                    streamHelper.getChannelStream(
                        channelId = channelId,
                        startTimeTicks = ticks,
                        liveStreamId = stream.liveStreamId,
                        mediaSourceId = stream.mediaSourceId,
                    ) ?: return@withLock
                currentLiveStream = resolved
                updateTimeshiftWindow(resolved)
                val exoPlayer = player ?: return@withLock
                withContext(Dispatchers.Main) {
                    val mediaItem =
                        MediaItem
                            .Builder()
                            .setUri(resolved.url.toUri())
                            .apply {
                                resolved.mimeType?.let { setMimeType(it) }
                            }.build()
                    exoPlayer.setMediaItem(mediaItem, 0L)
                    playbackAnchorUtcMs = targetUtc
                    val shouldPlay = exoPlayer.playWhenReady
                    exoPlayer.prepare()
                    if (shouldPlay) {
                        exoPlayer.play()
                    }
                    attachedSurface = null
                    attachVideoSurface(exoPlayer, force = true, allowBeforeReady = true)
                }
            }
        }
    }

    override fun onTimeShiftSetPlaybackParams(params: PlaybackParams) {
        val speed = params.speed
        if (speed > 0f) {
            player?.setPlaybackSpeed(speed)
        }
    }

    override fun onTimeShiftGetStartPosition(): Long =
        timeshiftWindow?.seekableStartUtcMs ?: TvInputManager.TIME_SHIFT_INVALID_TIME

    override fun onTimeShiftGetCurrentPosition(): Long {
        val window = timeshiftWindow ?: return TvInputManager.TIME_SHIFT_INVALID_TIME
        val exoPlayer = player ?: return TvInputManager.TIME_SHIFT_INVALID_TIME
        val currentUtc = playbackAnchorUtcMs + exoPlayer.currentPosition.coerceAtLeast(0L)
        return window.clampUtc(currentUtc)
    }

    override fun onTune(channelUri: Uri): Boolean {
        currentChannelUri = channelUri
        contentAllowedNotified = false
        videoAvailableNotified = false
        firstFrameRendered = false
        tuneStartedAtMs = System.currentTimeMillis()
        currentLiveStream = null
        timeshiftWindow = null
        playbackAnchorUtcMs = tuneStartedAtMs
        attachedSurface = null
        releasePlayerForRetune()
        if (hostSurfaceWidth > 0 && hostSurfaceHeight > 0) {
            applySurfaceLayout(hostSurfaceWidth, hostSurfaceHeight, force = true)
        }
        notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_UNAVAILABLE)
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        tuneJob?.cancel()
        tuneJob =
            sessionScope.launchIO {
                ConnectionLog.playback("onTune: channelUri=$channelUri")
                if (!ensureAuthenticated()) {
                    ConnectionLog.playback("onTune aborted: not authenticated")
                    withContext(Dispatchers.Main) {
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                    return@launchIO
                }
                val channelId = resolveChannelId(channelUri)
                if (channelId == null) {
                    ConnectionLog.playback("onTune aborted: could not resolve channel from $channelUri")
                    withContext(Dispatchers.Main) {
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                    return@launchIO
                }
                currentChannelId = channelId
                withContext(Dispatchers.Main) {
                    notifyBuffering()
                }
                val stream = streamHelper.getChannelStream(channelId)
                if (stream == null) {
                    ConnectionLog.playback("onTune aborted: no stream URL for channel $channelId")
                    withContext(Dispatchers.Main) {
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                    return@launchIO
                }
                currentLiveStream = stream
                ConnectionLog.playback(
                    "onTune resolved channel=$channelId mimeType=${stream.mimeType} bufferMs=${stream.bufferMs}",
                )
                updateTimeshiftWindow(stream)
                withContext(Dispatchers.Main) {
                    val exoPlayer =
                        playerFactory.createTvInputPlayer().also {
                            it.addListener(
                                object : Player.Listener {
                                    override fun onPlayerError(error: PlaybackException) {
                                        Timber.e(error, "TIF playback error")
                                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                                    }

                                    override fun onPlaybackStateChanged(playbackState: Int) {
                                        when (playbackState) {
                                            Player.STATE_BUFFERING -> notifyBuffering()
                                            Player.STATE_READY -> {
                                                attachVideoSurface(player, force = true)
                                                maybeMarkVideoAvailable()
                                            }
                                            Player.STATE_ENDED ->
                                                notifyVideoUnavailable(
                                                    TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN,
                                                )
                                        }
                                    }

                                    override fun onTracksChanged(tracks: Tracks) {
                                        publishTifTrackInfo(tracks)
                                    }

                                    override fun onRenderedFirstFrame() {
                                        if (firstFrameRendered) return
                                        firstFrameRendered = true
                                        attachVideoSurface(
                                            player,
                                            force = true,
                                            allowBeforeReady = true,
                                        )
                                        maybeMarkVideoAvailable()
                                    }
                                },
                            )
                            player = it
                        }
                    attachVideoSurface(exoPlayer, force = true, allowBeforeReady = true)
                    val mediaItem =
                        MediaItem
                            .Builder()
                            .setUri(stream.url.toUri())
                            .apply {
                                stream.mimeType?.let { setMimeType(it) }
                            }.build()
                    exoPlayer.setMediaItem(mediaItem)
                    playbackAnchorUtcMs = System.currentTimeMillis()
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    notifyTimeShiftStatusChanged(
                        if (timeshiftWindow?.isAvailable == true) {
                            TvInputManager.TIME_SHIFT_STATUS_AVAILABLE
                        } else {
                            TvInputManager.TIME_SHIFT_STATUS_UNAVAILABLE
                        },
                    )
                    sessionScope.launchIO {
                        reportPlaybackStarted(channelId)
                    }
                }
            }
        return true
    }

    override fun onSetSurface(surface: Surface?): Boolean {
        if (surface == null) {
            pendingSurface = null
            attachedSurface = null
            videoAvailableNotified = false
            // Do not call ExoPlayer.setVideoSurface(null); Sony clears the surface during UI updates.
            return true
        }
        pendingSurface = surface
        attachVideoSurface(
            player,
            force = true,
            allowBeforeReady = player != null,
        )
        if (firstFrameRendered && player?.playbackState == Player.STATE_READY) {
            markVideoAvailable()
        }
        return true
    }

    override fun onSurfaceChanged(
        format: Int,
        width: Int,
        height: Int,
    ) {
        if (width > 0 && height > 0) {
            applySurfaceLayout(width, height)
        }
        if (player != null) {
            attachedSurface = null
            attachVideoSurface(player, force = true, allowBeforeReady = true)
        }
    }

    private fun applySurfaceLayout(
        width: Int,
        height: Int,
        force: Boolean = false,
    ) {
        if (width <= 0 || height <= 0) return
        if (!force && width == hostSurfaceWidth && height == hostSurfaceHeight) return
        hostSurfaceWidth = width
        hostSurfaceHeight = height
        layoutSurface(0, 0, width, height)
    }

    private fun releasePlayerForRetune() {
        player?.release()
        player = null
        attachedSurface = null
    }

    private fun attachVideoSurface(
        exoPlayer: ExoPlayer?,
        force: Boolean = false,
        allowBeforeReady: Boolean = false,
    ) {
        val surface = pendingSurface ?: return
        if (!surface.isValid) return
        val playbackState = exoPlayer?.playbackState ?: Player.STATE_IDLE
        if (!allowBeforeReady && playbackState != Player.STATE_READY) return
        if (!force && attachedSurface === surface) return
        attachedSurface = surface
        exoPlayer?.setVideoSurface(surface)
    }

    private fun notifyBuffering() {
        videoAvailableNotified = false
        ConnectionLog.playback("notifyVideoUnavailable: BUFFERING")
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
    }

    private fun maybeMarkVideoAvailable() {
        if (videoAvailableNotified) return
        if (pendingSurface?.isValid != true) return
        val exoPlayer = player ?: return
        if (!firstFrameRendered) return
        if (exoPlayer.playbackState != Player.STATE_READY) return
        markVideoAvailable()
    }

    private fun markVideoAvailable() {
        if (videoAvailableNotified) return
        if (pendingSurface?.isValid != true) return
        publishContentAllowed(currentChannelUri)
        player?.currentTracks?.let { publishTifTrackInfo(it) }
        if (hostSurfaceWidth > 0 && hostSurfaceHeight > 0) {
            applySurfaceLayout(hostSurfaceWidth, hostSurfaceHeight, force = true)
        }
        videoAvailableNotified = true
        notifyVideoAvailable()
    }

    private fun publishTifTrackInfo(tracks: Tracks) {
        val tifTracks = ArrayList<TvTrackInfo>()
        var selectedVideoId: String? = null
        var selectedAudioId: String? = null
        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val trackId = "video-$i"
                        val builder = TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, trackId)
                        if (format.width > 0) builder.setVideoWidth(format.width)
                        if (format.height > 0) builder.setVideoHeight(format.height)
                        format.language?.let { builder.setLanguage(it) }
                        tifTracks.add(builder.build())
                        if (group.isTrackSelected(i)) {
                            selectedVideoId = trackId
                        }
                    }
                }

                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val trackId = "audio-$i"
                        val builder = TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, trackId)
                        format.language?.let { builder.setLanguage(it) }
                        tifTracks.add(builder.build())
                        if (group.isTrackSelected(i)) {
                            selectedAudioId = trackId
                        }
                    }
                }
            }
        }
        if (tifTracks.isEmpty()) return
        notifyTracksChanged(tifTracks)
        selectedVideoId?.let { notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, it) }
        selectedAudioId?.let { notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, it) }
    }

    private fun publishContentAllowed(channelUri: Uri?) {
        if (contentAllowedNotified) return
        val tvInputManager =
            appContext.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        if (!tvInputManager.isParentalControlsEnabled) {
            notifyContentAllowed()
            contentAllowedNotified = true
            return
        }
        val rating = channelUri?.let { queryCurrentProgramRating(it) }
        if (rating != null && tvInputManager.isRatingBlocked(rating)) {
            notifyContentBlocked(rating)
        } else {
            notifyContentAllowed()
        }
        contentAllowedNotified = true
    }

    private fun queryCurrentProgramRating(channelUri: Uri): TvContentRating? {
        val now = System.currentTimeMillis()
        val programsUri =
            TvContract.buildProgramsUriForChannel(channelUri, now, now + 3_600_000L)
        return runCatching {
            appContext.contentResolver
                .query(
                    programsUri,
                    arrayOf(TvContract.Programs.COLUMN_CONTENT_RATING),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(TvContract.Programs.COLUMN_CONTENT_RATING)
                    if (index < 0) return@use null
                    cursor.getString(index)?.let { TvContentRating.unflattenFromString(it) }
                }
        }.getOrNull()
    }

    private fun queryCurrentProgramBounds(channelUri: Uri): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        val programsUri = TvContract.buildProgramsUriForChannel(channelUri, now - 3_600_000L, now + 3_600_000L)
        return runCatching {
            appContext.contentResolver
                .query(
                    programsUri,
                    arrayOf(
                        TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                        TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val startIdx = cursor.getColumnIndex(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS)
                    val endIdx = cursor.getColumnIndex(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS)
                    if (startIdx < 0 || endIdx < 0) {
                        return@use null
                    }
                    cursor.getLong(startIdx) to cursor.getLong(endIdx)
                }
        }.getOrNull() ?: (null to null)
    }

    private fun updateTimeshiftWindow(stream: LiveTvStream) {
        val channelUri = currentChannelUri
        val (programStartMs, programEndMs) =
            if (channelUri != null) {
                queryCurrentProgramBounds(channelUri)
            } else {
                null to null
            }
        val now = System.currentTimeMillis()
        val window =
            LiveTvTimeshiftWindow.from(
                nowMs = now,
                tuneStartedAtMs = tuneStartedAtMs,
                serverBufferMs = stream.bufferMs,
                programStartUtcMs = programStartMs,
                programEndUtcMs = programEndMs,
            )
        timeshiftWindow = window
    }

    private suspend fun ensureAuthenticated(): Boolean {
        if (!api.accessToken.isNullOrBlank() && !api.baseUrl.isNullOrBlank()) {
            ConnectionLog.playback("ensureAuthenticated: already configured")
            return true
        }
        ConnectionLog.playback("ensureAuthenticated: restoring session")
        val restored = sessionRepository.restoreSession()
        if (!restored) {
            ConnectionLog.playback("ensureAuthenticated: restore failed")
        }
        ConnectionLog.apiClient("playback.tune", api)
        return restored
    }

    private suspend fun reportPlaybackStarted(channelId: UUID) {
        if (api.accessToken.isNullOrBlank()) return
        runCatching {
            api.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    canSeek = timeshiftWindow?.isAvailable == true,
                    itemId = channelId,
                    isPaused = false,
                    playMethod = PlayMethod.TRANSCODE,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    isMuted = false,
                ),
            )
        }.onFailure { Timber.w(it, "TIF reportPlaybackStart failed") }
    }

    private suspend fun reportPlaybackStopped(position: kotlin.time.Duration) {
        val channelId = currentChannelId ?: return
        if (api.accessToken.isNullOrBlank()) return
        runCatching {
            api.playStateApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = channelId,
                    positionTicks = position.inWholeTicks,
                    failed = false,
                ),
            )
        }.onFailure { Timber.w(it, "TIF reportPlaybackStopped failed") }
        currentChannelId = null
    }

    private fun resolveChannelId(channelUri: Uri): UUID? =
        appContext.contentResolver
            .query(
                channelUri,
                arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index =
                        cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    if (index >= 0) {
                        cursor.getString(index)?.toUUIDOrNull()
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
}
