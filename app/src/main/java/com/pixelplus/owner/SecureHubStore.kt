package com.pixelplus.owner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hub URL and tokens live in Keystore-backed preferences. If the keystore cannot be opened,
 * nothing is written in the clear and the app stays in demo mode.
 */
class SecureHubStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? = open(context.applicationContext)

    val encryptionAvailable: Boolean get() = prefs != null

    fun read(): HubConfig {
        val stored = prefs ?: return HubConfig("", "", "")
        val url = when (val parsed = normalizeHubUrl(stored.getString(KEY_URL, "").orEmpty())) {
            is UrlResult.Ok -> parsed.url
            is UrlResult.Reject -> ""
        }
        val owner = cleanToken(stored.getString(KEY_OWNER, "").orEmpty()) ?: ""
        val pullRaw = stored.getString(KEY_PULL, "").orEmpty()
        val pull = if (pullRaw.isBlank()) "" else cleanToken(pullRaw) ?: ""
        return HubConfig(url, owner, pull)
    }

    fun save(config: HubConfig): Boolean = synchronized(com.pixelplus.owner.sync.HubSession.lock) {
        val stored = prefs ?: return false
        val url = when (val parsed = normalizeHubUrl(config.baseUrl)) {
            is UrlResult.Ok -> parsed.url
            is UrlResult.Reject -> return false
        }
        val owner = cleanToken(config.ownerToken) ?: return false
        val pull = if (config.pullToken.isBlank()) "" else cleanToken(config.pullToken) ?: return false
        if (!runCatching { com.pixelplus.owner.sync.OwnerSync.onSaved(appContext, read(), HubConfig(url, owner, pull)) }.getOrDefault(false)) return false
        return stored.edit()
            .putString(KEY_URL, url)
            .putString(KEY_OWNER, owner)
            .putString(KEY_PULL, pull)
            .commit()
    }

    fun clear(): Boolean = synchronized(com.pixelplus.owner.sync.HubSession.lock) {
        val stored = prefs ?: return false
        if (!runCatching { com.pixelplus.owner.sync.OwnerSync.onCleared(appContext, read()) }.getOrDefault(false)) return false
        return stored.edit().clear().commit()
    }

    fun readSeen(): SeenMarks {
        val stored = prefs ?: return SeenMarks.EMPTY
        return SeenMarks(
            requestsReady = stored.getBoolean(KEY_REQ_READY, false),
            eventsReady = stored.getBoolean(KEY_EVT_READY, false),
            requestIds = decodeIds(stored.getString(KEY_REQ_IDS, "").orEmpty()),
            eventIds = decodeIds(stored.getString(KEY_EVT_IDS, "").orEmpty())
        )
    }

    fun writeSeen(marks: SeenMarks): Boolean {
        val stored = prefs ?: return false
        return stored.edit()
            .putBoolean(KEY_REQ_READY, marks.requestsReady)
            .putBoolean(KEY_EVT_READY, marks.eventsReady)
            .putString(KEY_REQ_IDS, encodeIds(marks.requestIds))
            .putString(KEY_EVT_IDS, encodeIds(marks.eventIds))
            .commit()
    }

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val FILE_NAME = "pixel_plus_owner_hub"
        const val KEY_URL = "hub_url"
        const val KEY_OWNER = "owner_token"
        const val KEY_PULL = "pull_token"
        const val KEY_REQ_IDS = "seen_request_ids"
        const val KEY_EVT_IDS = "seen_event_ids"
        const val KEY_REQ_READY = "seen_requests_ready"
        const val KEY_EVT_READY = "seen_events_ready"
    }
}
