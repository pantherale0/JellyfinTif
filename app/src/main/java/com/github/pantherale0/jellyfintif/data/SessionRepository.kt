package com.github.pantherale0.jellyfintif.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import org.jellyfin.sdk.model.UUID

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

data class SessionInfo(
    val serverUrl: String,
    val serverId: UUID,
    val userId: UUID,
    val accessToken: String,
    val serverName: String?,
)

@Singleton
class SessionRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val apiClient: ApiClient,
    ) {
        private val dataStore = context.sessionDataStore

        private val serverUrlKey = stringPreferencesKey("server_url")
        private val serverIdKey = stringPreferencesKey("server_id")
        private val userIdKey = stringPreferencesKey("user_id")
        private val accessTokenKey = stringPreferencesKey("access_token")
        private val serverNameKey = stringPreferencesKey("server_name")

        val isAuthenticated: Boolean
            get() =
                !apiClient.accessToken.isNullOrBlank() &&
                    !apiClient.baseUrl.isNullOrBlank()

        suspend fun getSession(): SessionInfo? {
            val prefs = dataStore.data.first()
            val serverUrl = prefs[serverUrlKey] ?: return null
            val serverId = prefs[serverIdKey]?.toUUIDOrNull() ?: return null
            val userId = prefs[userIdKey]?.toUUIDOrNull() ?: return null
            val accessToken = prefs[accessTokenKey] ?: return null
            val serverName = prefs[serverNameKey]
            return SessionInfo(
                serverUrl = serverUrl,
                serverId = serverId,
                userId = userId,
                accessToken = accessToken,
                serverName = serverName,
            )
        }

        suspend fun saveSession(
            serverUrl: String,
            authenticationResult: AuthenticationResult,
        ) {
            val accessToken =
                authenticationResult.accessToken
                    ?: throw IllegalArgumentException("Authentication result access token was null")
            val authedUser =
                authenticationResult.user
                    ?: throw IllegalArgumentException("Authentication result user was null")
            val serverId =
                authenticationResult.serverId?.toUUIDOrNull()
                    ?: throw IllegalArgumentException("Authentication result serverId not valid")

            apiClient.update(baseUrl = serverUrl, accessToken = accessToken)

            dataStore.edit { prefs ->
                prefs[serverUrlKey] = serverUrl
                prefs[serverIdKey] = serverId.toString()
                prefs[userIdKey] = authedUser.id.toString()
                prefs[accessTokenKey] = accessToken
                prefs[serverNameKey] = authedUser.serverName ?: ""
            }
            Timber.i("Session saved for user %s on %s", authedUser.name, serverUrl)
        }

        suspend fun restoreSession(): Boolean {
            if (isAuthenticated) return true
            val session = getSession() ?: return false
            apiClient.update(baseUrl = session.serverUrl, accessToken = session.accessToken)
            return isAuthenticated
        }

        suspend fun getUserId(): UUID? = getSession()?.userId

        suspend fun getServerId(): UUID? = getSession()?.serverId

        suspend fun getAccessToken(): String? = getSession()?.accessToken ?: apiClient.accessToken

        suspend fun clearSession() {
            dataStore.edit { it.clear() }
            apiClient.update(baseUrl = null, accessToken = null)
        }
    }
