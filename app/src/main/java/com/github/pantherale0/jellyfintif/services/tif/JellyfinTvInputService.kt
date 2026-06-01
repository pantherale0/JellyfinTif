package com.github.pantherale0.jellyfintif.services.tif

import android.media.tv.TvInputService
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.TifPlayerFactory
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.api.client.ApiClient
import javax.inject.Inject

@AndroidEntryPoint
class JellyfinTvInputService : TvInputService() {
    @Inject
    lateinit var playerFactory: TifPlayerFactory

    @Inject
    lateinit var streamHelper: TifStreamHelper

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var api: ApiClient

    override fun onCreateSession(inputId: String): Session =
        JellyfinTvSession(
            serviceContext = this,
            inputId = inputId,
            playerFactory = playerFactory,
            streamHelper = streamHelper,
            sessionRepository = sessionRepository,
            api = api,
        )
}
