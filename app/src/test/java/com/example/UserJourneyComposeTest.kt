package com.example

import android.content.Context
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Before
import com.example.data.device.PermissionStatusSnapshot
import com.example.data.local.LocalCallEntity
import com.example.data.local.LocalRecordingEntity
import com.example.data.local.LocalSmsEntity
import com.example.data.model.AuditLog
import com.example.data.model.AuthorizedShare
import com.example.data.model.DeviceRecord
import com.example.data.preferences.PrivacyPreferencesState
import com.example.ui.auth.AuthScreen
import com.example.ui.consent.ConsentOnboardingScreen
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MessagesScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsPrivacyScreen
import com.example.ui.theme.ConnectActivityTheme
import com.example.ui.viewmodel.CallDateRange
import com.example.ui.viewmodel.DecryptedSmsItem
import com.example.ui.viewmodel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xhdpi")
class UserJourneyComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun ensureFirebaseInitialized() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.connectactivity.kxmpzq")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }
    }

    @Test
    fun authScreen_rendersDisclosuresAndGoogleSignInAction() {
        composeTestRule.setContent {
            ConnectActivityTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connect Activity").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Explicit Consent First").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Keystore-Backed Local Encryption").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_sign_in_button").assertIsEnabled()
    }

    @Test
    fun consentOnboarding_userCanToggleScopesDeclineOrGrantWithPermissions() {
        var lastSavedAccepted: Boolean? = null
        var lastCallConsent = false
        var lastSmsConsent = false
        var lastRecConsent = false
        var permissionsRefreshed = false

        val autoGrantRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    val perms = input as? Array<*>
                    val result = perms?.filterIsInstance<String>()?.associateWith { true } ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, result as O)
                }
            }
        }

        composeTestRule.setContent {
            CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides autoGrantRegistryOwner) {
                ConnectActivityTheme {
                    ConsentOnboardingScreen(
                        onSaveConsent = { accepted, call, sms, rec, _, _ ->
                            lastSavedAccepted = accepted
                            lastCallConsent = call
                            lastSmsConsent = sms
                            lastRecConsent = rec
                        },
                        onRefreshPermissions = { permissionsRefreshed = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("consent_sms_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("consent_recording_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("grant_consent_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(permissionsRefreshed)
        assertEquals(true, lastSavedAccepted)
        assertTrue(lastCallConsent)
        assertTrue(lastSmsConsent)
        assertTrue(lastRecConsent)

        composeTestRule.onNodeWithTag("decline_consent_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(false, lastSavedAccepted)
        assertFalse(lastCallConsent)
        assertFalse(lastSmsConsent)
        assertFalse(lastRecConsent)
    }

    @Test
    fun homeScreen_displaysActivityCountsAuditLogsAndTriggersSyncAndScan() {
        var syncClicked = false
        var scanClicked = false

        val now = System.currentTimeMillis()
        val sampleCalls = listOf(
            LocalCallEntity("c1", "u1", "d1", "android_call_log", "h1", "+15550101", "Alice", "incoming", now, 40),
            LocalCallEntity("c2", "u1", "d1", "android_call_log", "h2", "+15550102", "Bob", "incoming", now - 1000, 15),
            LocalCallEntity("c3", "u1", "d1", "android_call_log", "h3", "+15550103", "Carol", "outgoing", now - 2000, 90),
            LocalCallEntity("c4", "u1", "d1", "android_call_log", "h4", "+15550104", "", "missed", now - 3000, 0)
        )
        val sampleSms = listOf(
            DecryptedSmsItem(
                LocalSmsEntity("s1", "u1", "d1", "user_import", "hs1", "+15550101", "Alice", "enc", "incoming", "received", now),
                "Confirmed for 3 PM"
            ),
            DecryptedSmsItem(
                LocalSmsEntity("s2", "u1", "d1", "user_import", "hs2", "+15550103", "Carol", "enc2", "outgoing", "sent", now - 500),
                "On my way"
            )
        )
        val sampleRecs = listOf(
            LocalRecordingEntity("r1", "u1", "d1", "user_imported_audio", null, "+15550101", "Alice", "note.m4a", "/tmp/r1.enc", "audio/mp4", 42, 1024, "AES-256-GCM-AndroidKeystore", false, false, now)
        )
        val sampleAudit = listOf(
            AuditLog(id = "a1", ownerUserId = "u1", deviceId = "d1", source = "android_app", action = "SYNC_COMPLETED", details = "Synced 4 calls")
        )

        composeTestRule.setContent {
            ConnectActivityTheme {
                HomeScreen(
                    privacyState = PrivacyPreferencesState(deviceId = "d1", hasCompletedOnboarding = true, consentAccepted = true, lastSyncMillis = now),
                    permissionSnapshot = PermissionStatusSnapshot(callLogGranted = true, contactsGranted = true, smsGranted = false, notificationsGranted = true, callRecordingSupported = false),
                    calls = sampleCalls,
                    smsItems = sampleSms,
                    recordings = sampleRecs,
                    devicesState = UiState.Success(listOf(DeviceRecord(id = "d1", ownerUserId = "u1", deviceId = "d1", deviceName = "Pixel 9", isConnected = true))),
                    auditLogsState = UiState.Success(sampleAudit),
                    isSyncing = false,
                    onSyncNow = { syncClicked = true },
                    onRefreshDevice = { scanClicked = true },
                    onOpenConsentReview = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Consent Active • Encrypted Vault").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 / 1").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1").assertCountEquals(3)
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("SYNC_COMPLETED"))
        composeTestRule.onNodeWithText("SYNC_COMPLETED").assertIsDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("sync_now_button"))
        composeTestRule.onNodeWithTag("sync_now_button").performClick()
        composeTestRule.onNodeWithTag("scan_device_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(syncClicked)
        assertTrue(scanClicked)
    }

    @Test
    fun callsScreen_supportsSearchTypeFilterDateRangeAndPagination() {
        val now = System.currentTimeMillis()
        val generatedCalls = (1..30).map { i ->
            LocalCallEntity(
                id = "call_$i",
                ownerUserId = "u1",
                deviceId = "d1",
                source = "android_call_log",
                recordHash = "hash_$i",
                phoneNumber = "+1555000$i",
                contactName = "Contact $i",
                callType = if (i % 2 == 0) "incoming" else "missed",
                callTimestampMillis = now - i * 1000L,
                durationSeconds = i * 5L,
                syncedToCloud = i <= 10
            )
        }

        var searchText by mutableStateOf("")
        var selectedType by mutableStateOf("all")
        var selectedRange by mutableStateOf(CallDateRange.ALL)
        var pageLimit by mutableStateOf(25)

        composeTestRule.setContent {
            ConnectActivityTheme {
                val filtered = generatedCalls.filter {
                    (selectedType == "all" || it.callType == selectedType) &&
                        (searchText.isBlank() || it.contactName.contains(searchText, ignoreCase = true) || it.phoneNumber.contains(searchText))
                }
                CallsScreen(
                    calls = filtered.take(pageLimit),
                    totalMatchedCount = filtered.size,
                    searchQuery = searchText,
                    selectedType = selectedType,
                    selectedDateRange = selectedRange,
                    permissionSnapshot = PermissionStatusSnapshot(false, false, false, true, false),
                    onSearchChanged = { searchText = it },
                    onTypeSelected = { selectedType = it },
                    onDateRangeSelected = { selectedRange = it },
                    onLoadMore = { pageLimit += 25 },
                    onRefreshDevice = {},
                    onPermissionsUpdated = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Call Log Access Not Granted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("calls_search_input").performTextInput("55500028")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Contact 28").assertIsDisplayed()
        composeTestRule.onNodeWithText("+155500028").assertIsDisplayed()

        searchText = ""
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Missed").performClick()
        composeTestRule.waitForIdle()
        assertEquals("missed", selectedType)

        composeTestRule.onNodeWithText("24h").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CallDateRange.TODAY, selectedRange)

        selectedType = "all"
        selectedRange = CallDateRange.ALL
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Load More (25 of 30)"))
        composeTestRule.onNodeWithText("Load More (25 of 30)").performClick()
        composeTestRule.waitForIdle()
        assertEquals(50, pageLimit)
    }

    @Test
    fun messagesScreen_displaysEncryptedMessagesAndFilters() {
        val now = System.currentTimeMillis()
        val items = listOf(
            DecryptedSmsItem(
                LocalSmsEntity("s1", "u1", "d1", "user_import", "h1", "+15551110000", "Support", "cipher1", "incoming", "received", now),
                "Your verification code is 9124"
            ),
            DecryptedSmsItem(
                LocalSmsEntity("s2", "u1", "d1", "user_import", "h2", "+15552220000", "Alex", "cipher2", "outgoing", "sent", now - 1000),
                "Call me when free"
            )
        )
        var query by mutableStateOf("")
        var direction by mutableStateOf("all")

        composeTestRule.setContent {
            ConnectActivityTheme {
                val filtered = items.filter {
                    (direction == "all" || it.entity.direction == direction) &&
                        (query.isBlank() || it.body.contains(query, ignoreCase = true) || it.entity.contactName.contains(query, ignoreCase = true))
                }
                MessagesScreen(
                    smsItems = filtered,
                    totalMatchedCount = filtered.size,
                    searchQuery = query,
                    selectedDirection = direction,
                    permissionSnapshot = PermissionStatusSnapshot(false, false, false, true, false),
                    onSearchChanged = { query = it },
                    onDirectionSelected = { direction = it },
                    onLoadMore = {},
                    onImportSmsUri = {},
                    onRefreshDevice = {},
                    onPermissionsUpdated = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transparent SMS Handling").assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_sms_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_sms_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your verification code is 9124").assertIsDisplayed()

        composeTestRule.onNodeWithText("Outgoing").performClick()
        composeTestRule.waitForIdle()
        assertEquals("outgoing", direction)
        composeTestRule.onNodeWithText("Call me when free").assertIsDisplayed()

        composeTestRule.onNodeWithTag("sms_search_input").performTextInput("Alex")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Call me when free").assertIsDisplayed()
    }

    @Test
    fun recordingsScreen_showsUnsupportedNoticeAndControlsPlaybackSyncAndDeletion() {
        val now = System.currentTimeMillis()
        val rec = LocalRecordingEntity(
            id = "rec_42",
            ownerUserId = "u1",
            deviceId = "d1",
            source = "user_imported_audio",
            callRecordId = null,
            phoneNumber = "+15558889999",
            contactName = "Client Line",
            fileName = "meeting_summary.m4a",
            encryptedFilePath = "/tmp/rec_42.enc",
            mimeType = "audio/mp4",
            durationSeconds = 75L,
            sizeBytes = 4096L,
            encryptionAlgorithm = "AES-256-GCM-AndroidKeystore",
            authorizedForSync = false,
            syncedToCloud = false,
            recordedAtMillis = now
        )
        var playingId by mutableStateOf<String?>(null)
        var authToggledTo: Boolean? = null
        var deletedId: String? = null

        composeTestRule.setContent {
            ConnectActivityTheme {
                RecordingsScreen(
                    recordings = listOf(rec),
                    permissionSnapshot = PermissionStatusSnapshot(false, false, false, true, callRecordingSupported = false),
                    playingRecordingId = playingId,
                    onImportRecording = { _, _, _, _ -> },
                    onTogglePlay = { playingId = if (playingId == it.id) null else it.id },
                    onToggleSyncAuth = { _, auth -> authToggledTo = auth },
                    onDeleteRecording = { deletedId = it.id }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Call recording is not supported by this device or current Android configuration.").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("meeting_summary.m4a"))
        composeTestRule.onNodeWithText("meeting_summary.m4a").assertIsDisplayed()
        composeTestRule.onNodeWithText("AES-256-GCM-AndroidKeystore").assertIsDisplayed()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("play_recording_rec_42"))
        composeTestRule.onNodeWithTag("play_recording_rec_42").performClick()
        composeTestRule.waitForIdle()
        assertEquals("rec_42", playingId)

        composeTestRule.onNodeWithTag("delete_recording_rec_42").performClick()
        composeTestRule.waitForIdle()
        assertEquals("rec_42", deletedId)
    }

    @Test
    fun settingsPrivacyScreen_handlesTogglesSharesDeviceDisconnectAndDeleteConfirmations() {
        var autoSync by mutableStateOf(true)
        var wifiOnly by mutableStateOf(true)
        var disconnectedDeviceId: String? = null
        var grantedShare: Pair<String, String>? = null
        var revokedShareId: String? = null
        var cloudDeleted = false
        var accountDeleted = false
        var signedOut = false

        val device = DeviceRecord(id = "dev_1", ownerUserId = "u1", deviceId = "dev_1", deviceName = "Pixel 9 Pro", osVersion = "15", isConnected = true)
        val share = AuthorizedShare(id = "share_1", ownerUserId = "u1", deviceId = "dev_1", viewerEmail = "auditor@example.com", scope = "calls", status = "active")

        composeTestRule.setContent {
            ConnectActivityTheme {
                SettingsPrivacyScreen(
                    userEmail = "owner@example.com",
                    privacyState = PrivacyPreferencesState(deviceId = "dev_1", hasCompletedOnboarding = true, consentAccepted = true, autoSyncEnabled = autoSync, wifiOnlySync = wifiOnly),
                    permissionSnapshot = PermissionStatusSnapshot(true, true, false, true, false),
                    devicesState = UiState.Success(listOf(device)),
                    sharesState = UiState.Success(listOf(share)),
                    onToggleAutoSync = { autoSync = it },
                    onToggleWifiOnly = { wifiOnly = it },
                    onReviewConsent = {},
                    onRevokeConsent = {},
                    onDisconnectDevice = { disconnectedDeviceId = it },
                    onGrantShare = { email, scope -> grantedShare = email to scope },
                    onRevokeShare = { revokedShareId = it.id },
                    onExportPersonalData = {},
                    onDeleteCloudData = { cloudDeleted = true },
                    onSignOut = { signedOut = true },
                    onDeleteAccount = { accountDeleted = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Signed in as owner@example.com").assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings_autosync_switch"))
        composeTestRule.onNodeWithTag("settings_autosync_switch").performClick()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings_wifi_switch"))
        composeTestRule.onNodeWithTag("settings_wifi_switch").performClick()
        composeTestRule.waitForIdle()
        assertFalse(autoSync)
        assertFalse(wifiOnly)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Disconnect"))
        composeTestRule.onNodeWithText("Disconnect").performClick()
        composeTestRule.waitForIdle()
        assertEquals("dev_1", disconnectedDeviceId)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Authorized Viewer Email"))
        composeTestRule.onNodeWithText("Authorized Viewer Email").performTextInput("reviewer@example.com")
        composeTestRule.onNodeWithText("SMS").performClick()
        composeTestRule.onNodeWithText(" Authorize Viewer").performClick()
        composeTestRule.waitForIdle()
        assertEquals("reviewer@example.com" to "sms", grantedShare)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Revoke"))
        composeTestRule.onNodeWithText("Revoke").performClick()
        composeTestRule.waitForIdle()
        assertEquals("share_1", revokedShareId)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("delete_cloud_button"))
        composeTestRule.onNodeWithTag("delete_cloud_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete All Cloud Data?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete Cloud Data").performClick()
        composeTestRule.waitForIdle()
        assertTrue(cloudDeleted)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("delete_account_button"))
        composeTestRule.onNodeWithTag("delete_account_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete Account Permanently?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete Account").performClick()
        composeTestRule.waitForIdle()
        assertTrue(accountDeleted)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("sign_out_button"))
        composeTestRule.onNodeWithTag("sign_out_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(signedOut)
    }
}
