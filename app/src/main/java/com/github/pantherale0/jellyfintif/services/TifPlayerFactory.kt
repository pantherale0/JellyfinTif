package com.github.pantherale0.jellyfintif.services

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.github.pantherale0.jellyfintif.di.AuthOkHttpClient
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
        fun createTvInputPlayer(): ExoPlayer {
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
            val renderersFactory: RenderersFactory =
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(extensionMode)
                    .setMediaCodecSelector(TV_INPUT_MEDIA_CODEC_SELECTOR)
            val mediaSourceFactory =
                DefaultMediaSourceFactory(
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
            private val TIF_PREFERRED_VIDEO_DECODERS =
                listOf(
                    "c2.android.avc.decoder",
                    "OMX.google.h264.decoder",
                    "c2.android.hevc.decoder",
                    "OMX.google.hevc.decoder",
                )

            private val TV_INPUT_MEDIA_CODEC_SELECTOR =
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
                            false,
                            false,
                        )
                    val preferred =
                        TIF_PREFERRED_VIDEO_DECODERS.mapNotNull { name ->
                            infos.find { it.name == name }
                        }
                    if (preferred.isNotEmpty()) {
                        return@MediaCodecSelector preferred
                    }
                    val software =
                        infos.filter { info ->
                            info.softwareOnly || !info.hardwareAccelerated
                        }
                    if (software.isNotEmpty()) {
                        return@MediaCodecSelector software
                    }
                    val filtered =
                        infos.filter { info ->
                            !info.name.contains("secure", ignoreCase = true) &&
                                !info.name.contains("MTK", ignoreCase = true) &&
                                !info.name.contains("amlogic", ignoreCase = true)
                        }
                    filtered.ifEmpty { infos }
                }
        }
    }
