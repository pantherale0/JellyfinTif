package com.github.pantherale0.jellyfintif.services

import android.content.Context
import com.github.pantherale0.jellyfintif.LiveTvConstants
import com.github.pantherale0.jellyfintif.util.profile.MediaCodecCapabilitiesTest
import com.github.pantherale0.jellyfintif.util.profile.createDeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.DeviceProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceProfileService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val mediaCodecCapabilitiesTest by lazy {
            MediaCodecCapabilitiesTest(context)
        }
        private val mutex = Mutex()
        private var cachedProfile: DeviceProfile? = null
        private var cachedServerVersion: ServerVersion? = null

        suspend fun getOrCreateDeviceProfile(serverVersion: ServerVersion? = null): DeviceProfile =
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    val jellyfinTenEleven =
                        serverVersion != null && serverVersion >= ServerVersion(10, 11, 0)
                    if (cachedProfile == null || cachedServerVersion != serverVersion) {
                        cachedServerVersion = serverVersion
                        cachedProfile =
                            createDeviceProfile(
                                mediaTest = mediaCodecCapabilitiesTest,
                                maxBitrate = LiveTvConstants.DEFAULT_BITRATE,
                                isAC3Enabled = true,
                                downMixAudio = false,
                                assDirectPlay = false,
                                pgsDirectPlay = false,
                                dolbyVisionELDirectPlay = false,
                                decodeAv1 = false,
                                jellyfinTenEleven = jellyfinTenEleven,
                            )
                    }
                    cachedProfile!!
                }
            }
    }
