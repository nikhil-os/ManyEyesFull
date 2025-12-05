package com.manyeyes.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "manyeyes")

object Keys {
    val token = stringPreferencesKey("token")
    val deviceId = stringPreferencesKey("deviceId")
    val permissionsGranted = booleanPreferencesKey("permissionsGranted")
    val baseUrl = stringPreferencesKey("baseUrl")
}

class Prefs(private val context: Context) {
    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.token] }
    val deviceIdFlow: Flow<String?> = context.dataStore.data.map { it[Keys.deviceId] }
    val permissionsGrantedFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.permissionsGranted] ?: false }
    val baseUrlFlow: Flow<String?> = context.dataStore.data.map { it[Keys.baseUrl] }

    suspend fun setToken(token: String) { context.dataStore.edit { it[Keys.token] = token } }
    suspend fun setDeviceId(id: String) { context.dataStore.edit { it[Keys.deviceId] = id } }
    suspend fun setPermissionsGranted(granted: Boolean) { context.dataStore.edit { it[Keys.permissionsGranted] = granted } }
    suspend fun setBaseUrl(url: String) { context.dataStore.edit { it[Keys.baseUrl] = url } }
}
