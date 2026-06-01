package com.github.pantherale0.jellyfintif.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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
                ConnectionLog.setup("connectToServer: input=$inputUrl")
                _state.value = SetupScreenState.EnterServerUrl
                try {
                    val scores =
                        jellyfin.discovery
                            .getRecommendedServers(inputUrl)
                            .sortedBy { it.score }
                    ConnectionLog.discovery(inputUrl, scores)

                    val bestServer =
                        scores.firstOrNull { it.score != RecommendedServerInfoScore.BAD }
                    val serverInfo = bestServer?.systemInfo?.getOrNull()
                    if (bestServer == null || serverInfo == null) {
                        ConnectionLog.discoveryRejected(
                            inputUrl,
                            "no candidate with acceptable score (found ${scores.size})",
                        )
                        _state.value =
                            SetupScreenState.Error(
                                "Could not connect to server. Check the URL and try again.",
                            )
                        return@launch
                    }
                    val serverUrl = bestServer.address
                    val id = serverInfo.id?.toUUIDOrNull()
                    if (id == null) {
                        ConnectionLog.discoveryRejected(inputUrl, "server id missing or invalid")
                        _state.value =
                            SetupScreenState.Error("Server returned an invalid response.")
                        return@launch
                    }
                    if (serverInfo.startupWizardCompleted != true) {
                        ConnectionLog.discoveryRejected(
                            inputUrl,
                            "startup wizard not completed on server $id",
                        )
                        _state.value =
                            SetupScreenState.Error("Server returned an invalid response.")
                        return@launch
                    }
                    ConnectionLog.setup(
                        "selected server url=$serverUrl id=$id name=${serverInfo.serverName} version=${serverInfo.version}",
                    )
                    startQuickConnect(serverUrl)
                } catch (ex: Exception) {
                    ConnectionLog.setup("connectToServer failed for input=$inputUrl", ex)
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
                        ConnectionLog.quickConnect(serverUrl, "creating API client")
                        val api = jellyfin.createApi(serverUrl)

                        val quickConnectEnabled =
                            runCatching {
                                api.quickConnectApi.getQuickConnectEnabled().content
                            }.onFailure { ex ->
                                ConnectionLog.setup("getQuickConnectEnabled failed @ $serverUrl", ex)
                            }.getOrDefault(false)

                        ConnectionLog.quickConnect(
                            serverUrl,
                            "Quick Connect enabled check",
                            "enabled=$quickConnectEnabled",
                        )

                        if (!quickConnectEnabled) {
                            _state.value =
                                SetupScreenState.Error(
                                    "Quick Connect is disabled on this server. Enable it in the Jellyfin dashboard.",
                                )
                            return@launch
                        }

                        var quickConnectStatus =
                            api.quickConnectApi.initiateQuickConnect().content
                        ConnectionLog.quickConnect(
                            serverUrl,
                            "initiated",
                            "code=${quickConnectStatus.code} secret=${ConnectionLog.redactSecret(quickConnectStatus.secret)}",
                        )

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
                            ConnectionLog.quickConnectPoll(
                                serverUrl,
                                quickConnectStatus.authenticated,
                                quickConnectStatus.code,
                            )
                            _state.value =
                                SetupScreenState.QuickConnect(
                                    serverUrl = serverUrl,
                                    code = quickConnectStatus.code?.toString() ?: "",
                                )
                        }

                        ConnectionLog.quickConnect(serverUrl, "authenticated, exchanging secret for token")
                        val authenticationResult by
                            api.userApi.authenticateWithQuickConnect(
                                QuickConnectDto(secret = quickConnectStatus.secret),
                            )
                        sessionRepository.saveSession(serverUrl, authenticationResult)
                        ConnectionLog.setup("Quick Connect complete, starting EPG sync")
                        _state.value = SetupScreenState.Syncing
                    } catch (_: CancellationException) {
                        ConnectionLog.setup("Quick Connect cancelled by user")
                    } catch (ex: Exception) {
                        ConnectionLog.setup("Quick Connect failed @ $serverUrl", ex)
                        val message =
                            if (ex is InvalidStatusException) {
                                ConnectionLog.setup(
                                    "HTTP ${ex.status}: ${ex.message}",
                                )
                                if (ex.status == 401) {
                                    "Quick Connect failed. Enable Quick Connect in the Jellyfin dashboard."
                                } else {
                                    ex.message ?: "Quick Connect failed (HTTP ${ex.status})."
                                }
                            } else {
                                ex.message ?: "Quick Connect failed."
                            }
                        _state.value = SetupScreenState.Error(message)
                    }
                }
        }

        fun cancelQuickConnect() {
            ConnectionLog.setup("cancelQuickConnect")
            quickConnectJob?.cancel()
            quickConnectJob = null
            _state.value = SetupScreenState.EnterServerUrl
        }

        fun resetToServerEntry() {
            ConnectionLog.setup("resetToServerEntry")
            cancelQuickConnect()
            _state.value = SetupScreenState.EnterServerUrl
        }
    }
