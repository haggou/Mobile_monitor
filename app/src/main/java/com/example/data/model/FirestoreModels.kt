package com.example.data.model

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth = FirebaseAuth.getInstance()
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = auth.currentUser
    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val syncEnabled: Boolean = false,
    val consentAccepted: Boolean = false,
    val consentVersion: String = "1.0",
    @ServerTimestamp val consentUpdatedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class DeviceRecord(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val osVersion: String = "",
    val isConnected: Boolean = true,
    val callRecordingSupported: Boolean = false,
    @ServerTimestamp val lastSyncAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CallRecord(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "android_call_log",
    val recordHash: String = "",
    val phoneNumber: String = "",
    val contactName: String = "",
    val callType: String = "incoming",
    val callTimestampMillis: Long = 0L,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val syncedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SmsRecord(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "user_import",
    val recordHash: String = "",
    val address: String = "",
    val contactName: String = "",
    val messageBody: String = "",
    val direction: String = "incoming",
    val deliveryStatus: String = "received",
    val messageTimestampMillis: Long = 0L,
    @ServerTimestamp val syncedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class RecordingMetadata(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "user_imported_audio",
    val callRecordId: String? = null,
    val phoneNumber: String = "",
    val contactName: String = "",
    val fileName: String = "",
    val mimeType: String = "audio/mp4",
    val durationSeconds: Long = 0L,
    val sizeBytes: Long = 0L,
    val encryptionAlgorithm: String = "AES-256-GCM-AndroidKeystore",
    val authorizedForSync: Boolean = false,
    val recordedAtMillis: Long = 0L,
    @ServerTimestamp val syncedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ConsentRecord(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "consent_flow",
    val consentType: String = "",
    val granted: Boolean = false,
    val policyVersion: String = "1.0",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SyncStatusRecord(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "manual_sync",
    val status: String = "idle",
    val callsSyncedCount: Long = 0L,
    val smsSyncedCount: Long = 0L,
    val recordingsSyncedCount: Long = 0L,
    val errorMessage: String? = null,
    @ServerTimestamp val lastSyncAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuthorizedShare(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "privacy_dashboard",
    val viewerEmail: String = "",
    val scope: String = "calls",
    val status: String = "active",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val ownerUserId: String = "",
    val deviceId: String = "",
    val source: String = "android_app",
    val action: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
