package com.example.data.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.data.local.LocalCallEntity
import com.example.data.local.LocalRecordingEntity
import com.example.data.local.LocalSmsEntity
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class PermissionStatusSnapshot(
    val callLogGranted: Boolean,
    val contactsGranted: Boolean,
    val smsGranted: Boolean,
    val notificationsGranted: Boolean,
    val callRecordingSupported: Boolean
)

class DeviceTelemetryCollector(
    private val context: Context,
    private val crypto: KeystoreCryptoManager
) {
    fun getPermissionSnapshot(): PermissionStatusSnapshot {
        val callLog = isGranted(Manifest.permission.READ_CALL_LOG)
        val contacts = isGranted(Manifest.permission.READ_CONTACTS)
        val sms = isGranted(Manifest.permission.READ_SMS)
        val notifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return PermissionStatusSnapshot(
            callLogGranted = callLog,
            contactsGranted = contacts,
            smsGranted = sms,
            notificationsGranted = notifications,
            callRecordingSupported = isNativeCallRecordingSupported()
        )
    }

    /**
     * Standard Android 10+ devices restrict cellular call audio capture to privileged system
     * dialers holding CAPTURE_AUDIO_OUTPUT. We never use Accessibility Services, hidden APIs,
     * root, or microphone workarounds.
     */
    fun isNativeCallRecordingSupported(): Boolean {
        val hasCaptureOutput = ContextCompat.checkSelfPermission(
            context,
            "android.permission.CAPTURE_AUDIO_OUTPUT"
        ) == PackageManager.PERMISSION_GRANTED
        return hasCaptureOutput
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun collectAuthorizedCalls(uid: String, deviceId: String): List<LocalCallEntity> =
        withContext(Dispatchers.IO) {
            if (!isGranted(Manifest.permission.READ_CALL_LOG)) return@withContext emptyList()
            val canReadContacts = isGranted(Manifest.permission.READ_CONTACTS)
            val records = mutableListOf<LocalCallEntity>()
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                var count = 0
                while (cursor.moveToNext() && count < MAX_SCAN_BATCH) {
                    val number = cursor.getString(numberIdx)?.ifBlank { "Unknown" } ?: "Unknown"
                    val cachedName = cursor.getString(nameIdx).orEmpty()
                    val contactName = when {
                        cachedName.isNotBlank() -> cachedName
                        canReadContacts && number != "Unknown" -> lookupContactName(number)
                        else -> ""
                    }
                    val callType = when (cursor.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "incoming"
                    }
                    val dateMillis = cursor.getLong(dateIdx)
                    val duration = cursor.getLong(durIdx).coerceAtLeast(0L)
                    val hash = KeystoreCryptoManager.sha256Hex("$uid|$deviceId|$number|$callType|$dateMillis|$duration")
                    records.add(
                        LocalCallEntity(
                            id = "call_${hash.take(24)}",
                            ownerUserId = uid,
                            deviceId = deviceId,
                            source = "android_call_log",
                            recordHash = hash,
                            phoneNumber = number,
                            contactName = contactName,
                            callType = callType,
                            callTimestampMillis = dateMillis,
                            durationSeconds = duration
                        )
                    )
                    count++
                }
            }
            records
        }

    suspend fun collectAuthorizedSms(uid: String, deviceId: String): List<LocalSmsEntity> =
        withContext(Dispatchers.IO) {
            if (!isGranted(Manifest.permission.READ_SMS)) return@withContext emptyList()
            val canReadContacts = isGranted(Manifest.permission.READ_CONTACTS)
            val records = mutableListOf<LocalSmsEntity>()
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.STATUS
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val statusIdx = cursor.getColumnIndex(Telephony.Sms.STATUS)
                var count = 0
                while (cursor.moveToNext() && count < MAX_SCAN_BATCH) {
                    val address = cursor.getString(addrIdx)?.ifBlank { "Unknown" } ?: "Unknown"
                    val body = cursor.getString(bodyIdx).orEmpty().take(3500)
                    val direction = if (cursor.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        "outgoing"
                    } else {
                        "incoming"
                    }
                    val dateMillis = cursor.getLong(dateIdx)
                    val deliveryStatus = when (cursor.getInt(statusIdx)) {
                        Telephony.Sms.STATUS_COMPLETE -> "delivered"
                        Telephony.Sms.STATUS_PENDING -> "pending"
                        Telephony.Sms.STATUS_FAILED -> "failed"
                        else -> if (direction == "outgoing") "sent" else "received"
                    }
                    val contactName = if (canReadContacts && address != "Unknown") lookupContactName(address) else ""
                    val hash = KeystoreCryptoManager.sha256Hex("$uid|$deviceId|$address|$direction|$dateMillis|${body.hashCode()}")
                    records.add(
                        LocalSmsEntity(
                            id = "sms_${hash.take(24)}",
                            ownerUserId = uid,
                            deviceId = deviceId,
                            source = "android_sms_provider",
                            recordHash = hash,
                            address = address,
                            contactName = contactName,
                            encryptedBody = crypto.encryptText(body),
                            direction = direction,
                            deliveryStatus = deliveryStatus,
                            messageTimestampMillis = dateMillis
                        )
                    )
                    count++
                }
            }
            records
        }

    suspend fun importSmsFromDocument(uri: Uri, uid: String, deviceId: String): List<LocalSmsEntity> =
        withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext emptyList()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val results = mutableListOf<LocalSmsEntity>()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val address = obj.optString("address", "Unknown").ifBlank { "Unknown" }
                    val body = obj.optString("body", "").take(3500)
                    val direction = obj.optString("direction", "incoming").let {
                        if (it == "outgoing") "outgoing" else "incoming"
                    }
                    val ts = obj.optLong("timestamp", System.currentTimeMillis())
                    val status = obj.optString("deliveryStatus", if (direction == "outgoing") "sent" else "received")
                    val contact = obj.optString("contactName", "")
                    val hash = KeystoreCryptoManager.sha256Hex("$uid|$deviceId|$address|$direction|$ts|${body.hashCode()}")
                    results.add(
                        LocalSmsEntity(
                            id = "sms_${hash.take(24)}",
                            ownerUserId = uid,
                            deviceId = deviceId,
                            source = "user_import",
                            recordHash = hash,
                            address = address,
                            contactName = contact,
                            encryptedBody = crypto.encryptText(body),
                            direction = direction,
                            deliveryStatus = status,
                            messageTimestampMillis = ts
                        )
                    )
                }
            } else {
                trimmed.lines().filter { it.isNotBlank() }.forEachIndexed { idx, line ->
                    if (idx == 0 && line.lowercase().startsWith("address,")) return@forEachIndexed
                    val parts = if (line.contains("|")) line.split("|", limit = 4) else line.split(",", limit = 4)
                    val address = parts.getOrNull(0)?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
                    val direction = parts.getOrNull(1)?.trim()?.lowercase().let {
                        if (it == "outgoing" || it == "sent") "outgoing" else "incoming"
                    }
                    val ts = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: (System.currentTimeMillis() - idx * 1000L)
                    val body = parts.getOrNull(3)?.trim()?.take(3500) ?: line.take(3500)
                    val hash = KeystoreCryptoManager.sha256Hex("$uid|$deviceId|$address|$direction|$ts|${body.hashCode()}")
                    results.add(
                        LocalSmsEntity(
                            id = "sms_${hash.take(24)}",
                            ownerUserId = uid,
                            deviceId = deviceId,
                            source = "user_import",
                            recordHash = hash,
                            address = address,
                            contactName = "",
                            encryptedBody = crypto.encryptText(body),
                            direction = direction,
                            deliveryStatus = if (direction == "outgoing") "sent" else "received",
                            messageTimestampMillis = ts
                        )
                    )
                }
            }
            results
        }

    suspend fun importEncryptedRecordingFromUri(
        uri: Uri,
        uid: String,
        deviceId: String,
        phoneNumber: String,
        contactName: String,
        authorizedForSync: Boolean
    ): LocalRecordingEntity = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read selected audio file.")
        val originalName = resolveFileName(uri) ?: "recording_${System.currentTimeMillis()}.m4a"
        val mimeType = context.contentResolver.getType(uri) ?: "audio/mp4"
        val durationSeconds = extractDurationSeconds(uri)

        val encryptedBytes = crypto.encryptBytes(rawBytes)
        val dir = File(context.filesDir, "encrypted_recordings").apply { mkdirs() }
        val recId = "rec_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val targetFile = File(dir, "$recId.enc")
        targetFile.writeBytes(encryptedBytes)

        LocalRecordingEntity(
            id = recId,
            ownerUserId = uid,
            deviceId = deviceId,
            source = "user_imported_audio",
            callRecordId = null,
            phoneNumber = phoneNumber.ifBlank { "Unlinked" },
            contactName = contactName,
            fileName = originalName,
            encryptedFilePath = targetFile.absolutePath,
            mimeType = mimeType,
            durationSeconds = durationSeconds,
            sizeBytes = encryptedBytes.size.toLong(),
            encryptionAlgorithm = "AES-256-GCM-AndroidKeystore",
            authorizedForSync = authorizedForSync,
            syncedToCloud = false,
            recordedAtMillis = System.currentTimeMillis()
        )
    }

    private fun resolveFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun extractDurationSeconds(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun lookupContactName(phoneNumber: String): String {
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return runCatching {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            } ?: ""
        }.getOrDefault("")
    }

    companion object {
        private const val MAX_SCAN_BATCH = 500
    }
}
