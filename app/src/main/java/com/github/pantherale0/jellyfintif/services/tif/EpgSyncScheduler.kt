package com.github.pantherale0.jellyfintif.services.tif

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.pantherale0.jellyfintif.LiveTvConstants
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues [EpgSyncWorker] to refresh system Live TV guide data from Jellyfin.
 */
@Singleton
class EpgSyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val sessionRepository: SessionRepository,
        private val workManager: WorkManager,
    ) {
        val supportsTif: Boolean
            get() =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        suspend fun enqueueSync(): UUID? {
            ConnectionLog.sync("enqueueSync requested")
            val syncContext = resolveSyncContext() ?: return null
            val request =
                OneTimeWorkRequestBuilder<EpgSyncWorker>()
                    .setInputData(syncContext.inputData)
                    .setConstraints(syncConstraints)
                    .build()
            // Unique work prevents stacked one-shots (boot + setup) from running concurrent syncs
            // that each hold a full EPG payload in memory on low-RAM TVs.
            workManager.enqueueUniqueWork(
                EpgSyncWorker.ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            ensurePeriodicSyncScheduled(syncContext)
            ConnectionLog.sync(
                "enqueueSync scheduled workId=${request.id} userId=${syncContext.userId} serverId=${syncContext.serverId}",
            )
            return request.id
        }

        /**
         * Registers a periodic WorkManager job so guide data stays fresh after initial setup.
         * Safe to call repeatedly; existing work is updated in place.
         */
        suspend fun ensurePeriodicSyncScheduled(): Boolean {
            val syncContext = resolveSyncContext() ?: return false
            return ensurePeriodicSyncScheduled(syncContext)
        }

        private suspend fun resolveSyncContext(): SyncContext? {
            if (!supportsTif) {
                ConnectionLog.sync("sync aborted: device lacks leanback feature")
                return null
            }
            if (!sessionRepository.restoreSession()) {
                ConnectionLog.sync("sync aborted: session restore failed")
                return null
            }
            val userId = sessionRepository.getUserId()
            if (userId == null) {
                ConnectionLog.sync("sync aborted: no user id")
                return null
            }
            val serverId = sessionRepository.getServerId()
            if (serverId == null) {
                ConnectionLog.sync("sync aborted: no server id")
                return null
            }
            return SyncContext(
                userId = userId,
                serverId = serverId,
                inputData =
                    workDataOf(
                        EpgSyncWorker.PARAM_USER_ID to userId.toString(),
                        EpgSyncWorker.PARAM_SERVER_ID to serverId.toString(),
                    ),
            )
        }

        private fun ensurePeriodicSyncScheduled(syncContext: SyncContext): Boolean {
            val request =
                PeriodicWorkRequestBuilder<EpgSyncWorker>(
                    LiveTvConstants.EPG_SYNC_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                ).setInputData(syncContext.inputData)
                    .setConstraints(syncConstraints)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                EpgSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            ConnectionLog.sync(
                "ensurePeriodicSyncScheduled: every ${LiveTvConstants.EPG_SYNC_INTERVAL_HOURS}h " +
                    "userId=${syncContext.userId} serverId=${syncContext.serverId}",
            )
            return true
        }

        private data class SyncContext(
            val userId: UUID,
            val serverId: UUID,
            val inputData: Data,
        )

        private companion object {
            private val syncConstraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
        }
    }
