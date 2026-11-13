package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.device.DeviceTelemetryCollector
import com.example.data.local.AppDatabase
import com.example.data.local.LocalCallEntity
import com.example.data.local.LocalSmsEntity
import com.example.data.preferences.ConsentPreferences
import com.example.security.KeystoreCryptoManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class ActivitySyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var context: Context
    private lateinit var localDb: AppDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var prefs: ConsentPreferences
    private lateinit var repository: ActivitySyncRepository

    @Before
    fun initDependencies() {
        context = ApplicationProvider.getApplicationContext()
        localDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        crypto = KeystoreCryptoManager(overrideKeyForTesting = testKey)
        prefs = ConsentPreferences(context)
        val collector = DeviceTelemetryCollector(context, crypto)
        repository = ActivitySyncRepository(
            db = firestore,
            auth = auth,
            localDb = localDb,
            crypto = crypto,
            preferences = prefs,
            collector = collector
        )
    }

    @After
    fun closeLocalDb() {
        runBlocking { prefs.clearAll() }
        localDb.close()
    }

    @Test
    fun authenticatedOwner_syncsCallsAndSms_withDuplicatePrevention() = runBlocking {
        val uid = signInTestUser("alice@connect.test")
        val deviceId = prefs.getOrCreateDeviceId(uid)

        withTimeout(5000L) {
            repository.saveConsentPreferences(
                accepted = true,
                callConsent = true,
                smsConsent = true,
                recordingConsent = true,
                autoSync = true
            )
        }

        val callHash = KeystoreCryptoManager.sha256Hex("call_${UUID.randomUUID()}")
        val callId = "call_${callHash.take(20)}"
        val callEntity = LocalCallEntity(
            id = callId,
            ownerUserId = uid,
            deviceId = deviceId,
            source = "android_call_log",
            recordHash = callHash,
            phoneNumber = "+15551234567",
            contactName = "Alice Office",
            callType = "incoming",
            callTimestampMillis = System.currentTimeMillis() - 60_000L,
            durationSeconds = 95L
        )
        localDb.callDao().insertAllIgnoreDuplicates(listOf(callEntity, callEntity))
        assertEquals(1, localDb.callDao().getAllCallsSnapshot(uid).size)

        val smsHash = KeystoreCryptoManager.sha256Hex("sms_${UUID.randomUUID()}")
        val smsId = "sms_${smsHash.take(20)}"
        val smsEntity = LocalSmsEntity(
            id = smsId,
            ownerUserId = uid,
            deviceId = deviceId,
            source = "user_import",
            recordHash = smsHash,
            address = "+15551234567",
            contactName = "Alice Office",
            encryptedBody = crypto.encryptText("Authorized message body"),
            direction = "incoming",
            deliveryStatus = "received",
            messageTimestampMillis = System.currentTimeMillis() - 30_000L
        )
        localDb.smsDao().insertAllIgnoreDuplicates(listOf(smsEntity))

        val syncResult = withTimeout(8000L) {
            repository.performIncrementalSync(source = "test_sync")
        }
        assertEquals(1, syncResult.callsSynced)
        assertEquals(1, syncResult.smsSynced)

        val cloudCall = withTimeout(5000L) {
            firestore.collection("call_records").document(callId).get().await()
        }
        assertTrue(cloudCall.exists())
        assertEquals("+15551234567", cloudCall.getString("phoneNumber"))
    }

    @Test
    fun crossUserAccess_isRejectedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice2@connect.test")
        val deviceId = prefs.getOrCreateDeviceId(aliceUid)

        withTimeout(5000L) {
            repository.saveConsentPreferences(
                accepted = true,
                callConsent = true,
                smsConsent = false,
                recordingConsent = false,
                autoSync = false
            )
        }

        val callHash = KeystoreCryptoManager.sha256Hex("call_iso_${UUID.randomUUID()}")
        val callId = "call_${callHash.take(20)}"
        localDb.callDao().insertAllIgnoreDuplicates(
            listOf(
                LocalCallEntity(
                    id = callId,
                    ownerUserId = aliceUid,
                    deviceId = deviceId,
                    source = "android_call_log",
                    recordHash = callHash,
                    phoneNumber = "+15559990000",
                    contactName = "Private Contact",
                    callType = "outgoing",
                    callTimestampMillis = System.currentTimeMillis() - 10_000L,
                    durationSeconds = 30L
                )
            )
        )

        withTimeout(8000L) {
            repository.performIncrementalSync(source = "test_sync")
        }

        signInTestUser("bob@connect.test")
        val deniedException = runCatching {
            withTimeout(5000L) {
                firestore.collection("call_records").document(callId).get().await()
            }
        }.exceptionOrNull()

        val firestoreEx = deniedException?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun unauthenticatedObserveDevices_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val exception = runCatching {
            withTimeout(4000L) {
                repository.observeDevices("alice_uid").first()
            }
        }.exceptionOrNull()

        val firestoreEx = exception?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }
}
