package com.github.pantherale0.jellyfintif.di

import android.content.Context
import androidx.work.WorkManager
import com.github.pantherale0.jellyfintif.BuildConfig
import com.github.pantherale0.jellyfintif.R
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clientInfo(
        @ApplicationContext context: Context,
    ): ClientInfo =
        ClientInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
        )

    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val started = System.nanoTime()
                        try {
                            val response = chain.proceed(request)
                            ConnectionLog.http(
                                request.method,
                                request.url.toString(),
                                response.code,
                                (System.nanoTime() - started) / 1_000_000,
                            )
                            response
                        } catch (ex: Exception) {
                            ConnectionLog.httpFailure(request.method, request.url.toString(), ex)
                            throw ex
                        }
                    }
                }
            }.build()

    @AuthOkHttpClient
    @Provides
    @Singleton
    fun authOkHttpClient(
        apiClient: ApiClient,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ): OkHttpClient =
        okHttpClient
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = apiClient.accessToken
                val newRequest =
                    token?.let { accessToken ->
                        request
                            .newBuilder()
                            .addHeader(
                                "Authorization",
                                AuthorizationHeaderBuilder.buildHeader(
                                    clientName = clientInfo.name,
                                    clientVersion = clientInfo.version,
                                    deviceId = deviceInfo.id,
                                    deviceName = deviceInfo.name,
                                    accessToken = accessToken,
                                ),
                            ).build()
                    }
                chain.proceed(newRequest ?: request)
            }.build()

    @Provides
    @Singleton
    fun okHttpFactory(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = OkHttpFactory(okHttpClient)

    @Provides
    @Singleton
    fun jellyfin(
        okHttpFactory: OkHttpFactory,
        @ApplicationContext context: Context,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ): Jellyfin =
        createJellyfin {
            this.context = context
            this.clientInfo = clientInfo
            this.deviceInfo = deviceInfo
            apiClientFactory = okHttpFactory
            socketConnectionFactory = okHttpFactory
            minimumServerVersion = Jellyfin.minimumVersion
        }

    @Provides
    @Singleton
    fun apiClient(jellyfin: Jellyfin): ApiClient = jellyfin.createApi()

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun deviceInfo(
        @ApplicationContext context: Context,
    ): DeviceInfo = androidDevice(context)
}
