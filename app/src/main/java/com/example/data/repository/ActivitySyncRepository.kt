package com.example.data.repository

import android.content.Context
import android.os.Build
import com.example.R
import com.example.data.device.DeviceTelemetryCollector
import com.example.data.local.AppDatabase
import com.example.data.local.LocalCallEntity
import com.example.data.local.LocalRecordingEntity
import com.example.data.local.LocalSmsEntity
import com.example.data.model.AuditLog
import com.example.data.model.AuthorizedShare
import com.example.data.model.CallRecord
import com.example.data.model.ConsentRecord
import com.example.data.model.DeviceRecord
import com.example.data.model.OperationType
import com.example.data.model.RecordingMetadata
import com.example.data.model.SmsRecord
import com.example.data.model.SyncStatusRecord
import com.example.data.model.UserProfile
import com.example.data.model.handleFirestoreError
import com.example.data.preferences.ConsentPreferences
import com.example.security.KeystoreCryptoManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SyncExecutionResult(
    val callsSynced: Int,
    val smsSynced: Int,
    val recordingsSynced: Int
)

class ActivitySyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val localDb: AppDatabase,
    private val crypto: KeystoreCryptoManager,
    private val preferences: ConsentPreferences,
    private val collector: DeviceTelemetryCollector
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = FirebaseAuth.getInstance(),
        localDb = AppDatabase.getInstance(context.applicationContext),
        crypto = KeystoreCryptoManager(),
        preferences = ConsentPreferences(context.applicationContext),
        collector = DeviceTelemetryCollector(context.applicationContext, KeystoreCryptoManager())
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeLocalCalls(uid: String): Flow<List<LocalCallEntity>> = localDb.callDao().observeCalls(uid)

    fun observeLocalSms(uid: String): Flow<List<LocalSmsEntity>> = localDb.smsDao().observeSms(uid)

    fun observeLocalRecordings(uid: String): Flow<List<LocalRecordingEntity>> = localDb.recordingDao().observeRecordings(uid)

    fun decryptSmsBody(encryptedBody: String): String {
        return runCatching { crypto.decryptText(encryptedBody) }.getOrDefault("[Encrypted payload unavailable]")
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snap -> snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    handleFlowError(error, OperationType.GET, docRef.path, uid)
                    throw error
                }
        )
    }

    fun observeDevices(uid: String): Flow<List<DeviceRecord>> = flow {
        val query = db.collection("devices").whereEqualTo("ownerUserId", uid)
        emitAll(
            query.snapshots()
                .map { it.toObjects(DeviceRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, "devices", uid)
                    throw error
                }
        )
    }

    fun observeSyncStatuses(uid: String): Flow<List<SyncStatusRecord>> = flow {
        val query = db.collection("sync_status").whereEqualTo("ownerUserId", uid)
        emitAll(
            query.snapshots()
                .map { it.toObjects(SyncStatusRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, "sync_status", uid)
                    throw error
                }
        )
    }

    fun observeAuthorizedShares(uid: String): Flow<List<AuthorizedShare>> = flow {
        val query = db.collection("authorized_shares").whereEqualTo("ownerUserId", uid)
        emitAll(
            query.snapshots()
                .map { it.toObjects(AuthorizedShare::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, "authorized_shares", uid)
                    throw error
                }
        )
    }

    fun observeAuditLogs(uid: String): Flow<List<AuditLog>> = flow {
        val query = db.collection("audit_logs")
            .whereEqualTo("ownerUserId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
        emitAll(
            query.snapshots()
                .map { it.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, "audit_logs", uid)
                    throw error
                }
        )
    }

    suspend fun ensureUserAndDeviceRegistered() {
        val uid = requireUserId()
        val user = auth.currentUser
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val prefs = preferences.state.first()

        val userRef = db.collection("users").document(uid)
        try {
            val existingUser = userRef.get().await()
            if (!existingUser.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "email" to (user?.email ?: ""),
                    "displayName" to (user?.displayName ?: "Account Owner"),
                    "syncEnabled" to prefs.autoSyncEnabled,
                    "consentAccepted" to prefs.consentAccepted,
                    "consentVersion" to prefs.policyVersion,
                    "consentUpdatedAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path, auth)
            throw e
        }

        val deviceRef = db.collection("devices").document(deviceId)
        try {
            val existingDevice = deviceRef.get().await()
            val recordingSupported = collector.isNativeCallRecordingSupported()
            if (!existingDevice.exists()) {
                val payload = mapOf(
                    "ownerUserId" to uid,
                    "deviceId" to deviceId,
                    "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" },
                    "manufacturer" to Build.MANUFACTURER.orEmpty().take(80),
                    "model" to Build.MODEL.orEmpty().take(80),
                    "osVersion" to (Build.VERSION.RELEASE ?: "10+").take(40),
                    "isConnected" to true,
                    "callRecordingSupported" to recordingSupported,
                    "lastSyncAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                deviceRef.set(payload).await()
                appendAuditLog(uid, deviceId, "DEVICE_REGISTERED", "Registered device ${Build.MODEL}")
            } else {
                deviceRef.update(
                    mapOf(
                        "isConnected" to true,
                        "callRecordingSupported" to recordingSupported,
                        "osVersion" to (Build.VERSION.RELEASE ?: "10+").take(40),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, deviceRef.path, auth)
            throw e
        }
    }

    suspend fun saveConsentPreferences(
        accepted: Boolean,
        callConsent: Boolean,
        smsConsent: Boolean,
        recordingConsent: Boolean,
        autoSync: Boolean
    ) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        preferences.saveConsent(accepted, callConsent, smsConsent, recordingConsent, autoSync)

        val userRef = db.collection("users").document(uid)
        try {
            val existing = userRef.get().await()
            if (existing.exists()) {
                userRef.update(
                    mapOf(
                        "syncEnabled" to autoSync,
                        "consentAccepted" to accepted,
                        "consentVersion" to "1.0",
                        "consentUpdatedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val user = auth.currentUser
                userRef.set(
                    mapOf(
                        "userId" to uid,
                        "email" to (user?.email ?: ""),
                        "displayName" to (user?.displayName ?: "Account Owner"),
                        "syncEnabled" to autoSync,
                        "consentAccepted" to accepted,
                        "consentVersion" to "1.0",
                        "consentUpdatedAt" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path, auth)
            throw e
        }

        val consentEntries = mapOf(
            "master_sync" to accepted,
            "call_history" to callConsent,
            "sms_history" to smsConsent,
            "recordings" to recordingConsent
        )
        consentEntries.forEach { (scope, granted) ->
            val docId = "${uid}_${deviceId}_$scope"
            val docRef = db.collection("consent_records").document(docId)
            try {
                val snap = docRef.get().await()
                if (snap.exists()) {
                    docRef.update(
                        mapOf(
                            "granted" to granted,
                            "policyVersion" to "1.0",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } else {
                    docRef.set(
                        mapOf(
                            "ownerUserId" to uid,
                            "deviceId" to deviceId,
                            "source" to "consent_flow",
                            "consentType" to scope,
                            "granted" to granted,
                            "policyVersion" to "1.0",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            }
        }

        appendAuditLog(
            uid,
            deviceId,
            if (accepted) "CONSENT_GRANTED" else "CONSENT_REVOKED",
            "Calls=$callConsent, SMS=$smsConsent, Recordings=$recordingConsent, AutoSync=$autoSync"
        )
    }

    suspend fun refreshLocalCacheFromDevice(): Pair<Int, Int> {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val prefs = preferences.state.first()
        if (!prefs.consentAccepted) return 0 to 0

        var callsAdded = 0
        var smsAdded = 0

        if (prefs.callHistoryConsent) {
            val calls = collector.collectAuthorizedCalls(uid, deviceId)
            if (calls.isNotEmpty()) {
                localDb.callDao().insertAllIgnoreDuplicates(calls)
                callsAdded = calls.size
            }
        }
        if (prefs.smsHistoryConsent) {
            val messages = collector.collectAuthorizedSms(uid, deviceId)
            if (messages.isNotEmpty()) {
                localDb.smsDao().insertAllIgnoreDuplicates(messages)
                smsAdded = messages.size
            }
        }
        return callsAdded to smsAdded
    }

    suspend fun importSmsFile(uri: android.net.Uri): Int {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val imported = collector.importSmsFromDocument(uri, uid, deviceId)
        if (imported.isNotEmpty()) {
            localDb.smsDao().insertAllIgnoreDuplicates(imported)
            appendAuditLog(uid, deviceId, "SMS_IMPORTED", "Imported ${imported.size} SMS records via SAF")
        }
        return imported.size
    }

    suspend fun importRecordingFile(
        uri: android.net.Uri,
        phoneNumber: String,
        contactName: String,
        authorizedForSync: Boolean
    ): LocalRecordingEntity {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val entity = collector.importEncryptedRecordingFromUri(
            uri = uri,
            uid = uid,
            deviceId = deviceId,
            phoneNumber = phoneNumber,
            contactName = contactName,
            authorizedForSync = authorizedForSync
        )
        localDb.recordingDao().upsertRecording(entity)
        appendAuditLog(uid, deviceId, "RECORDING_ADDED", "Encrypted local recording ${entity.fileName}")
        return entity
    }

    suspend fun toggleRecordingSyncAuthorization(recordingId: String, authorized: Boolean) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        localDb.recordingDao().setSyncAuthorization(uid, recordingId, authorized)
        appendAuditLog(uid, deviceId, "RECORDING_SYNC_AUTH", "Recording $recordingId authorizedForSync=$authorized")
    }

    suspend fun deleteLocalAndCloudRecording(recording: LocalRecordingEntity) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        withContext(Dispatchers.IO) {
            runCatching { File(recording.encryptedFilePath).delete() }
        }
        localDb.recordingDao().deleteById(uid, recording.id)
        val docRef = db.collection("recordings").document(recording.id)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
        }
        appendAuditLog(uid, deviceId, "RECORDING_DELETED", "Deleted recording ${recording.fileName}")
    }

    suspend fun decryptRecordingToTempFile(context: Context, recording: LocalRecordingEntity): File =
        withContext(Dispatchers.IO) {
            val encFile = File(recording.encryptedFilePath)
            require(encFile.exists()) { "Encrypted recording file is missing on this device." }
            val decrypted = crypto.decryptBytes(encFile.readBytes())
            val tempFile = File.createTempFile("play_${recording.id}_", ".tmp", context.cacheDir)
            tempFile.writeBytes(decrypted)
            tempFile
        }

    suspend fun performIncrementalSync(source: String = "manual_sync"): SyncExecutionResult {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val prefs = preferences.state.first()
        if (!prefs.consentAccepted) {
            throw IllegalStateException("Explicit user consent is required before synchronization.")
        }

        ensureUserAndDeviceRegistered()
        updateSyncStatus(uid, deviceId, source, "syncing", 0, 0, 0, null)

        try {
            refreshLocalCacheFromDevice()

            var syncedCalls = 0
            var syncedSms = 0
            var syncedRecordings = 0

            if (prefs.callHistoryConsent) {
                val unsyncedCalls = localDb.callDao().getUnsyncedCalls(uid)
                unsyncedCalls.chunked(200).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { call ->
                        val docRef = db.collection("call_records").document(call.id)
                        batch.set(
                            docRef,
                            mapOf(
                                "ownerUserId" to uid,
                                "deviceId" to call.deviceId,
                                "source" to call.source,
                                "recordHash" to call.recordHash,
                                "phoneNumber" to call.phoneNumber,
                                "contactName" to call.contactName.take(120),
                                "callType" to call.callType,
                                "callTimestampMillis" to call.callTimestampMillis,
                                "durationSeconds" to call.durationSeconds,
                                "syncedAt" to FieldValue.serverTimestamp(),
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    batch.commit().await()
                    localDb.callDao().markSynced(chunk.map { it.id })
                    syncedCalls += chunk.size
                }
            }

            if (prefs.smsHistoryConsent) {
                val unsyncedSms = localDb.smsDao().getUnsyncedSms(uid)
                unsyncedSms.chunked(200).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { sms ->
                        val docRef = db.collection("sms_records").document(sms.id)
                        batch.set(
                            docRef,
                            mapOf(
                                "ownerUserId" to uid,
                                "deviceId" to sms.deviceId,
                                "source" to sms.source,
                                "recordHash" to sms.recordHash,
                                "address" to sms.address,
                                "contactName" to sms.contactName.take(120),
                                "messageBody" to decryptSmsBody(sms.encryptedBody).take(4000),
                                "direction" to sms.direction,
                                "deliveryStatus" to sms.deliveryStatus,
                                "messageTimestampMillis" to sms.messageTimestampMillis,
                                "syncedAt" to FieldValue.serverTimestamp(),
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    batch.commit().await()
                    localDb.smsDao().markSynced(chunk.map { it.id })
                    syncedSms += chunk.size
                }
            }

            if (prefs.recordingSyncConsent) {
                val unsyncedRecs = localDb.recordingDao().getAuthorizedUnsyncedRecordings(uid)
                if (unsyncedRecs.isNotEmpty()) {
                    val batch = db.batch()
                    unsyncedRecs.forEach { rec ->
                        val docRef = db.collection("recordings").document(rec.id)
                        batch.set(
                            docRef,
                            mapOf(
                                "ownerUserId" to uid,
                                "deviceId" to rec.deviceId,
                                "source" to rec.source,
                                "phoneNumber" to rec.phoneNumber,
                                "contactName" to rec.contactName.take(120),
                                "fileName" to rec.fileName.take(200),
                                "mimeType" to rec.mimeType.take(64),
                                "durationSeconds" to rec.durationSeconds,
                                "sizeBytes" to rec.sizeBytes,
                                "encryptionAlgorithm" to rec.encryptionAlgorithm,
                                "authorizedForSync" to rec.authorizedForSync,
                                "recordedAtMillis" to rec.recordedAtMillis,
                                "syncedAt" to FieldValue.serverTimestamp(),
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    batch.commit().await()
                    localDb.recordingDao().markSynced(unsyncedRecs.map { it.id })
                    syncedRecordings = unsyncedRecs.size
                }
            }

            preferences.setLastSyncMillis(System.currentTimeMillis())
            db.collection("devices").document(deviceId).update(
                mapOf(
                    "lastSyncAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            updateSyncStatus(uid, deviceId, source, "success", syncedCalls, syncedSms, syncedRecordings, null)
            appendAuditLog(
                uid,
                deviceId,
                "SYNC_COMPLETED",
                "Synced $syncedCalls calls, $syncedSms SMS, $syncedRecordings recordings ($source)"
            )
            return SyncExecutionResult(syncedCalls, syncedSms, syncedRecordings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "sync_pipeline", auth)
            runCatching {
                updateSyncStatus(uid, deviceId, source, "error", 0, 0, 0, e.localizedMessage?.take(450))
            }
            throw e
        }
    }

    private suspend fun updateSyncStatus(
        uid: String,
        deviceId: String,
        source: String,
        status: String,
        callsCount: Int,
        smsCount: Int,
        recordingsCount: Int,
        errorMsg: String?
    ) {
        val docId = "${uid}_$deviceId"
        val docRef = db.collection("sync_status").document(docId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "source" to source,
                    "status" to status,
                    "callsSyncedCount" to callsCount.toLong(),
                    "smsSyncedCount" to smsCount.toLong(),
                    "recordingsSyncedCount" to recordingsCount.toLong(),
                    "lastSyncAt" to FieldValue.serverTimestamp()
                )
                if (errorMsg != null) {
                    updates["errorMessage"] = errorMsg
                }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUserId" to uid,
                    "deviceId" to deviceId,
                    "source" to source,
                    "status" to status,
                    "callsSyncedCount" to callsCount.toLong(),
                    "smsSyncedCount" to smsCount.toLong(),
                    "recordingsSyncedCount" to recordingsCount.toLong(),
                    "lastSyncAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
                if (errorMsg != null) {
                    payload["errorMessage"] = errorMsg
                }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
        }
    }

    suspend fun grantAuthorizedShare(viewerEmail: String, scope: String) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val normalized = viewerEmail.trim().lowercase()
        require(normalized.contains("@") && normalized.length >= 3) { "Enter a valid viewer email address." }
        val shareId = "share_${KeystoreCryptoManager.sha256Hex("$uid|$normalized").take(24)}"
        val docRef = db.collection("authorized_shares").document(shareId)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "scope" to scope,
                        "status" to "active",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "ownerUserId" to uid,
                        "deviceId" to deviceId,
                        "source" to "privacy_dashboard",
                        "viewerEmail" to normalized,
                        "scope" to scope,
                        "status" to "active",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            appendAuditLog(uid, deviceId, "SHARE_GRANTED", "Authorized $normalized for scope=$scope")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun revokeAuthorizedShare(share: AuthorizedShare) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val docRef = db.collection("authorized_shares").document(share.id)
        try {
            docRef.update(
                mapOf(
                    "status" to "revoked",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAuditLog(uid, deviceId, "SHARE_REVOKED", "Revoked access for ${share.viewerEmail}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun disconnectDevice(deviceIdToDisconnect: String) {
        val uid = requireUserId()
        val currentDeviceId = preferences.getOrCreateDeviceId(uid)
        val docRef = db.collection("devices").document(deviceIdToDisconnect)
        try {
            docRef.update(
                mapOf(
                    "isConnected" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            appendAuditLog(uid, currentDeviceId, "DEVICE_DISCONNECTED", "Disconnected device $deviceIdToDisconnect")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun buildPersonalDataExportJson(): String = withContext(Dispatchers.IO) {
        val uid = requireUserId()
        val calls = localDb.callDao().getAllCallsSnapshot(uid)
        val sms = localDb.smsDao().getAllSmsSnapshot(uid)
        val recordings = localDb.recordingDao().getAllRecordingsSnapshot(uid)

        val root = JSONObject()
        root.put("exportedAtMillis", System.currentTimeMillis())
        root.put("ownerUserId", uid)

        val callsArray = JSONArray()
        calls.forEach { c ->
            callsArray.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("phoneNumber", c.phoneNumber)
                    put("contactName", c.contactName)
                    put("callType", c.callType)
                    put("callTimestampMillis", c.callTimestampMillis)
                    put("durationSeconds", c.durationSeconds)
                    put("source", c.source)
                }
            )
        }
        root.put("calls", callsArray)

        val smsArray = JSONArray()
        sms.forEach { m ->
            smsArray.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("address", m.address)
                    put("contactName", m.contactName)
                    put("direction", m.direction)
                    put("deliveryStatus", m.deliveryStatus)
                    put("messageTimestampMillis", m.messageTimestampMillis)
                    put("messageBody", decryptSmsBody(m.encryptedBody))
                    put("source", m.source)
                }
            )
        }
        root.put("sms", smsArray)

        val recArray = JSONArray()
        recordings.forEach { r ->
            recArray.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("fileName", r.fileName)
                    put("phoneNumber", r.phoneNumber)
                    put("durationSeconds", r.durationSeconds)
                    put("encryptionAlgorithm", r.encryptionAlgorithm)
                    put("authorizedForSync", r.authorizedForSync)
                }
            )
        }
        root.put("recordings", recArray)
        root.toString(2)
    }

    suspend fun deleteAllCloudData(keepAuditNote: Boolean = true) {
        val uid = requireUserId()
        val deviceId = preferences.getOrCreateDeviceId(uid)
        val collections = listOf(
            "call_records",
            "sms_records",
            "recordings",
            "consent_records",
            "sync_status",
            "authorized_shares",
            "devices",
            "audit_logs"
        )
        for (col in collections) {
            try {
                val snap = db.collection(col).whereEqualTo("ownerUserId", uid).get().await()
                snap.documents.chunked(200).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { doc -> batch.delete(doc.reference) }
                    batch.commit().await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, col, auth)
                throw e
            }
        }
        if (keepAuditNote) {
            appendAuditLog(uid, deviceId, "CLOUD_DATA_DELETED", "Owner wiped all synchronized cloud records")
        }
    }

    suspend fun deleteAccountAndAllData() {
        val uid = requireUserId()
        deleteAllCloudData(keepAuditNote = false)
        val userRef = db.collection("users").document(uid)
        try {
            userRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userRef.path, auth)
            throw e
        }
        withContext(Dispatchers.IO) {
            localDb.recordingDao().getAllRecordingsSnapshot(uid).forEach { rec ->
                runCatching { File(rec.encryptedFilePath).delete() }
            }
        }
        localDb.callDao().deleteAllForUser(uid)
        localDb.smsDao().deleteAllForUser(uid)
        localDb.recordingDao().deleteAllForUser(uid)
        preferences.clearAll()
        auth.currentUser?.delete()?.await()
    }

    suspend fun appendAuditLog(uid: String, deviceId: String, action: String, details: String) {
        val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docRef = db.collection("audit_logs").document(logId)
        try {
            docRef.set(
                mapOf(
                    "ownerUserId" to uid,
                    "deviceId" to deviceId,
                    "source" to "android_app",
                    "action" to action.take(64),
                    "details" to details.take(500),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
        }
    }

    private fun handleFlowError(error: Throwable, op: OperationType, path: String, uid: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<com.google.firebase.firestore.FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: error
        if (auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path, auth)
        }
    }
}
