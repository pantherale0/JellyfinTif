package com.github.pantherale0.jellyfintif.services.tif

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

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
            val serverId =
                inputData.getString(PARAM_SERVER_ID)?.toUUIDOrNull()
            val userId =
                inputData.getString(PARAM_USER_ID)?.toUUIDOrNull()
            if (serverId == null || userId == null) {
                ConnectionLog.sync("EpgSyncWorker aborted: missing input data (serverId=$serverId userId=$userId)")
                return Result.failure()
            }
            ConnectionLog.sync("EpgSyncWorker starting serverId=$serverId userId=$userId")
            ConnectionLog.apiClient("epg.worker", api)

            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                ConnectionLog.sync("EpgSyncWorker: ApiClient not configured, attempting session restore")
                if (!sessionRepository.restoreSession()) {
                    ConnectionLog.sync("EpgSyncWorker aborted: session restore failed")
                    return Result.failure()
                }
            }

            return try {
                when (val syncResult = tifSyncManager.syncAll()) {
                    is TifSyncManager.SyncResult.NotAuthenticated -> {
                        ConnectionLog.sync("EpgSyncWorker failed: not authenticated")
                        Result.failure()
                    }
                    is TifSyncManager.SyncResult.Error -> {
                        ConnectionLog.sync("EpgSyncWorker failed: ${syncResult.message}")
                        Result.failure()
                    }
                    is TifSyncManager.SyncResult.Success -> {
                        ConnectionLog.sync(
                            "EpgSyncWorker complete: ${syncResult.channels} channels, ${syncResult.programs} programs",
                        )
                        Result.success()
                    }
                }
            } catch (ex: ApiClientException) {
                ConnectionLog.sync("EpgSyncWorker API error, will retry", ex)
                Result.retry()
            } catch (ex: Exception) {
                ConnectionLog.sync("EpgSyncWorker failed", ex)
                Result.failure()
            }
        }

        companion object {
            const val WORK_NAME = "com.github.pantherale0.jellyfintif.services.tif.EpgSyncWorker"
            const val ONE_SHOT_WORK_NAME = "$WORK_NAME.oneshot"
            const val PARAM_USER_ID = "userId"
            const val PARAM_SERVER_ID = "serverId"
        }
    }
