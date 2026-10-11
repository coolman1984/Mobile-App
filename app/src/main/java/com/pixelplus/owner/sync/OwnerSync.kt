package com.pixelplus.owner.sync

import android.content.Context
import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.HubResult
import com.pixelplus.owner.LicenceRequest
import com.pixelplus.owner.OwnerAction
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.arabicFailure
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.normalizeHubUrl

/**
 * The screen calls this. A queued decision is not described as accepted by the hub.
 * Tokens stay in the encrypted store. This type does not put them in the database.
 */
data class RequestSnapshot(val result: HubResult<List<LicenceRequest>>, val cachedAt: Long? = null)

object OwnerSync {
    fun requests(context: Context, config: HubConfig, client: com.pixelplus.owner.HubClient): RequestSnapshot = synchronized(HubSession.lock) {
        if (com.pixelplus.owner.SecureHubStore(context).read() != config) return RequestSnapshot(HubResult.Rejected("settings_changed"))
        val database = open(context) ?: return RequestSnapshot(HubResult.Rejected("cache_unavailable"))
        try {
            val store = SyncStore(database)
            val now = System.currentTimeMillis()
            val profile = store.ensureProfile(config.baseUrl, now) ?: return RequestSnapshot(HubResult.Rejected("bad_url"))
            val result = client.pendingAll(config)
            if (result is HubResult.Ok) {
                store.savePendingPage(profile, result.value.map { it.toCached() }, result.value.lastOrNull()?.let { "${it.createdAt}:${it.id}" }.orEmpty(), true, now)
                if (HubSync(store, client).noteReachable(config, now) && store.openRows(profile.profileId).any { it.status in OutboxRules.ACTIVE }) com.pixelplus.owner.HubScheduler.kick(context, profile.profileId)
                return RequestSnapshot(result)
            }
            // Auth denial must remain visible. Cached rows do not grant access.
            if (result is HubResult.Network || result is HubResult.Http && (result.code == 429 || result.code >= 500)) {
                val cachedAt = store.cursor(profile.profileId, SyncStore.STREAM_PENDING)?.lastSuccess
                if (cachedAt != null) return RequestSnapshot(HubResult.Ok(store.cachedRequests(profile.profileId)), cachedAt)
            }
            return RequestSnapshot(result)
        } finally {
            database.close()
        }
    }

    fun enqueue(context: Context, config: HubConfig, request: LicenceRequest, action: OwnerAction): String = synchronized(HubSession.lock) {
        if (com.pixelplus.owner.SecureHubStore(context).read() != config) return SyncCopy.settingsChanged().display
        if (!config.isLive) return arabicFailure("no_token")
        val database = open(context) ?: return arabicFailure("http")
        return try {
            val store = SyncStore(database)
            val now = System.currentTimeMillis()
            val profile = store.ensureProfile(config.baseUrl, now) ?: return arabicFailure("bad_url")
            when (val outcome = store.enqueue(profile, request, action, now)) {
                is QueueOutcome.Queued -> {
                    runCatching { com.pixelplus.owner.HubScheduler.kick(context, profile.profileId) }
                    outcome.display
                }
                is QueueOutcome.Existing -> outcome.display
                is QueueOutcome.Rejected -> arabicFailure(outcome.reason)
            }
        } finally {
            database.close()
        }
    }

    fun queueLine(context: Context, config: HubConfig): String {
        if (!config.isLive) return ""
        val database = open(context) ?: return ""
        return try {
            val store = SyncStore(database)
            val profile = store.profileForUrl(config.baseUrl) ?: return ""
            store.waitingLine(profile.profileId, System.currentTimeMillis())
        } finally {
            database.close()
        }
    }

    fun onSaved(context: Context, previous: HubConfig, next: HubConfig): Boolean {
        if (!next.isLive) return false
        val database = open(context) ?: return false
        try {
            val store = SyncStore(database)
            val now = System.currentTimeMillis()
            val profile = store.ensureProfile(next.baseUrl, now) ?: return false
            val previousUrl = normalized(previous.baseUrl)
            val nextUrl = normalized(next.baseUrl)
            if (previous.isLive && previousUrl != null && nextUrl != null && previousUrl != nextUrl) {
                store.profileForUrl(previous.baseUrl)?.let { old ->
                    if (old.profileId != profile.profileId) {
                        if (store.changeOrigin(old.profileId, previous.baseUrl, now) == null) return false
                    }
                }
            }
            val tokenChanged = previous.ownerToken != next.ownerToken || previousUrl != nextUrl
            if (tokenChanged && !store.pauseCredential(profile.profileId, now)) return false
            return true
        } finally {
            database.close()
        }
    }

    fun onCleared(context: Context, previous: HubConfig): Boolean {
        if (!previous.isLive) return true
        val database = open(context) ?: return false
        try {
            val store = SyncStore(database)
            val profile = store.profileForUrl(previous.baseUrl) ?: return true
            return store.pauseCredential(profile.profileId, System.currentTimeMillis())
        } finally {
            database.close()
        }
    }

    fun noteReachable(context: Context, config: HubConfig, client: com.pixelplus.owner.HubClient, pendingOk: Boolean) {
        if (!pendingOk || !config.isLive) return
        val database = open(context) ?: return
        try {
            HubSync(SyncStore(database), client).noteReachable(config, System.currentTimeMillis())
        } finally {
            database.close()
        }
    }

    private fun open(context: Context): OwnerDatabase? = runCatching {
        OwnerDatabase.open(context.applicationContext)
    }.getOrNull()

    private fun normalized(raw: String): String? = when (val parsed = normalizeHubUrl(raw)) {
        is UrlResult.Ok -> parsed.url
        is UrlResult.Reject -> null
    }
}

