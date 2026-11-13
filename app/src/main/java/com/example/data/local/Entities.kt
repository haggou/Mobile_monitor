package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_calls",
    indices = [Index(value = ["ownerUserId", "recordHash"], unique = true)]
)
data class LocalCallEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String,
    val deviceId: String,
    val source: String,
    val recordHash: String,
    val phoneNumber: String,
    val contactName: String,
    val callType: String, // incoming, outgoing, missed, rejected
    val callTimestampMillis: Long,
    val durationSeconds: Long,
    val syncedToCloud: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "local_sms",
    indices = [Index(value = ["ownerUserId", "recordHash"], unique = true)]
)
data class LocalSmsEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String,
    val deviceId: String,
    val source: String,
    val recordHash: String,
    val address: String,
    val contactName: String,
    val encryptedBody: String,
    val direction: String, // incoming, outgoing
    val deliveryStatus: String,
    val messageTimestampMillis: Long,
    val syncedToCloud: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_recordings")
data class LocalRecordingEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String,
    val deviceId: String,
    val source: String,
    val callRecordId: String?,
    val phoneNumber: String,
    val contactName: String,
    val fileName: String,
    val encryptedFilePath: String,
    val mimeType: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val encryptionAlgorithm: String,
    val authorizedForSync: Boolean = false,
    val syncedToCloud: Boolean = false,
    val recordedAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis()
)
