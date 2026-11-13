package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.device.DeviceTelemetryCollector
import com.example.data.local.AppDatabase
import com.example.data.preferences.ConsentPreferences
import com.example.data.repository.ActivitySyncRepository
import com.example.security.KeystoreCryptoManager
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOut
import com.example.ui.consent.ConsentOnboardingScreen
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MessagesScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsPrivacyScreen
import com.example.ui.theme.ConnectActivityTheme
import com.example.ui.viewmodel.ConnectViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

enum class MainTab(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    CALLS("calls", R.string.nav_calls, Icons.Default.Call),
    MESSAGES("messages", R.string.nav_messages, Icons.Default.Sms),
    RECORDINGS("recordings", R.string.nav_recordings, Icons.Default.GraphicEq),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectActivityTheme {
                AppContent()
            }
        }
    }
}

@Composable
fun AppContent() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { currentUser = Firebase.auth.currentUser })
    } else {
        androidx.compose.runtime.key(user.uid) {
            AuthenticatedShell(
                currentUserId = user.uid,
                userEmail = user.email.orEmpty(),
                onSignedOut = { currentUser = Firebase.auth.currentUser }
            )
        }
    }
}

@Composable
fun AuthenticatedShell(
    currentUserId: String,
    userEmail: String,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: ConnectViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val auth = FirebaseAuth.getInstance()
                val localDb = AppDatabase.getInstance(app)
                val crypto = KeystoreCryptoManager()
                val prefs = ConsentPreferences(app)
                val collector = DeviceTelemetryCollector(app, crypto)
                val repo = ActivitySyncRepository(db, auth, localDb, crypto, prefs, collector)
                ConnectViewModel(repo, prefs, collector, currentUserId)
            }
        }
    )

    val privacyState by viewModel.privacyState.collectAsStateWithLifecycle()
    val permissionSnapshot by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val allCalls by viewModel.allCalls.collectAsStateWithLifecycle()
    val filteredCalls by viewModel.filteredCalls.collectAsStateWithLifecycle()
    val callLimit by viewModel.callPageLimit.collectAsStateWithLifecycle()
    val callQuery by viewModel.callSearchQuery.collectAsStateWithLifecycle()
    val callType by viewModel.callTypeFilter.collectAsStateWithLifecycle()
    val callDateRange by viewModel.callDateRange.collectAsStateWithLifecycle()
    val allSms by viewModel.allSms.collectAsStateWithLifecycle()
    val filteredSms by viewModel.filteredSms.collectAsStateWithLifecycle()
    val smsLimit by viewModel.smsPageLimit.collectAsStateWithLifecycle()
    val smsQuery by viewModel.smsSearchQuery.collectAsStateWithLifecycle()
    val smsDirection by viewModel.smsDirectionFilter.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val devicesState by viewModel.devicesState.collectAsStateWithLifecycle()
    val sharesState by viewModel.authorizedSharesState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val playingId by viewModel.playingRecordingId.collectAsStateWithLifecycle()

    var showConsentEditor by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    if (showConsentEditor || !privacyState.hasCompletedOnboarding) {
        BackHandler(enabled = showConsentEditor) { showConsentEditor = false }
        ConsentOnboardingScreen(
            onSaveConsent = { accepted, call, sms, rec, auto, wifi ->
                viewModel.saveConsent(context, accepted, call, sms, rec, auto, wifi)
                showConsentEditor = false
            },
            onRefreshPermissions = { viewModel.refreshPermissionSnapshot() }
        )
        return
    }

    if (currentTab != MainTab.HOME) {
        BackHandler { currentTab = MainTab.HOME }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        MainTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail {
                        MainTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }

                when (currentTab) {
                    MainTab.HOME -> HomeScreen(
                        privacyState = privacyState,
                        permissionSnapshot = permissionSnapshot,
                        calls = allCalls,
                        smsItems = allSms,
                        recordings = recordings,
                        devicesState = devicesState,
                        auditLogsState = auditLogsState,
                        isSyncing = isSyncing,
                        onSyncNow = { viewModel.syncNow() },
                        onRefreshDevice = { viewModel.refreshFromDevice() },
                        onOpenConsentReview = { showConsentEditor = true }
                    )
                    MainTab.CALLS -> CallsScreen(
                        calls = filteredCalls.take(callLimit),
                        totalMatchedCount = filteredCalls.size,
                        searchQuery = callQuery,
                        selectedType = callType,
                        selectedDateRange = callDateRange,
                        permissionSnapshot = permissionSnapshot,
                        onSearchChanged = viewModel::updateCallSearch,
                        onTypeSelected = viewModel::updateCallTypeFilter,
                        onDateRangeSelected = viewModel::updateCallDateRange,
                        onLoadMore = viewModel::loadMoreCalls,
                        onRefreshDevice = { viewModel.refreshFromDevice() },
                        onPermissionsUpdated = { viewModel.refreshPermissionSnapshot() }
                    )
                    MainTab.MESSAGES -> MessagesScreen(
                        smsItems = filteredSms.take(smsLimit),
                        totalMatchedCount = filteredSms.size,
                        searchQuery = smsQuery,
                        selectedDirection = smsDirection,
                        permissionSnapshot = permissionSnapshot,
                        onSearchChanged = viewModel::updateSmsSearch,
                        onDirectionSelected = viewModel::updateSmsDirectionFilter,
                        onLoadMore = viewModel::loadMoreSms,
                        onImportSmsUri = { viewModel.importSmsDocument(it) },
                        onRefreshDevice = { viewModel.refreshFromDevice() },
                        onPermissionsUpdated = { viewModel.refreshPermissionSnapshot() }
                    )
                    MainTab.RECORDINGS -> RecordingsScreen(
                        recordings = recordings,
                        permissionSnapshot = permissionSnapshot,
                        playingRecordingId = playingId,
                        onImportRecording = { uri, phone, contact, authSync ->
                            viewModel.importRecording(uri, phone, contact, authSync)
                        },
                        onTogglePlay = { viewModel.togglePlayRecording(context, it) },
                        onToggleSyncAuth = { id, auth -> viewModel.toggleRecordingSyncAuth(id, auth) },
                        onDeleteRecording = { viewModel.deleteRecording(it) }
                    )
                    MainTab.SETTINGS -> SettingsPrivacyScreen(
                        userEmail = userEmail,
                        privacyState = privacyState,
                        permissionSnapshot = permissionSnapshot,
                        devicesState = devicesState,
                        sharesState = sharesState,
                        onToggleAutoSync = { viewModel.toggleAutoSync(context, it) },
                        onToggleWifiOnly = { viewModel.toggleWifiOnly(context, it) },
                        onReviewConsent = { showConsentEditor = true },
                        onRevokeConsent = { viewModel.revokeConsent(context) },
                        onDisconnectDevice = { viewModel.disconnectDevice(it) },
                        onGrantShare = { email, scopeVal -> viewModel.grantShare(email, scopeVal) },
                        onRevokeShare = { viewModel.revokeShare(it) },
                        onExportPersonalData = { uri -> viewModel.exportPersonalData(context, uri) },
                        onDeleteCloudData = { viewModel.deleteCloudData() },
                        onSignOut = {
                            signOut(credentialManager, scope, onSignOutComplete = onSignedOut)
                        },
                        onDeleteAccount = {
                            viewModel.deleteAccount(context, onDeleted = onSignedOut)
                        }
                    )
                }
            }
        }
    }
}
