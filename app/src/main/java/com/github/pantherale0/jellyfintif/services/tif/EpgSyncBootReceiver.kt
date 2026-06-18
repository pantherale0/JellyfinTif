package com.github.pantherale0.jellyfintif.services.tif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-registers periodic EPG sync after reboot or app update so the guide keeps refreshing
 * even when the setup activity is never opened again.
 */
@AndroidEntryPoint
class EpgSyncBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var epgSyncScheduler: EpgSyncScheduler

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ConnectionLog.sync("EpgSyncBootReceiver: ${intent.action}")
                val workId = epgSyncScheduler.enqueueSync()
                ConnectionLog.sync("EpgSyncBootReceiver: EPG sync workId=$workId")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
