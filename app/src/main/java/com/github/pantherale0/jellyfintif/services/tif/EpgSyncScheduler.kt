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
            if (!supportsTif) {
                Timber.w("TIF sync not supported on this device")
                return null
            }
            return runBlocking {
                if (!sessionRepository.restoreSession()) return@runBlocking null
                val userId = sessionRepository.getUserId() ?: return@runBlocking null
                val serverId = sessionRepository.getServerId() ?: return@runBlocking null
                val request =
                    OneTimeWorkRequestBuilder<EpgSyncWorker>()
                        .setInputData(
                            workDataOf(
                                EpgSyncWorker.PARAM_USER_ID to userId.toString(),
                                EpgSyncWorker.PARAM_SERVER_ID to serverId.toString(),
                            ),
                        ).build()
                workManager.enqueue(request)
                request.id
            }
        }
    }
