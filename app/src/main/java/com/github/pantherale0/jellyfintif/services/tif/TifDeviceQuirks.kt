package com.github.pantherale0.jellyfintif.services.tif

import android.os.Build

/**
 * Device-specific playback quirks for Android TV Input Framework hosts.
 *
 * Cross-referenced issues:
 * - [roboTV #44](https://github.com/pipelka/roboTV/issues/44): Sony TIF + tunneled MTK playback breaks
 * - [ExoPlayer #3790](https://github.com/google/ExoPlayer/issues/3790): Tunneling distorts MTK output
 * - [androidx/media #2941](https://github.com/androidx/media/issues/2941): Sony MTK needs codec recreation on format changes
 * - [ExoPlayer #8329](https://github.com/google/ExoPlayer/issues/8329): setOutputSurface broken on some TVs
 */
object TifDeviceQuirks {
    val isSonyBraviaTv: Boolean by lazy {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        manufacturer.contains("sony", ignoreCase = true) ||
            model.contains("BRAVIA", ignoreCase = true) ||
            device.contains("BRAVIA", ignoreCase = true)
    }

    /**
     * Media3 only enables the setOutputSurface workaround for [Build.DEVICE] values such as
     * BRAVIA_ATV2 on API 26 and below. Newer Sony sets (e.g. BRAVIA_UR2_4K on API 29) are not
     * covered, yet still fail DynamicANWBuffer setup to the TIF-provided Surface.
     */
    fun needsSetOutputSurfaceWorkaround(codecName: String): Boolean {
        if (codecName.startsWith("OMX.google", ignoreCase = true) ||
            codecName.startsWith("c2.android.", ignoreCase = true)
        ) {
            return false
        }
        return isSonyBraviaTv && isMtkVideoCodec(codecName)
    }

    fun isMtkVideoCodec(codecName: String): Boolean =
        codecName.contains("MTK", ignoreCase = true) ||
            codecName.contains("mtk", ignoreCase = true)

    /**
     * Sony Live Channels often attaches or resizes the host Surface while tuning. Defer binding
     * the decoder output until playback is ready or the decoder has been initialized.
     */
    val deferSurfaceAttachUntilDecoderReady: Boolean
        get() = isSonyBraviaTv
}
