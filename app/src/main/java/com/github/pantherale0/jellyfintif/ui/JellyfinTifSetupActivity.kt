package com.github.pantherale0.jellyfintif.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.pantherale0.jellyfintif.R
import com.github.pantherale0.jellyfintif.util.ConnectionLog
import com.github.pantherale0.jellyfintif.data.SessionRepository
import com.github.pantherale0.jellyfintif.services.tif.EpgSyncScheduler
import com.github.pantherale0.jellyfintif.services.tif.TifInputServiceReset
import com.github.pantherale0.jellyfintif.ui.components.LoadingIndicator
import com.github.pantherale0.jellyfintif.ui.setup.QuickConnectViewModel
import com.github.pantherale0.jellyfintif.ui.setup.SetupScreenState
import com.github.pantherale0.jellyfintif.ui.theme.JellyfinTifTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

private val JellyfinBlue = Color(0xFF00A4DC)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFBDBDBD)
private val FieldBackground = Color(0xFF2A2A2A)
private val FieldBorder = Color(0xFF888888)

@AndroidEntryPoint
class JellyfinTifSetupActivity : ComponentActivity() {
    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var epgSyncScheduler: EpgSyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TifInputServiceReset.requestRebind(this)
        ConnectionLog.setup(
            "JellyfinTifSetupActivity started authenticated=${sessionRepository.isAuthenticated}",
        )
        setContent {
            JellyfinTifTheme {
                val viewModel: QuickConnectViewModel = hiltViewModel()
                val state by viewModel.state.collectAsState()
                TifSetupScreen(
                    initialAuthenticated = sessionRepository.isAuthenticated,
                    state = state,
                    onConnect = viewModel::connectToServer,
                    onCancelQuickConnect = viewModel::cancelQuickConnect,
                    onRetry = viewModel::resetToServerEntry,
                    enqueueSync = { epgSyncScheduler.enqueueSync() },
                    observeWork = { workId -> observeSyncWork(workId) },
                )
            }
        }
    }

    private fun observeSyncWork(workId: UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    setResult(Activity.RESULT_OK)
                    finish()
                }

                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED,
                -> {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun TifSetupScreen(
    initialAuthenticated: Boolean,
    state: SetupScreenState,
    onConnect: (String) -> Unit,
    onCancelQuickConnect: () -> Unit,
    onRetry: () -> Unit,
    enqueueSync: () -> UUID?,
    observeWork: (UUID) -> Unit,
) {
    var syncStarted by remember { mutableStateOf(false) }
    var enqueueFailed by remember { mutableStateOf(false) }

    LaunchedEffect(initialAuthenticated, state) {
        if ((initialAuthenticated || state is SetupScreenState.Syncing) && !syncStarted && !enqueueFailed) {
            syncStarted = true
            val workId = enqueueSync()
            if (workId != null) {
                observeWork(workId)
            } else {
                enqueueFailed = true
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            enqueueFailed -> {
                ErrorContent(
                    message = stringResource(R.string.tif_setup_sync_failed),
                    onRetry = onRetry,
                )
            }

            initialAuthenticated || state is SetupScreenState.Syncing -> {
                SyncingContent()
            }

            state is SetupScreenState.EnterServerUrl -> {
                ServerUrlContent(onConnect = onConnect)
            }

            state is SetupScreenState.QuickConnect -> {
                QuickConnectContent(
                    code = state.code,
                    onCancel = onCancelQuickConnect,
                )
            }

            state is SetupScreenState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun ServerUrlContent(onConnect: (String) -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            cursorColor = JellyfinBlue,
            focusedBorderColor = JellyfinBlue,
            unfocusedBorderColor = FieldBorder,
            focusedLabelColor = JellyfinBlue,
            unfocusedLabelColor = TextSecondary,
            focusedContainerColor = FieldBackground,
            unfocusedContainerColor = FieldBackground,
        )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(48.dp),
    ) {
        Text(
            text = stringResource(R.string.tif_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tif_setup_enter_server),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = {
                M3Text(
                    text = stringResource(R.string.tif_setup_server_url_hint),
                    color = TextSecondary,
                )
            },
            modifier = Modifier.fillMaxWidth(0.6f),
            colors = fieldColors,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (serverUrl.isNotBlank()) onConnect(serverUrl.trim())
                    },
                ),
            singleLine = true,
        )
        Button(onClick = { if (serverUrl.isNotBlank()) onConnect(serverUrl.trim()) }) {
            Text(stringResource(R.string.tif_setup_connect))
        }
    }
}

@Composable
private fun QuickConnectContent(
    code: String,
    onCancel: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(48.dp),
    ) {
        Text(
            text = stringResource(R.string.tif_setup_quick_connect_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tif_setup_quick_connect_instructions),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            color = JellyfinBlue,
        )
        LoadingIndicator()
        Button(onClick = onCancel) {
            Text(stringResource(R.string.tif_setup_cancel))
        }
    }
}

@Composable
private fun SyncingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(48.dp),
    ) {
        LoadingIndicator()
        Text(
            text = stringResource(R.string.tif_setup_syncing),
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tif_setup_explanation),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.tif_setup_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(48.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.tif_setup_try_again))
        }
    }
}
