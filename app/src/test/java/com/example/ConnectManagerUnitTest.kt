package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.device.DeviceTelemetryCollector
import com.example.security.KeystoreCryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectManagerUnitTest {

    @Test
    fun aesGcmEncryption_roundTripsTextAndBytes() {
        val key = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")
        val crypto = KeystoreCryptoManager(overrideKeyForTesting = key)

        val secret = "Confidential authorized SMS body: code 482910"
        val cipher = crypto.encryptText(secret)
        assertNotEquals(secret, cipher)
        assertEquals(secret, crypto.decryptText(cipher))

        val audioBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val encBytes = crypto.encryptBytes(audioBytes)
        val decBytes = crypto.decryptBytes(encBytes)
        assertEquals(audioBytes.toList(), decBytes.toList())
    }

    @Test
    fun sha256DeduplicationHash_isDeterministic() {
        val h1 = KeystoreCryptoManager.sha256Hex("uid1|dev1|+15550100|incoming|1700000000|60")
        val h2 = KeystoreCryptoManager.sha256Hex("uid1|dev1|+15550100|incoming|1700000000|60")
        val h3 = KeystoreCryptoManager.sha256Hex("uid1|dev1|+15550100|outgoing|1700000000|60")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
    }

    @Test
    fun permissionSnapshot_defaultsToDeniedAndUnsupportedCallRecording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val collector = DeviceTelemetryCollector(context, KeystoreCryptoManager(overrideKeyForTesting = key))
        val snapshot = collector.getPermissionSnapshot()
        assertFalse(snapshot.callLogGranted)
        assertFalse(snapshot.smsGranted)
        assertFalse(snapshot.callRecordingSupported)
    }
}
