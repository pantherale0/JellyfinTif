package com.github.pantherale0.jellyfintif.services.tif

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
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Syncs Jellyfin Live TV channels and EPG into the Android system [TvContract] database.
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

        suspend fun syncAll(): SyncResult {
            if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                return SyncResult.NotAuthenticated
            }
            val userId = sessionRepository.getUserId() ?: return SyncResult.NotAuthenticated

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

            val guideStart = LocalDateTime.now()
            val maxStartDate = guideStart.plusHours(LiveTvConstants.MAX_HOURS)
            val minEndDate = guideStart.plusMinutes(1)
            val programsResult by
                api.liveTvApi.getPrograms(
                    GetProgramsDto(
                        maxStartDate = maxStartDate,
                        minEndDate = minEndDate,
                        channelIds = jellyfinChannels.map { it.id },
                        sortBy = listOf(ItemSortBy.START_DATE),
                        userId = userId,
                        fields = listOf(ItemFields.OVERVIEW),
                    ),
                )
            val programs =
                programsResult.items.filter {
                    it.channelId != null && it.startDate != null && it.endDate != null
                }

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
            channelOps.chunked(100).forEach { batch ->
                context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(batch))
                yield()
            }

            val channelIdMap = loadChannelIdMap()
            val programOps = ArrayList<ContentProviderOperation>(programs.size)
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
                val values =
                    ContentValues().apply {
                        put(TvContract.Programs.COLUMN_CHANNEL_ID, channelRowId)
                        put(TvContract.Programs.COLUMN_TITLE, program.name ?: program.seriesName)
                        put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, program.overview)
                        put(TvContract.Programs.COLUMN_LONG_DESCRIPTION, program.overview)
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
            }
            if (programOps.isNotEmpty()) {
                programOps.chunked(100).forEach { batch ->
                    context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(batch))
                    yield() // Yield to prevent bogging down the system TvProvider and Launcher
                }
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
                yield() // Yield between processing heavy logos
            }

            Timber.i(
                "TIF sync complete: %s channels, %s programs, %s logos",
                jellyfinChannels.size,
                programs.size,
                logosSet,
            )
            return SyncResult.Success(
                channels = jellyfinChannels.size,
                programs = programs.size,
            )
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
        ): Boolean = withContext(Dispatchers.IO) {
            val imageUrl =
                imageUrlService.getItemImageUrl(channelId, ImageType.PRIMARY) ?: return@withContext false
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val bytes = response.body?.bytes() ?: return@use false
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use false
                    
                    val logoUri = TvContract.buildChannelLogoUri(channelUri)
                    try {
                        context.contentResolver.openOutputStream(logoUri)?.use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        } ?: return@use false
                    } catch (_: java.io.FileNotFoundException) {
                        Timber.w("TIF logo file not found, skipping logo for %s", channelId)
                        return@use false
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
    }

/** [TvContract.Channels.TYPE_OTHER] is recommended for streaming-based channels. */
private val TIF_CHANNEL_TYPE_LIVE: String
    get() = TvContract.Channels.TYPE_OTHER
