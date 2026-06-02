package com.github.pantherale0.jellyfintif.services

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.github.pantherale0.jellyfintif.di.AuthOkHttpClient
import com.github.pantherale0.jellyfintif.services.tif.TifDeviceQuirks
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class TifPlayerFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
    ) {
        fun createTvInputPlayer(
            forceSoftwareVideoDecoders: Boolean = TifDeviceQuirks.preferSoftwareVideoDecoders,
        ): ExoPlayer {
            val extractorsFactory = createTvInputExtractorsFactory()
            val ffmpegAvailable =
                runCatching {
                    Class.forName("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer")
                }.isSuccess
            val extensionMode =
                if (ffmpegAvailable) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            val codecSelector =
                if (forceSoftwareVideoDecoders) {
                    TV_INPUT_SOFTWARE_VIDEO_CODEC_SELECTOR
                } else {
                    TV_INPUT_HARDWARE_CODEC_SELECTOR
                }
            val renderersFactory =
                TifRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(extensionMode)
                    .setMediaCodecSelector(codecSelector)
            val mediaSourceFactory =
                TifMediaSourceFactory(
                    OkHttpDataSource.Factory(authOkHttpClient),
                    extractorsFactory,
                )
            return ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(createTvInputTrackSelector())
                .build()
                .also {
                    it.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        false,
                    )
                    it.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                }
        }

        private fun createTvInputExtractorsFactory() =
            DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

        /**
         * Tunneling must stay disabled for Sony TIF hosts. roboTV#44 and ExoPlayer#3790 show tunneled
         * MTK playback breaks audio and/or renders via the VDP plane instead of the app Surface.
         */
        private fun createTvInputTrackSelector() =
            DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setTunnelingEnabled(false)
                        .setAudioOffloadPreferences(
                            AudioOffloadPreferences
                                .Builder()
                                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                                .build(),
                        ),
                )
            }

        companion object {
            private val TIF_PREFERRED_SOFTWARE_VIDEO_DECODERS =
                listOf(
                    "c2.android.avc.decoder",
                    "OMX.google.h264.decoder",
                    "c2.android.hevc.decoder",
                    "OMX.google.hevc.decoder",
                )

            /**
             * Prefer hardware decoders. Sony/MTK workarounds in [TifMediaCodecVideoRenderer] and
             * [TifDeviceQuirks] address surface and codec-reuse bugs (androidx/media#2941).
             */
            private val TV_INPUT_HARDWARE_CODEC_SELECTOR =
                MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                    if (!mimeType.startsWith("video/")) {
                        return@MediaCodecSelector MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            requiresSecure,
                            requiresTunneling,
                        )
                    }
                    val infos =
                        MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            /* requiresSecure= */ false,
                            /* requiresTunneling= */ false,
                        )
                    infos
                        .filter { info ->
                            !info.name.contains("secure", ignoreCase = true)
                        }.sortedWith(
                            compareBy<MediaCodecInfo> { info ->
                                when {
                                    info.softwareOnly -> 2
                                    !info.hardwareAccelerated -> 1
                                    else -> 0
                                }
                            },
                        )
                }

            private val TV_INPUT_SOFTWARE_VIDEO_CODEC_SELECTOR =
                MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                    if (!mimeType.startsWith("video/")) {
                        return@MediaCodecSelector MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            requiresSecure,
                            requiresTunneling,
                        )
                    }
                    selectSoftwareVideoDecoders(
                        MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            false,
                            false,
                        ),
                    )
                }

            private fun selectSoftwareVideoDecoders(infos: List<MediaCodecInfo>): List<MediaCodecInfo> {
                val preferred =
                    TIF_PREFERRED_SOFTWARE_VIDEO_DECODERS.mapNotNull { name ->
                        infos.find { it.name == name }
                    }
                if (preferred.isNotEmpty()) {
                    return preferred
                }
                val software =
                    infos.filter { info ->
                        info.softwareOnly || !info.hardwareAccelerated
                    }
                if (software.isNotEmpty()) {
                    return software
                }
                return infos.filter { info ->
                    !info.name.contains("secure", ignoreCase = true) &&
                        !TifDeviceQuirks.isMtkVideoCodec(info.name)
                }
            }
        }
    }
