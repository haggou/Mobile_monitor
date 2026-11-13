package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "connect_activity_consent"
)

data class PrivacyPreferencesState(
    val deviceId: String = "",
    val hasCompletedOnboarding: Boolean = false,
    val consentAccepted: Boolean = false,
    val callHistoryConsent: Boolean = false,
    val smsHistoryConsent: Boolean = false,
    val recordingSyncConsent: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val wifiOnlySync: Boolean = true,
    val lastSyncMillis: Long = 0L,
    val policyVersion: String = "1.0"
)

class ConsentPreferences(private val context: Context) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val CONSENT_ACCEPTED = booleanPreferencesKey("consent_accepted")
        val CALL_CONSENT = booleanPreferencesKey("call_consent")
        val SMS_CONSENT = booleanPreferencesKey("sms_consent")
        val RECORDING_CONSENT = booleanPreferencesKey("recording_consent")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val LAST_SYNC_MILLIS = longPreferencesKey("last_sync_millis")
    }

    val state: Flow<PrivacyPreferencesState> = context.consentDataStore.data.map { prefs ->
        PrivacyPreferencesState(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            hasCompletedOnboarding = prefs[Keys.ONBOARDING_DONE] ?: false,
            consentAccepted = prefs[Keys.CONSENT_ACCEPTED] ?: false,
            callHistoryConsent = prefs[Keys.CALL_CONSENT] ?: false,
            smsHistoryConsent = prefs[Keys.SMS_CONSENT] ?: false,
            recordingSyncConsent = prefs[Keys.RECORDING_CONSENT] ?: false,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: false,
            wifiOnlySync = prefs[Keys.WIFI_ONLY] ?: true,
            lastSyncMillis = prefs[Keys.LAST_SYNC_MILLIS] ?: 0L
        )
    }

    suspend fun getOrCreateDeviceId(ownerUserId: String? = null): String {
        val current = context.consentDataStore.data.first()[Keys.DEVICE_ID]
        val baseId = if (!current.isNullOrBlank()) {
            current
        } else {
            val generated = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            context.consentDataStore.edit { it[Keys.DEVICE_ID] = generated }
            generated
        }
        return if (ownerUserId.isNullOrBlank()) {
            baseId
        } else {
            "dev_${KeystoreCryptoManager.sha256Hex("$ownerUserId|$baseId").take(20)}"
        }
    }

    suspend fun saveConsent(
        accepted: Boolean,
        callConsent: Boolean,
        smsConsent: Boolean,
        recordingConsent: Boolean,
        autoSync: Boolean
    ) {
        context.consentDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_DONE] = true
            prefs[Keys.CONSENT_ACCEPTED] = accepted
            prefs[Keys.CALL_CONSENT] = callConsent
            prefs[Keys.SMS_CONSENT] = smsConsent
            prefs[Keys.RECORDING_CONSENT] = recordingConsent
            prefs[Keys.AUTO_SYNC] = autoSync
        }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        context.consentDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.consentDataStore.edit { it[Keys.WIFI_ONLY] = wifiOnly }
    }

    suspend fun setLastSyncMillis(millis: Long) {
        context.consentDataStore.edit { it[Keys.LAST_SYNC_MILLIS] = millis }
    }

    suspend fun revokeAllConsent() {
        context.consentDataStore.edit { prefs ->
            prefs[Keys.CONSENT_ACCEPTED] = false
            prefs[Keys.CALL_CONSENT] = false
            prefs[Keys.SMS_CONSENT] = false
            prefs[Keys.RECORDING_CONSENT] = false
            prefs[Keys.AUTO_SYNC] = false
        }
    }

    suspend fun clearAll() {
        context.consentDataStore.edit { it.clear() }
    }
}
