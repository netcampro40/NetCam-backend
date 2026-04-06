package com.netcam.app.data.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.netcam.app.domain.auth.CameraAccessAuthorization
import com.netcam.app.domain.auth.CameraAccessRepository
import kotlinx.coroutines.flow.first
import java.io.IOException

class DataStoreCameraAccessRepository(
    appContext: Context,
) : CameraAccessRepository {
    private val dataStore =
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(STORE_NAME) },
        )

    override suspend fun getAuthorization(): CameraAccessAuthorization? {
        val prefs = readPreferences()
        val token = prefs[Keys.ARENA_TOKEN] ?: return null
        val name = prefs[Keys.ARENA_NAME] ?: return null
        val authorizedAt = prefs[Keys.AUTHORIZED_AT] ?: return null
        val expiresAt = prefs[Keys.EXPIRES_AT] ?: return null
        return CameraAccessAuthorization(
            arenaToken = token,
            arenaName = name,
            authorizedAtEpochMs = authorizedAt,
            expiresAtEpochMs = expiresAt,
        )
    }

    override suspend fun saveAuthorization(authorization: CameraAccessAuthorization) {
        dataStore.edit { prefs ->
            prefs[Keys.ARENA_TOKEN] = authorization.arenaToken
            prefs[Keys.ARENA_NAME] = authorization.arenaName
            prefs[Keys.AUTHORIZED_AT] = authorization.authorizedAtEpochMs
            prefs[Keys.EXPIRES_AT] = authorization.expiresAtEpochMs
        }
    }

    override suspend fun clearAuthorization() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ARENA_TOKEN)
            prefs.remove(Keys.ARENA_NAME)
            prefs.remove(Keys.AUTHORIZED_AT)
            prefs.remove(Keys.EXPIRES_AT)
        }
    }

    override suspend fun isAuthorizedNow(nowEpochMs: Long): Boolean {
        val auth = getAuthorization() ?: return false
        val isValid = nowEpochMs < auth.expiresAtEpochMs
        if (!isValid) {
            clearAuthorization()
        }
        return isValid
    }

    private suspend fun readPreferences(): Preferences =
        try {
            dataStore.data.first()
        } catch (_: IOException) {
            emptyPreferences()
        }

    private object Keys {
        val ARENA_TOKEN = stringPreferencesKey("arena_token")
        val ARENA_NAME = stringPreferencesKey("arena_name")
        val AUTHORIZED_AT = longPreferencesKey("authorized_at_epoch_ms")
        val EXPIRES_AT = longPreferencesKey("expires_at_epoch_ms")
    }

    companion object {
        private const val STORE_NAME = "camera_access_auth.preferences_pb"
    }
}
