package com.github.pantherale0.jellyfintif.services.tif

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.pantherale0.jellyfintif.data.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import timber.log.Timber
import java.util.UUID
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

        fun enqueueSync(): UUID? {
            ConnectionLog.sync("enqueueSync requested")
            if (!supportsTif) {
                ConnectionLog.sync("enqueueSync aborted: device lacks leanback feature")
                return null
            }
            return runBlocking {
                if (!sessionRepository.restoreSession()) {
                    ConnectionLog.sync("enqueueSync aborted: session restore failed")
                    return@runBlocking null
                }
                val userId = sessionRepository.getUserId()
                if (userId == null) {
                    ConnectionLog.sync("enqueueSync aborted: no user id")
                    return@runBlocking null
                }
                val serverId = sessionRepository.getServerId()
                if (serverId == null) {
                    ConnectionLog.sync("enqueueSync aborted: no server id")
                    return@runBlocking null
                }
                val request =
                    OneTimeWorkRequestBuilder<EpgSyncWorker>()
                        .setInputData(
                            workDataOf(
                                EpgSyncWorker.PARAM_USER_ID to userId.toString(),
                                EpgSyncWorker.PARAM_SERVER_ID to serverId.toString(),
                            ),
                        ).build()
                workManager.enqueue(request)
                ConnectionLog.sync("enqueueSync scheduled workId=${request.id} userId=$userId serverId=$serverId")
                request.id
            }
        }
    }
