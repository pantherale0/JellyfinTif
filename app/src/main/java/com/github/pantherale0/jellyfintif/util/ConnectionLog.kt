package com.github.pantherale0.jellyfintif.util

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.UUID
import timber.log.Timber

/**
 * Structured connection diagnostics. Never logs access tokens or Quick Connect secrets.
 */
object ConnectionLog {
    private const val TAG = "Connection"

    fun setup(message: String) {
        Timber.tag(TAG).i("[setup] %s", message)
    }

    fun setup(message: String, throwable: Throwable) {
        Timber.tag(TAG).e(throwable, "[setup] %s", message)
    }

    fun session(message: String) {
        Timber.tag(TAG).i("[session] %s", message)
    }

    fun sync(message: String) {
        Timber.tag(TAG).i("[sync] %s", message)
    }

    fun sync(message: String, throwable: Throwable) {
        Timber.tag(TAG).w(throwable, "[sync] %s", message)
    }

    fun playback(message: String) {
        Timber.tag(TAG).i("[playback] %s", message)
    }

    fun playback(message: String, throwable: Throwable) {
        Timber.tag(TAG).w(throwable, "[playback] %s", message)
    }

    fun http(method: String, url: String, code: Int, durationMs: Long) {
        Timber.tag(TAG).d("[http] %s %s -> %d (%dms)", method, sanitizeUrl(url), code, durationMs)
    }

    fun httpFailure(method: String, url: String, throwable: Throwable) {
        Timber.tag(TAG).w(throwable, "[http] %s %s failed", method, sanitizeUrl(url))
    }

    fun apiClient(
        context: String,
        api: ApiClient,
    ) {
        Timber.tag(TAG).i(
            "[%s] server=%s tokenPresent=%s",
            context,
            api.baseUrl ?: "(none)",
            !api.accessToken.isNullOrBlank(),
        )
    }

    fun discovery(
        inputUrl: String,
        candidates: List<RecommendedServerInfo>,
    ) {
        if (candidates.isEmpty()) {
            Timber.tag(TAG).w("[discovery] No candidates for input=%s", inputUrl)
            return
        }
        Timber.tag(TAG).i("[discovery] input=%s found %d candidate(s)", inputUrl, candidates.size)
        candidates.forEach { candidate ->
            val info = candidate.systemInfo.getOrNull()
            Timber.tag(TAG).i(
                "[discovery]   score=%s address=%s server=%s version=%s id=%s wizardComplete=%s issues=%s",
                candidate.score,
                candidate.address,
                info?.serverName ?: "?",
                info?.version ?: "?",
                info?.id ?: "?",
                info?.startupWizardCompleted,
                candidate.issues.joinToString { it.javaClass.simpleName }.ifBlank { "none" },
            )
        }
    }

    fun discoveryRejected(
        inputUrl: String,
        reason: String,
    ) {
        Timber.tag(TAG).w("[discovery] Rejected input=%s: %s", inputUrl, reason)
    }

    fun quickConnect(
        serverUrl: String,
        step: String,
        detail: String? = null,
    ) {
        if (detail != null) {
            Timber.tag(TAG).i("[quickconnect] %s @ %s — %s", step, serverUrl, detail)
        } else {
            Timber.tag(TAG).i("[quickconnect] %s @ %s", step, serverUrl)
        }
    }

    fun quickConnectPoll(
        serverUrl: String,
        authenticated: Boolean,
        code: String?,
    ) {
        Timber.tag(TAG).d(
            "[quickconnect] poll @ %s authenticated=%s code=%s",
            serverUrl,
            authenticated,
            code ?: "?",
        )
    }

    fun streamResolve(
        channelId: UUID,
        options: String,
        outcome: String,
    ) {
        Timber.tag(TAG).i("[stream] channel=%s options={%s} -> %s", channelId, options, outcome)
    }

    fun streamResolveFailure(
        channelId: UUID,
        reason: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Timber.tag(TAG).w(throwable, "[stream] channel=%s failed: %s", channelId, reason)
        } else {
            Timber.tag(TAG).w("[stream] channel=%s failed: %s", channelId, reason)
        }
    }

    /** Strip query strings that may contain session identifiers. */
    fun sanitizeUrl(url: String): String =
        runCatching {
            val withoutQuery = url.substringBefore('?')
            withoutQuery.substringBefore('#')
        }.getOrDefault(url)

    fun redactSecret(value: String?): String =
        when {
            value.isNullOrBlank() -> "(none)"
            value.length <= 4 -> "****"
            else -> "${value.take(4)}…(${value.length} chars)"
        }
}
