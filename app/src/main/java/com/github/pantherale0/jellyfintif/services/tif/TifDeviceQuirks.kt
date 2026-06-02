package com.github.pantherale0.jellyfintif.services.tif

import android.os.Build

/**
 * Device-specific playback quirks for Android TV Input Framework hosts.
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

    /** MTK hardware decoders do not render to the Sony TIF Surface reliably. */
    val preferSoftwareVideoDecoders: Boolean
        get() = isSonyBraviaTv

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
}
