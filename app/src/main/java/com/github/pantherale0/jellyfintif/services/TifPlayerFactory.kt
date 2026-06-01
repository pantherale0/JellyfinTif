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
            private val TV_INPUT_MEDIA_CODEC_SELECTOR =
                MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                    val infos =
                        MediaCodecSelector.DEFAULT.getDecoderInfos(
                            mimeType,
                            requiresSecure,
                            requiresTunneling,
                        )
                    if (!mimeType.startsWith("video/")) {
                        return@MediaCodecSelector infos
                    }
                    infos.sortedWith(
                        compareBy<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> { info ->
                            when {
                                info.softwareOnly -> 2
                                !info.hardwareAccelerated -> 1
                                else -> 0
                            }
                        }.thenBy { info ->
                            when {
                                info.name.startsWith("OMX.google.", ignoreCase = true) -> 1
                                info.name.startsWith("c2.android.", ignoreCase = true) -> 1
                                else -> 0
                            }
                        },
                    )
                }
        }
    }
