package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(
    name = "tv_channel_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val channelIdKey = longPreferencesKey("continue_watching_channel_id")
    private val logoVersionKey = intPreferencesKey("channel_logo_version")

    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data.map { it[channelIdKey] }.first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit { it[channelIdKey] = id }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit { it.remove(channelIdKey) }
    }

    /** The app versionCode whose launcher icon was last written as the channel logo. */
    suspend fun getLogoVersion(): Int? =
        context.tvChannelDataStore.data.map { it[logoVersionKey] }.first()

    suspend fun setLogoVersion(version: Int) {
        context.tvChannelDataStore.edit { it[logoVersionKey] = version }
    }
}
