package com.pixelplus.owner.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.localeDataStore: DataStore<Preferences> by preferencesDataStore(name = "owner_locale")

/** Persists the language choice and applies it through AppCompat. */
class LocaleStore(private val context: Context) {
    private val dataStore = context.applicationContext.localeDataStore
    private val key = stringPreferencesKey("app_language")

    fun read(): AppLanguage = runBlocking(Dispatchers.IO) {
        dataStore.data.map { prefs -> AppLanguage.fromStorage(prefs[key]) }.first()
    }

    fun flow(): Flow<AppLanguage> = dataStore.data.map { prefs -> AppLanguage.fromStorage(prefs[key]) }

    suspend fun save(language: AppLanguage, ticket: Long? = null, latest: AtomicLong? = null) {
        if (ticket != null && latest != null && latest.get() != ticket) return
        dataStore.edit { prefs ->
            if (ticket != null && latest != null && latest.get() != ticket) return@edit
            prefs[key] = language.storageValue
        }
        if (ticket != null && latest != null && latest.get() != ticket) return
        apply(language)
    }

    fun apply(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.ARABIC -> LocaleListCompat.forLanguageTags("ar")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.FOLLOW_SYSTEM -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        /** Called from [com.pixelplus.owner.OwnerApplication] before the first activity. */
        fun warmStart(context: Context) {
            runBlocking(Dispatchers.IO) {
                val store = LocaleStore(context)
                val prefs = store.dataStore.data.first()
                if (!prefs.contains(store.key)) return@runBlocking
                store.apply(AppLanguage.fromStorage(prefs[store.key]))
            }
        }
    }
}
