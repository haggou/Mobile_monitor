package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.device.DeviceTelemetryCollector
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsImportAndUiStateTest {

    @Test
    fun importSmsFromJsonAndCsv_encryptsAndParsesRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val crypto = KeystoreCryptoManager(overrideKeyForTesting = key)
        val collector = DeviceTelemetryCollector(context, crypto)

        val jsonFile = File(context.cacheDir, "sms_export_test.json")
        jsonFile.writeText(
            """
            [
              {"address":"+15551112222","body":"Hello from JSON","direction":"incoming","timestamp":1700000001000},
              {"address":"+15553334444","body":"Reply from JSON","direction":"outgoing","timestamp":1700000002000}
            ]
            """.trimIndent()
        )
        val parsedJson = collector.importSmsFromDocument(Uri.fromFile(jsonFile), "uid_1", "dev_1")
        assertEquals(2, parsedJson.size)
        assertEquals("incoming", parsedJson[0].direction)
        assertEquals("Hello from JSON", crypto.decryptText(parsedJson[0].encryptedBody))
        assertEquals("outgoing", parsedJson[1].direction)

        val csvFile = File(context.cacheDir, "sms_export_test.csv")
        csvFile.writeText(
            "address,direction,timestamp,body\n+15557778888,incoming,1700000003000,CSV authorized test\n"
        )
        val parsedCsv = collector.importSmsFromDocument(Uri.fromFile(csvFile), "uid_1", "dev_1")
        assertEquals(1, parsedCsv.size)
        assertEquals("+15557778888", parsedCsv[0].address)
        assertEquals("CSV authorized test", crypto.decryptText(parsedCsv[0].encryptedBody))
    }
}
