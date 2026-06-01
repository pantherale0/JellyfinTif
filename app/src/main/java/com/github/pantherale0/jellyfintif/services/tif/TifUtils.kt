package com.github.pantherale0.jellyfintif.services.tif

import android.content.ComponentName
import android.content.Context
import android.media.tv.TvContract

object TifUtils {
    fun inputId(context: Context): String =
        TvContract.buildInputId(
            ComponentName(context, JellyfinTvInputService::class.java),
        )
}
