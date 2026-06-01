package com.github.pantherale0.jellyfintif.services.tif

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.pantherale0.jellyfintif.data.SessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

/**
 * Background worker that syncs Jellyfin Live TV data into the system TV guide.
 */
@HiltWorker
class EpgSyncWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted workerParams: WorkerParameters,
        private val sessionRepository: SessionRepository,
        private val api: ApiClient,
        private val tifSyncManager: TifSyncManager,
    ) : CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            Timber.d("EpgSyncWorker starting")
            val serverId =
                inputData.getString(PARAM_SERVER_ID)?.toUUIDOrNull() ?: return Result.failure()
            val userId =
                inputData.getString(PARAM_USER_ID)?.toUUIDOrNull() ?: return Result.failure()

            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                if (!sessionRepository.restoreSession()) {
                    Timber.w("EpgSyncWorker: no authenticated user")
                    return Result.failure()
                }
            }

            return try {
                when (val syncResult = tifSyncManager.syncAll()) {
                    is TifSyncManager.SyncResult.NotAuthenticated -> Result.failure()
                    is TifSyncManager.SyncResult.Error -> Result.failure()
                    is TifSyncManager.SyncResult.Success -> {
                        Timber.d(
                            "EpgSyncWorker synced %s channels, %s programs",
                            syncResult.channels,
                            syncResult.programs,
                        )
                        Result.success()
                    }
                }
            } catch (_: ApiClientException) {
                Result.retry()
            } catch (ex: Exception) {
                Timber.e(ex, "EpgSyncWorker failed")
                Result.failure()
            }
        }

        companion object {
            const val WORK_NAME = "com.github.pantherale0.jellyfintif.services.tif.EpgSyncWorker"
            const val PARAM_USER_ID = "userId"
            const val PARAM_SERVER_ID = "serverId"
        }
    }
