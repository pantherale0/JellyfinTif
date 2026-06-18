package com.github.pantherale0.jellyfintif.services.tif

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * Toggles [JellyfinTvInputService] so TvInputManager drops stale DEAD bindings after APK updates.
 */
internal object TifInputServiceReset {
    fun requestRebind(context: Context) {
        val component =
            ComponentName(
                context,
                JellyfinTvInputService::class.java,
            )
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            Timber.i("TIF service component toggled for rebind")
        }.onFailure { error ->
            Timber.w(error, "TIF service rebind toggle failed")
        }
    }
}
