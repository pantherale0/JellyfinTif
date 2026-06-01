package com.github.pantherale0.jellyfintif.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pantherale0.jellyfintif.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface SetupScreenState {
    data object EnterServerUrl : SetupScreenState

    data class QuickConnect(
        val serverUrl: String,
        val code: String,
    ) : SetupScreenState

    data object Syncing : SetupScreenState

    data class Error(val message: String) : SetupScreenState
}

@HiltViewModel
class QuickConnectViewModel
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow<SetupScreenState>(SetupScreenState.EnterServerUrl)
        val state: StateFlow<SetupScreenState> = _state.asStateFlow()

        private var quickConnectJob: Job? = null

        fun connectToServer(inputUrl: String) {
            viewModelScope.launch {
                _state.value = SetupScreenState.EnterServerUrl
                try {
                    val scores =
                        jellyfin.discovery
                            .getRecommendedServers(inputUrl)
                            .sortedBy { it.score }
                    val bestServer =
                        scores.firstOrNull { it.score != RecommendedServerInfoScore.BAD }
                    val serverInfo = bestServer?.systemInfo?.getOrNull()
                    if (bestServer == null || serverInfo == null) {
                        _state.value =
                            SetupScreenState.Error(
                                "Could not connect to server. Check the URL and try again.",
                            )
                        return@launch
                    }
                    val serverUrl = bestServer.address
                    val id = serverInfo.id?.toUUIDOrNull()
                    if (id == null || serverInfo.startupWizardCompleted != true) {
                        _state.value =
                            SetupScreenState.Error("Server returned an invalid response.")
                        return@launch
                    }
                    startQuickConnect(serverUrl)
                } catch (ex: Exception) {
                    Timber.e(ex, "Error connecting to server")
                    _state.value =
                        SetupScreenState.Error(
                            ex.message ?: "Could not connect to server.",
                        )
                }
            }
        }

        private fun startQuickConnect(serverUrl: String) {
            quickConnectJob?.cancel()
            quickConnectJob =
                viewModelScope.launch {
                    try {
                        val api = jellyfin.createApi(serverUrl)
                        val quickConnectEnabled =
                            runCatching {
                                api.quickConnectApi.getQuickConnectEnabled().content
                            }.getOrDefault(false)
                        if (!quickConnectEnabled) {
                            _state.value =
                                SetupScreenState.Error(
                                    "Quick Connect is disabled on this server. Enable it in the Jellyfin dashboard.",
                                )
                            return@launch
                        }
                        var quickConnectStatus =
                            api.quickConnectApi.initiateQuickConnect().content
                        _state.value =
                            SetupScreenState.QuickConnect(
                                serverUrl = serverUrl,
                                code = quickConnectStatus.code?.toString() ?: "",
                            )
                        while (!quickConnectStatus.authenticated) {
                            delay(5_000L)
                            quickConnectStatus =
                                api.quickConnectApi
                                    .getQuickConnectState(
                                        secret = quickConnectStatus.secret,
                                    ).content
                            _state.value =
                                SetupScreenState.QuickConnect(
                                    serverUrl = serverUrl,
                                    code = quickConnectStatus.code?.toString() ?: "",
                                )
                        }
                        val authenticationResult by
                            api.userApi.authenticateWithQuickConnect(
                                QuickConnectDto(secret = quickConnectStatus.secret),
                            )
                        sessionRepository.saveSession(serverUrl, authenticationResult)
                        _state.value = SetupScreenState.Syncing
                    } catch (_: CancellationException) {
                        // User cancelled
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error during Quick Connect")
                        val message =
                            if (ex is InvalidStatusException && ex.status == 401) {
                                "Quick Connect failed. Enable Quick Connect in the Jellyfin dashboard."
                            } else {
                                ex.message ?: "Quick Connect failed."
                            }
                        _state.value = SetupScreenState.Error(message)
                    }
                }
        }

        fun cancelQuickConnect() {
            quickConnectJob?.cancel()
            quickConnectJob = null
            _state.value = SetupScreenState.EnterServerUrl
        }

        fun resetToServerEntry() {
            cancelQuickConnect()
            _state.value = SetupScreenState.EnterServerUrl
        }
    }
