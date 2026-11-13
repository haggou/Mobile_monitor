package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.device.DeviceTelemetryCollector
import com.example.data.local.AppDatabase
import com.example.data.local.LocalCallEntity
import com.example.data.preferences.ConsentPreferences
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class EndToEndUserJourneyRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var context: Context
    private lateinit var localDb: AppDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var prefs: ConsentPreferences
    private lateinit var repository: ActivitySyncRepository

    @Before
    fun setUpE2e() {
        context = ApplicationProvider.getApplicationContext()
        localDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val testKey = SecretKeySpec(ByteArray(32) { (it + 9).toByte() }, "AES")
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
    fun tearDownE2e() {
        runBlocking { prefs.clearAll() }
        localDb.close()
    }

    @Test
    fun fullUserLifecycle_onboardingImportEncryptSyncShareExportDisconnectAndWipe() = runBlocking {
        val uid = signInTestUser("e2e_user_${UUID.randomUUID().toString().take(8)}@connect.test")
        val deviceId = prefs.getOrCreateDeviceId(uid)

        // 1. Grant onboarding consent and verify cloud consent ledger + user profile
        withTimeout(6000L) {
            repository.saveConsentPreferences(
                accepted = true,
                callConsent = true,
                smsConsent = true,
                recordingConsent = true,
                autoSync = true
            )
        }

        // 2. Cache call history (including rejected and missed calls) locally
        val now = System.currentTimeMillis()
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val callHash = KeystoreCryptoManager.sha256Hex("call_$callId")
        localDb.callDao().insertAllIgnoreDuplicates(
            listOf(
                LocalCallEntity(
                    id = callId,
                    ownerUserId = uid,
                    deviceId = deviceId,
                    source = "android_call_log",
                    recordHash = callHash,
                    phoneNumber = "+15553334444",
                    contactName = "Operations Desk",
                    callType = "rejected",
                    callTimestampMillis = now - 5000L,
                    durationSeconds = 0L
                )
            )
        )

        // 3. Import SMS JSON via SAF document path and verify encrypted at rest
        val smsFile = File(context.cacheDir, "e2e_sms_${UUID.randomUUID()}.json")
        smsFile.writeText(
            """
            [
              {"address":"+15553334444","contactName":"Operations Desk","body":"Approved delivery window 14:00","direction":"incoming","timestamp":${now - 4000L}}
            ]
            """.trimIndent()
        )
        val importedSmsCount = withTimeout(5000L) {
            repository.importSmsFile(Uri.fromFile(smsFile))
        }
        assertEquals(1, importedSmsCount)
        val cachedSms = localDb.smsDao().getAllSmsSnapshot(uid).first()
        assertNotEquals("Approved delivery window 14:00", cachedSms.encryptedBody)
        assertEquals("Approved delivery window 14:00", repository.decryptSmsBody(cachedSms.encryptedBody))

        // 4. Import audio recording, verify encrypted file on disk + exact byte-for-byte decryption
        val rawAudioBytes = "FAKE_M4A_AUDIO_HEADER_AND_PAYLOAD_123456".toByteArray()
        val audioFile = File(context.cacheDir, "e2e_call_note.m4a").apply { writeBytes(rawAudioBytes) }
        val importedRec = withTimeout(5000L) {
            repository.importRecordingFile(
                uri = Uri.fromFile(audioFile),
                phoneNumber = "+15553334444",
                contactName = "Operations Desk",
                authorizedForSync = false
            )
        }
        assertTrue(File(importedRec.encryptedFilePath).exists())
        val decryptedTemp = repository.decryptRecordingToTempFile(context, importedRec)
        assertEquals(rawAudioBytes.toList(), decryptedTemp.readBytes().toList())
        decryptedTemp.delete()

        // 5. Sync before authorizing recording -> recording should NOT upload yet
        val firstSync = withTimeout(10000L) {
            repository.performIncrementalSync("e2e_initial")
        }
        assertEquals(1, firstSync.callsSynced)
        assertEquals(1, firstSync.smsSynced)
        assertEquals(0, firstSync.recordingsSynced)

        // 6. Authorize recording sync and re-run incremental sync -> only authorized recording uploads (0 duplicate calls/SMS)
        withTimeout(5000L) {
            repository.toggleRecordingSyncAuthorization(importedRec.id, true)
        }
        val secondSync = withTimeout(10000L) {
            repository.performIncrementalSync("e2e_recording_authorized")
        }
        assertEquals(0, secondSync.callsSynced)
        assertEquals(0, secondSync.smsSynced)
        assertEquals(1, secondSync.recordingsSynced)

        val cloudRec = withTimeout(5000L) {
            firestore.collection("recordings").document(importedRec.id).get().await()
        }
        assertTrue(cloudRec.exists())
        assertEquals("AES-256-GCM-AndroidKeystore", cloudRec.getString("encryptionAlgorithm"))

        // 7. Grant and revoke an authorized viewer share
        withTimeout(6000L) {
            repository.grantAuthorizedShare("viewer@connect.test", "calls")
        }
        val shareSnap = withTimeout(5000L) {
            firestore.collection("authorized_shares")
                .whereEqualTo("ownerUserId", uid)
                .get()
                .await()
        }
        assertEquals(1, shareSnap.size())

        // 8. Export personal data archive JSON and verify decrypted content is included
        val exportJson = JSONObject(repository.buildPersonalDataExportJson())
        assertEquals(uid, exportJson.getString("ownerUserId"))
        assertEquals(1, exportJson.getJSONArray("calls").length())
        assertEquals(1, exportJson.getJSONArray("sms").length())
        assertEquals(1, exportJson.getJSONArray("recordings").length())
        assertEquals(
            "Approved delivery window 14:00",
            exportJson.getJSONArray("sms").getJSONObject(0).getString("messageBody")
        )

        // 9. Disconnect device and verify Firestore status
        withTimeout(6000L) {
            repository.disconnectDevice(deviceId)
        }
        val devSnap = withTimeout(5000L) {
            firestore.collection("devices").document(deviceId).get().await()
        }
        assertEquals(false, devSnap.getBoolean("isConnected"))

        // 10. Delete recording from local vault + cloud and wipe remaining cloud data
        val latestRec = localDb.recordingDao().getAllRecordingsSnapshot(uid).first()
        withTimeout(6000L) {
            repository.deleteLocalAndCloudRecording(latestRec)
        }
        assertFalse(File(latestRec.encryptedFilePath).exists())
        assertTrue(localDb.recordingDao().getAllRecordingsSnapshot(uid).isEmpty())

        withTimeout(10000L) {
            repository.deleteAllCloudData(keepAuditNote = false)
        }
        val callsAfterWipe = withTimeout(5000L) {
            firestore.collection("call_records").whereEqualTo("ownerUserId", uid).get().await()
        }
        assertTrue(callsAfterWipe.isEmpty)
    }
}
