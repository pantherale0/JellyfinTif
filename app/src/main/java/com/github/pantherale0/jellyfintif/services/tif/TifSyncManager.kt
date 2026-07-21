package com.github.pantherale0.jellyfintif.services.tif

import android.app.ActivityManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.ImageUrlService
import com.github.pantherale0.jellyfintif.di.AuthOkHttpClient
import com.github.pantherale0.jellyfintif.LiveTvConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Syncs Jellyfin Live TV channels and EPG into the Android system [TvContract] database.
 *
 * Designed for low-RAM Android TV devices: program fetches are batched per channel group,
 * only one sync runs at a time, and channel logos are downsampled before decode.
 */
@Singleton
class TifSyncManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val sessionRepository: SessionRepository,
        private val imageUrlService: ImageUrlService,
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val inputId = TifUtils.inputId(context)
        private val syncMutex = Mutex()

        suspend fun syncAll(): SyncResult =
            syncMutex.withLock {
                doSyncAll()
            }

        private suspend fun doSyncAll(): SyncResult {
            ConnectionLog.sync("syncAll starting")
            ConnectionLog.apiClient("sync", api)
            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                ConnectionLog.sync("syncAll aborted: API client not authenticated")
                return SyncResult.NotAuthenticated
            }
            val userId = sessionRepository.getUserId()
            if (userId == null) {
                ConnectionLog.sync("syncAll aborted: no user id in session")
                return SyncResult.NotAuthenticated
            }
            ConnectionLog.sync("syncAll for userId=$userId")

            val channelResult by
                api.liveTvApi.getLiveTvChannels(
                    GetLiveTvChannelsRequest(
                        startIndex = 0,
                        userId = userId,
                        enableFavoriteSorting = true,
                        addCurrentProgram = false,
                    ),
                )
            val jellyfinChannels = channelResult.items
            if (jellyfinChannels.isEmpty()) {
                clearChannels()
                return SyncResult.Success(channels = 0, programs = 0)
            }

            val lowRam = isLowRamDevice()
            val guideHours = TifSyncMemory.guideWindowHours(lowRam)
            ConnectionLog.sync(
                "syncAll channels=${jellyfinChannels.size} guideHours=$guideHours lowRam=$lowRam",
            )

            clearChannels()

            val channelOps = ArrayList<ContentProviderOperation>(jellyfinChannels.size)
            jellyfinChannels.forEach { channel ->
                val values =
                    ContentValues().apply {
                        put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                        put(TvContract.Channels.COLUMN_TYPE, TIF_CHANNEL_TYPE_LIVE)
                        put(
                            TvContract.Channels.COLUMN_DISPLAY_NAME,
                            channel.channelName ?: channel.name,
                        )
                        channel.channelNumber?.let {
                            put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, it)
                        }
                        put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, channel.id.toString())
                        put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, channel.id.hashCode())
                        put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, channel.id.hashCode())
                        put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    }
                channelOps.add(
                    ContentProviderOperation
                        .newInsert(TvContract.Channels.CONTENT_URI)
                        .withValues(values)
                        .build(),
                )
            }
            channelOps.chunked(CONTENT_BATCH_SIZE).forEach { batch ->
                context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(batch))
                yield()
            }

            val channelIdMap = loadChannelIdMap()
            val guideStart = LocalDateTime.now()
            val maxStartDate = guideStart.plusHours(guideHours)
            val minEndDate = guideStart.plusMinutes(1)
            var totalPrograms = 0

            // Fetch and insert programs in channel batches so the full guide is never held in heap.
            jellyfinChannels
                .chunked(LiveTvConstants.EPG_PROGRAM_CHANNEL_BATCH_SIZE)
                .forEachIndexed { batchIndex, channelBatch ->
                    val programs = fetchProgramsForChannels(
                        userId = userId,
                        channelIds = channelBatch.map { it.id },
                        maxStartDate = maxStartDate,
                        minEndDate = minEndDate,
                    )
                    totalPrograms += insertPrograms(programs, channelIdMap)
                    ConnectionLog.sync(
                        "syncAll program batch ${batchIndex + 1}: ${programs.size} programs " +
                            "(channels ${channelBatch.size})",
                    )
                    yield()
                }

            var logosSet = 0
            jellyfinChannels.forEach { channel ->
                val rowId = channelIdMap[channel.id] ?: return@forEach
                val channelUri = TvContract.buildChannelUri(rowId)
                if (setChannelLogo(channelUri, channel.id)) {
                    logosSet++
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    TvContract.requestChannelBrowsable(context, rowId)
                }
                yield()
            }

            Timber.i(
                "TIF sync complete: %s channels, %s programs, %s logos",
                jellyfinChannels.size,
                totalPrograms,
                logosSet,
            )
            return SyncResult.Success(
                channels = jellyfinChannels.size,
                programs = totalPrograms,
            )
        }

        private suspend fun fetchProgramsForChannels(
            userId: UUID,
            channelIds: List<UUID>,
            maxStartDate: LocalDateTime,
            minEndDate: LocalDateTime,
        ): List<BaseItemDto> {
            val programsResult by
                api.liveTvApi.getPrograms(
                    GetProgramsDto(
                        maxStartDate = maxStartDate,
                        minEndDate = minEndDate,
                        channelIds = channelIds,
                        sortBy = listOf(ItemSortBy.START_DATE),
                        userId = userId,
                        // Overview is truncated before insert; still needed for guide descriptions.
                        fields = listOf(ItemFields.OVERVIEW),
                    ),
                )
            return programsResult.items.filter {
                it.channelId != null && it.startDate != null && it.endDate != null
            }
        }

        private suspend fun insertPrograms(
            programs: List<BaseItemDto>,
            channelIdMap: Map<UUID, Long>,
        ): Int {
            if (programs.isEmpty()) return 0
            var inserted = 0
            val programOps = ArrayList<ContentProviderOperation>(programs.size.coerceAtMost(CONTENT_BATCH_SIZE))
            programs.forEach { program ->
                val channelRowId = channelIdMap[program.channelId] ?: return@forEach
                val startMs =
                    program.startDate!!
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val endMs =
                    program.endDate!!
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val description = TifSyncMemory.truncateDescription(program.overview)
                val values =
                    ContentValues().apply {
                        put(TvContract.Programs.COLUMN_CHANNEL_ID, channelRowId)
                        put(TvContract.Programs.COLUMN_TITLE, program.name ?: program.seriesName)
                        // Store once: duplicating SHORT+LONG with the same overview doubles TvProvider RAM.
                        if (description != null) {
                            put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, description)
                        }
                        put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, startMs)
                        put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, endMs)
                        put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA, program.id.toString())
                    }
                programOps.add(
                    ContentProviderOperation
                        .newInsert(TvContract.Programs.CONTENT_URI)
                        .withValues(values)
                        .build(),
                )
                inserted++
                if (programOps.size >= CONTENT_BATCH_SIZE) {
                    context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(programOps))
                    programOps.clear()
                    yield()
                }
            }
            if (programOps.isNotEmpty()) {
                context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(programOps))
                yield()
            }
            return inserted
        }

        private fun isLowRamDevice(): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return am?.isLowRamDevice == true
        }

        private fun clearChannels() {
            context.contentResolver.delete(
                TvContract.buildChannelsUriForInput(inputId),
                null,
                null,
            )
        }

        private fun loadChannelIdMap(): Map<UUID, Long> {
            val map = mutableMapOf<UUID, Long>()
            context.contentResolver
                .query(
                    TvContract.buildChannelsUriForInput(inputId),
                    arrayOf(
                        BaseColumns._ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                    val dataIndex =
                        cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    while (cursor.moveToNext()) {
                        val providerData = cursor.getString(dataIndex) ?: continue
                        val jellyfinId =
                            runCatching { UUID.fromString(providerData) }.getOrNull() ?: continue
                        map[jellyfinId] = cursor.getLong(idIndex)
                    }
                }
            return map
        }

        private suspend fun setChannelLogo(
            channelUri: Uri,
            channelId: UUID,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val maxPx = LiveTvConstants.CHANNEL_LOGO_MAX_PX
                val imageUrl =
                    imageUrlService.getItemImageUrl(
                        itemId = channelId,
                        imageType = ImageType.PRIMARY,
                        maxWidth = maxPx,
                        maxHeight = maxPx,
                        quality = 85,
                    ) ?: return@withContext false
                runCatching {
                    val request = Request.Builder().url(imageUrl).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val bytes = response.body.bytes()
                        val bounds =
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val decodeOptions =
                            BitmapFactory.Options().apply {
                                inSampleSize =
                                    TifSyncMemory.calculateInSampleSize(
                                        bounds.outWidth,
                                        bounds.outHeight,
                                        maxPx,
                                        maxPx,
                                    )
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        val bitmap =
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                                ?: return@use false
                        try {
                            val logoUri = TvContract.buildChannelLogoUri(channelUri)
                            try {
                                context.contentResolver.openOutputStream(logoUri)?.use { output ->
                                    // JPEG is far smaller than PNG-100 for logos in TvProvider.
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                                } ?: return@use false
                            } catch (_: java.io.FileNotFoundException) {
                                Timber.w("TIF logo file not found, skipping logo for %s", channelId)
                                return@use false
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    true
                }.onFailure { Timber.w(it, "Failed to set channel logo for %s", channelId) }
                    .getOrDefault(false)
            }

        sealed interface SyncResult {
            data object NotAuthenticated : SyncResult

            data class Success(
                val channels: Int,
                val programs: Int,
            ) : SyncResult

            data class Error(
                val message: String,
            ) : SyncResult
        }

        private companion object {
            private const val CONTENT_BATCH_SIZE = 100
        }
    }

/** [TvContract.Channels.TYPE_OTHER] is recommended for streaming-based channels. */
private val TIF_CHANNEL_TYPE_LIVE: String
    get() = TvContract.Channels.TYPE_OTHER
