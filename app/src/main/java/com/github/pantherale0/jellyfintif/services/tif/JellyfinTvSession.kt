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
import com.github.pantherale0.jellyfintif.R
import android.view.View
import android.view.LayoutInflater
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvStream
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.TifPlayerFactory
import com.github.pantherale0.jellyfintif.services.tif.TifDeviceQuirks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import com.github.pantherale0.jellyfintif.util.launchIO
import com.github.pantherale0.jellyfintif.services.livetv.LiveTvTimeshiftWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private var playbackRetryCount = 0
    private var forceSoftwareVideoDecoders = false
    private var firstFrameWatchdogJob: Job? = null

    init {
        setOverlayViewEnabled(true)
    }

    private var bufferingOverlay: View? = null
    private var bufferingOverlayVisible = false
    private var overlayViewCreated = false

    override fun onRelease() {
        tuneJob?.cancel()
        cancelFirstFrameWatchdog()
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


    override fun onCreateOverlayView(): View =
        LayoutInflater.from(appContext).inflate(R.layout.tif_playback_overlay, null).also { overlay ->
            overlayViewCreated = true
            bufferingOverlay = overlay
            ConnectionLog.playback("onCreateOverlayView: host requested buffering overlay")
            updateBufferingOverlayVisibility()
        }

    override fun onOverlayViewSizeChanged(
        width: Int,
        height: Int,
    ) {
        ConnectionLog.playback("onOverlayViewSizeChanged: ${width}x$height")
        if (width > 0 && height > 0) {
            updateBufferingOverlayVisibility()
        }
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
        playbackRetryCount = 0
        forceSoftwareVideoDecoders = false
        cancelFirstFrameWatchdog()
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
        setOverlayViewEnabled(true)
        notifyInitialLoad()
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
                    notifyInitialLoad()
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
                    startChannelPlayback(channelId, stream, reportStart = true)
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
            allowBeforeReady = player != null && !TifDeviceQuirks.deferSurfaceAttachUntilDecoderReady,
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
            attachVideoSurface(
                player,
                force = true,
                allowBeforeReady = !TifDeviceQuirks.deferSurfaceAttachUntilDecoderReady,
            )
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
        val deferForSony = TifDeviceQuirks.deferSurfaceAttachUntilDecoderReady
        if (deferForSony && !allowBeforeReady) {
            return
        }
        if (!allowBeforeReady && playbackState != Player.STATE_READY) return
        if (!force && attachedSurface === surface) return
        attachedSurface = surface
        exoPlayer?.setVideoSurface(surface)
    }



    private fun createDecoderAnalyticsListener(exoPlayer: ExoPlayer): AnalyticsListener =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                ConnectionLog.playback("video decoder initialized: $decoderName")
                attachVideoSurface(exoPlayer, force = true, allowBeforeReady = true)
            }
        }

    private fun createPlaybackListener(): Player.Listener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (isLoading) {
                    notifyPlaybackLoading()
                } else {
                    maybeMarkVideoAvailable()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> notifyPlaybackLoading()
                    Player.STATE_READY -> {
                        attachVideoSurface(player, force = true, allowBeforeReady = true)
                        maybeMarkVideoAvailable()
                    }
                    Player.STATE_ENDED -> {
                        hideBufferingOverlay()
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                publishTifTrackInfo(tracks)
            }

            override fun onRenderedFirstFrame() {
                if (firstFrameRendered) return
                firstFrameRendered = true
                cancelFirstFrameWatchdog()
                attachVideoSurface(
                    player,
                    force = true,
                    allowBeforeReady = true,
                )
                maybeMarkVideoAvailable()
            }
        }

    private fun buildMediaItem(stream: LiveTvStream): MediaItem =
        MediaItem
            .Builder()
            .setUri(stream.url.toUri())
            .apply {
                stream.mimeType?.let { setMimeType(it) }
            }.build()

    private fun startChannelPlayback(
        channelId: UUID,
        stream: LiveTvStream,
        reportStart: Boolean,
    ) {
        val exoPlayer =
            player ?: playerFactory.createTvInputPlayer(forceSoftwareVideoDecoders).also { created ->
                created.addListener(createPlaybackListener())
                created.addAnalyticsListener(createDecoderAnalyticsListener(created))
                player = created
            }
        if (!TifDeviceQuirks.deferSurfaceAttachUntilDecoderReady) {
            attachVideoSurface(exoPlayer, force = true, allowBeforeReady = true)
        }
        exoPlayer.setMediaItem(buildMediaItem(stream))
        playbackAnchorUtcMs = System.currentTimeMillis()
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        startFirstFrameWatchdog()
        notifyTimeShiftStatusChanged(
            if (timeshiftWindow?.isAvailable == true) {
                TvInputManager.TIME_SHIFT_STATUS_AVAILABLE
            } else {
                TvInputManager.TIME_SHIFT_STATUS_UNAVAILABLE
            },
        )
        if (reportStart) {
            sessionScope.launchIO {
                reportPlaybackStarted(channelId)
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (isRecoverableHlsError(error) && playbackRetryCount < MAX_PLAYBACK_RETRIES) {
            playbackRetryCount++
            ConnectionLog.playback(
                "recoverable playback error (${error.cause?.javaClass?.simpleName}), retry $playbackRetryCount/$MAX_PLAYBACK_RETRIES",
            )
            notifyInitialLoad()
            val channelId = currentChannelId ?: return
            sessionScope.launchIO {
                val stream =
                    streamHelper.getChannelStream(
                        channelId = channelId,
                        liveStreamId = null,
                        mediaSourceId = null,
                    )
                if (stream == null) {
                    ConnectionLog.playback("playback retry failed: could not resolve stream")
                    withContext(Dispatchers.Main) {
                        hideBufferingOverlay()
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                    return@launchIO
                }
                currentLiveStream = stream
                withContext(Dispatchers.Main) {
                    firstFrameRendered = false
                    videoAvailableNotified = false
                    startChannelPlayback(channelId, stream, reportStart = false)
                }
            }
            return
        }
        Timber.e(error, "TIF playback error")
        hideBufferingOverlay()
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
    }

    private fun isRecoverableHlsError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            when (cause.javaClass.simpleName) {
                "PlaylistStuckException",
                "PlaylistResetException",
                "BehindLiveWindowException" -> return true
            }
            cause = cause.cause
        }
        return false
    }

    private companion object {
        private const val MAX_PLAYBACK_RETRIES = 2
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    }

    private fun showBufferingOverlay() {
        bufferingOverlayVisible = true
        if (!overlayViewCreated) {
            setOverlayViewEnabled(true)
        }
        updateBufferingOverlayVisibility()
    }

    private fun hideBufferingOverlay() {
        bufferingOverlayVisible = false
        updateBufferingOverlayVisibility()
    }

    private fun updateBufferingOverlayVisibility() {
        bufferingOverlay?.visibility =
            if (bufferingOverlayVisible) View.VISIBLE else View.GONE
    }

    private fun notifyInitialLoad() {
        videoAvailableNotified = false
        ConnectionLog.playback("notifyVideoUnavailable: TUNING")
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        showBufferingOverlay()
    }

    private fun notifyRebuffering() {
        videoAvailableNotified = false
        ConnectionLog.playback("notifyVideoUnavailable: BUFFERING")
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
        showBufferingOverlay()
    }

    private fun notifyPlaybackLoading() {
        if (firstFrameRendered || videoAvailableNotified) {
            notifyRebuffering()
        } else {
            notifyInitialLoad()
        }
    }

    private fun startFirstFrameWatchdog() {
        cancelFirstFrameWatchdog()
        firstFrameWatchdogJob =
            sessionScope.launch {
                delay(FIRST_FRAME_TIMEOUT_MS)
                if (firstFrameRendered || videoAvailableNotified) return@launch
                if (forceSoftwareVideoDecoders) return@launch
                ConnectionLog.playback(
                    "no first frame within ${FIRST_FRAME_TIMEOUT_MS}ms, falling back to software decoder",
                )
                forceSoftwareVideoDecoders = true
                fallbackToSoftwareDecoder()
            }
    }

    private fun cancelFirstFrameWatchdog() {
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = null
    }

    private fun fallbackToSoftwareDecoder() {
        val channelId = currentChannelId ?: return
        val stream = currentLiveStream ?: return
        player?.release()
        player = null
        attachedSurface = null
        firstFrameRendered = false
        videoAvailableNotified = false
        notifyInitialLoad()
        startChannelPlayback(channelId, stream, reportStart = false)
    }

    private fun maybeMarkVideoAvailable() {
        if (videoAvailableNotified) return
        if (pendingSurface?.isValid != true) return
        val exoPlayer = player ?: return
        if (!firstFrameRendered) return
        if (exoPlayer.playbackState != Player.STATE_READY) return
        if (exoPlayer.isLoading) return
        markVideoAvailable()
    }

    private fun markVideoAvailable() {
        if (videoAvailableNotified) return
        if (pendingSurface?.isValid != true) return
        hideBufferingOverlay()
        publishContentAllowed(currentChannelUri)
        player?.currentTracks?.let { publishTifTrackInfo(it) }
        if (hostSurfaceWidth > 0 && hostSurfaceHeight > 0) {
            applySurfaceLayout(hostSurfaceWidth, hostSurfaceHeight, force = true)
        }
        videoAvailableNotified = true
        playbackRetryCount = 0
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
