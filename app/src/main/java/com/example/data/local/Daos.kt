package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM local_calls WHERE ownerUserId = :uid ORDER BY callTimestampMillis DESC")
    fun observeCalls(uid: String): Flow<List<LocalCallEntity>>

    @Query("SELECT * FROM local_calls WHERE ownerUserId = :uid AND syncedToCloud = 0 ORDER BY callTimestampMillis DESC")
    suspend fun getUnsyncedCalls(uid: String): List<LocalCallEntity>

    @Query("SELECT * FROM local_calls WHERE ownerUserId = :uid ORDER BY callTimestampMillis DESC")
    suspend fun getAllCallsSnapshot(uid: String): List<LocalCallEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreDuplicates(calls: List<LocalCallEntity>)

    @Query("UPDATE local_calls SET syncedToCloud = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM local_calls WHERE ownerUserId = :uid")
    suspend fun deleteAllForUser(uid: String)
}

@Dao
interface SmsDao {
    @Query("SELECT * FROM local_sms WHERE ownerUserId = :uid ORDER BY messageTimestampMillis DESC")
    fun observeSms(uid: String): Flow<List<LocalSmsEntity>>

    @Query("SELECT * FROM local_sms WHERE ownerUserId = :uid AND syncedToCloud = 0 ORDER BY messageTimestampMillis DESC")
    suspend fun getUnsyncedSms(uid: String): List<LocalSmsEntity>

    @Query("SELECT * FROM local_sms WHERE ownerUserId = :uid ORDER BY messageTimestampMillis DESC")
    suspend fun getAllSmsSnapshot(uid: String): List<LocalSmsEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreDuplicates(messages: List<LocalSmsEntity>)

    @Query("UPDATE local_sms SET syncedToCloud = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM local_sms WHERE ownerUserId = :uid")
    suspend fun deleteAllForUser(uid: String)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM local_recordings WHERE ownerUserId = :uid ORDER BY recordedAtMillis DESC")
    fun observeRecordings(uid: String): Flow<List<LocalRecordingEntity>>

    @Query("SELECT * FROM local_recordings WHERE ownerUserId = :uid AND authorizedForSync = 1 AND syncedToCloud = 0")
    suspend fun getAuthorizedUnsyncedRecordings(uid: String): List<LocalRecordingEntity>

    @Query("SELECT * FROM local_recordings WHERE ownerUserId = :uid ORDER BY recordedAtMillis DESC")
    suspend fun getAllRecordingsSnapshot(uid: String): List<LocalRecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecording(recording: LocalRecordingEntity)

    @Query("UPDATE local_recordings SET authorizedForSync = :authorized WHERE id = :id AND ownerUserId = :uid")
    suspend fun setSyncAuthorization(uid: String, id: String, authorized: Boolean)

    @Query("UPDATE local_recordings SET syncedToCloud = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM local_recordings WHERE id = :id AND ownerUserId = :uid")
    suspend fun deleteById(uid: String, id: String)

    @Query("DELETE FROM local_recordings WHERE ownerUserId = :uid")
    suspend fun deleteAllForUser(uid: String)
}
