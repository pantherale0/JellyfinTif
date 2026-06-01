package com.github.pantherale0.jellyfintif.services

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
internal class TifRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            built,
        )
        built.removeAll { it is MediaCodecVideoRenderer }
        out.add(
            TifMediaCodecVideoRenderer(
                context = context,
                mediaCodecSelector = mediaCodecSelector,
                allowedJoiningTimeMs = allowedVideoJoiningTimeMs,
                enableDecoderFallback = enableDecoderFallback,
                eventHandler = eventHandler,
                eventListener = eventListener,
                maxDroppedFramesToNotify = MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
        out.addAll(built)
    }
}
