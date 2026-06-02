package com.github.pantherale0.jellyfintif.services

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.github.pantherale0.jellyfintif.services.tif.TifDeviceQuirks
import kotlin.math.abs

/**
 * TIF-tuned video renderer for Sony/MTK Android TVs.
 *
 * Applies workarounds from androidx/media#2941 and roboTV#44: avoid broken surface updates on
 * MTK hardware decoders and recreate the codec when stream format changes during channel zaps.
 */
@UnstableApi
internal class TifMediaCodecVideoRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    Builder(context)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify),
) {
    @Throws(MediaCodecUtil.DecoderQueryException::class)
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        if (!TifDeviceQuirks.preferSoftwareVideoDecoders) {
            return infos.filterNot { TifDeviceQuirks.isMtkVideoCodec(it.name) }
        }
        return infos.filter { info -> isTifSafeSoftwareDecoder(info.name) }
    }

    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
        super.codecNeedsSetOutputSurfaceWorkaround(name) ||
            TifDeviceQuirks.needsSetOutputSurfaceWorkaround(name)

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean,
    ): DecoderReuseEvaluation {
        val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)
        if (!TifDeviceQuirks.isMtkVideoCodec(codecInfo.name)) {
            return evaluation
        }
        val forcedReason = mtkForcedDiscardReason(oldFormat, newFormat)
        if (forcedReason == null) {
            return evaluation
        }
        return DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            forcedReason,
        )
    }

    private fun mtkForcedDiscardReason(
        oldFormat: Format,
        newFormat: Format,
    ): @DecoderReuseEvaluation.DecoderDiscardReasons Int? {
        if (oldFormat.width != newFormat.width || oldFormat.height != newFormat.height) {
            return DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE
        }
        val oldFrameRate = oldFormat.frameRate
        val newFrameRate = newFormat.frameRate
        if (oldFrameRate > 0f && newFrameRate > 0f) {
            val percentChange = abs(newFrameRate - oldFrameRate) / oldFrameRate * 100f
            if (percentChange > FRAME_RATE_RECREATE_THRESHOLD_PERCENT) {
                return DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE
            }
        }
        val oldColor = oldFormat.colorInfo
        val newColor = newFormat.colorInfo
        if (oldColor != null && newColor != null) {
            if (oldColor.colorSpace != newColor.colorSpace ||
                oldColor.colorRange != newColor.colorRange ||
                oldColor.colorTransfer != newColor.colorTransfer
            ) {
                return DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE
            }
        }
        return null
    }

    private fun isTifSafeSoftwareDecoder(name: String): Boolean =
        !TifDeviceQuirks.isMtkVideoCodec(name) &&
            !name.contains("amlogic", ignoreCase = true) &&
            (
                name.startsWith("OMX.google.", ignoreCase = true) ||
                    name.startsWith("c2.android.", ignoreCase = true) ||
                    name.contains("google", ignoreCase = true)
            )

    private companion object {
        private const val FRAME_RATE_RECREATE_THRESHOLD_PERCENT = 10f
    }
}
