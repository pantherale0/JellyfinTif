package com.github.pantherale0.jellyfintif.services

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.ImageType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageUrlService
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        fun getItemImageUrl(
            itemId: UUID,
            imageType: ImageType,
            maxWidth: Int? = null,
            maxHeight: Int? = null,
            quality: Int = 90,
        ): String? {
            if (api.baseUrl.isNullOrBlank()) return null
            return api.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = imageType,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                quality = quality,
            )
        }
    }
