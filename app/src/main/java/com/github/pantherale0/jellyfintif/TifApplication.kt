package com.github.pantherale0.jellyfintif

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class TifApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionRepository: SessionRepository

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(
                object : Timber.Tree() {
                    override fun isLoggable(
                        tag: String?,
                        priority: Int,
                    ): Boolean = priority >= Log.INFO

                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        Log.println(priority, tag ?: "JellyfinTif", message)
                    }
                },
            )
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ConnectionLog.session("application startup: attempting session restore")
            val restored = sessionRepository.restoreSession()
            ConnectionLog.session("application startup: session restore success=$restored")
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
