package com.example.ui.viewmodel

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.device.DeviceTelemetryCollector
import com.example.data.device.PermissionStatusSnapshot
import com.example.data.local.LocalCallEntity
import com.example.data.local.LocalRecordingEntity
import com.example.data.local.LocalSmsEntity
import com.example.data.model.AuditLog
import com.example.data.model.AuthorizedShare
import com.example.data.model.DeviceRecord
import com.example.data.model.SyncStatusRecord
import com.example.data.preferences.ConsentPreferences
import com.example.data.preferences.PrivacyPreferencesState
import com.example.data.repository.ActivitySyncRepository
import com.example.worker.ActivitySyncWorker
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class DecryptedSmsItem(
    val entity: LocalSmsEntity,
    val body: String
)

enum class CallDateRange(val label: String, val millisWindow: Long?) {
    ALL("All Time", null),
    TODAY("24h", 24L * 60 * 60 * 1000),
    WEEK("7 Days", 7L * 24 * 60 * 60 * 1000),
    MONTH("30 Days", 30L * 24 * 60 * 60 * 1000)
}

class ConnectViewModel(
    private val repository: ActivitySyncRepository,
    private val preferences: ConsentPreferences,
    private val collector: DeviceTelemetryCollector,
    val currentUserId: String
) : ViewModel() {

    private val _permissionSnapshot = MutableStateFlow(collector.getPermissionSnapshot())
    val permissionSnapshot: StateFlow<PermissionStatusSnapshot> = _permissionSnapshot.asStateFlow()

    private val _callSearchQuery = MutableStateFlow("")
    val callSearchQuery: StateFlow<String> = _callSearchQuery.asStateFlow()

    private val _callTypeFilter = MutableStateFlow("all")
    val callTypeFilter: StateFlow<String> = _callTypeFilter.asStateFlow()

    private val _callDateRange = MutableStateFlow(CallDateRange.ALL)
    val callDateRange: StateFlow<CallDateRange> = _callDateRange.asStateFlow()

    private val _callPageLimit = MutableStateFlow(PAGE_SIZE)
    val callPageLimit: StateFlow<Int> = _callPageLimit.asStateFlow()

    private val _smsSearchQuery = MutableStateFlow("")
    val smsSearchQuery: StateFlow<String> = _smsSearchQuery.asStateFlow()

    private val _smsDirectionFilter = MutableStateFlow("all")
    val smsDirectionFilter: StateFlow<String> = _smsDirectionFilter.asStateFlow()

    private val _smsPageLimit = MutableStateFlow(PAGE_SIZE)
    val smsPageLimit: StateFlow<Int> = _smsPageLimit.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _playingRecordingId = MutableStateFlow<String?>(null)
    val playingRecordingId: StateFlow<String?> = _playingRecordingId.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var activeTempAudioFile: File? = null

    val privacyState: StateFlow<PrivacyPreferencesState> = preferences.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = PrivacyPreferencesState()
        )

    val allCalls: StateFlow<List<LocalCallEntity>> = repository.observeLocalCalls(currentUserId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val filteredCalls: StateFlow<List<LocalCallEntity>> = combine(
        allCalls,
        _callSearchQuery,
        _callTypeFilter,
        _callDateRange
    ) { calls, query, typeFilter, dateRange ->
        val now = System.currentTimeMillis()
        val minTs = dateRange.millisWindow?.let { now - it } ?: 0L
        val q = query.trim().lowercase()
        calls.filter { call ->
            val matchesType = typeFilter == "all" || call.callType == typeFilter
            val matchesDate = call.callTimestampMillis >= minTs
            val matchesQuery = q.isEmpty() ||
                call.phoneNumber.lowercase().contains(q) ||
                call.contactName.lowercase().contains(q)
            matchesType && matchesDate && matchesQuery
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )

    val allSms: StateFlow<List<DecryptedSmsItem>> = repository.observeLocalSms(currentUserId)
        .map { list ->
            list.map { DecryptedSmsItem(entity = it, body = repository.decryptSmsBody(it.encryptedBody)) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val filteredSms: StateFlow<List<DecryptedSmsItem>> = combine(
        allSms,
        _smsSearchQuery,
        _smsDirectionFilter
    ) { items, query, dirFilter ->
        val q = query.trim().lowercase()
        items.filter { item ->
            val matchesDir = dirFilter == "all" || item.entity.direction == dirFilter
            val matchesQuery = q.isEmpty() ||
                item.entity.address.lowercase().contains(q) ||
                item.entity.contactName.lowercase().contains(q) ||
                item.body.lowercase().contains(q)
            matchesDir && matchesQuery
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )

    val recordings: StateFlow<List<LocalRecordingEntity>> = repository.observeLocalRecordings(currentUserId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val devicesState: StateFlow<UiState<List<DeviceRecord>>> = repository.observeDevices(currentUserId)
        .map<List<DeviceRecord>, UiState<List<DeviceRecord>>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(unwrapFirestoreMsg(error, "Failed to load devices"))) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val syncStatusesState: StateFlow<UiState<List<SyncStatusRecord>>> = repository.observeSyncStatuses(currentUserId)
        .map<List<SyncStatusRecord>, UiState<List<SyncStatusRecord>>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(unwrapFirestoreMsg(error, "Failed to load sync status"))) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val authorizedSharesState: StateFlow<UiState<List<AuthorizedShare>>> = repository.observeAuthorizedShares(currentUserId)
        .map<List<AuthorizedShare>, UiState<List<AuthorizedShare>>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(unwrapFirestoreMsg(error, "Failed to load shares"))) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val auditLogsState: StateFlow<UiState<List<AuditLog>>> = repository.observeAuditLogs(currentUserId)
        .map<List<AuditLog>, UiState<List<AuditLog>>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(unwrapFirestoreMsg(error, "Failed to load audit logs"))) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    init {
        viewModelScope.launch {
            runCatching { repository.ensureUserAndDeviceRegistered() }
                .onFailure { Log.w(TAG, "Initial device registration deferred: ${it.message}") }
        }
    }

    fun refreshPermissionSnapshot() {
        _permissionSnapshot.value = collector.getPermissionSnapshot()
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    fun updateCallSearch(query: String) {
        _callSearchQuery.value = query
        _callPageLimit.value = PAGE_SIZE
    }

    fun updateCallTypeFilter(type: String) {
        _callTypeFilter.value = type
        _callPageLimit.value = PAGE_SIZE
    }

    fun updateCallDateRange(range: CallDateRange) {
        _callDateRange.value = range
        _callPageLimit.value = PAGE_SIZE
    }

    fun loadMoreCalls() {
        _callPageLimit.value += PAGE_SIZE
    }

    fun updateSmsSearch(query: String) {
        _smsSearchQuery.value = query
        _smsPageLimit.value = PAGE_SIZE
    }

    fun updateSmsDirectionFilter(direction: String) {
        _smsDirectionFilter.value = direction
        _smsPageLimit.value = PAGE_SIZE
    }

    fun loadMoreSms() {
        _smsPageLimit.value += PAGE_SIZE
    }

    fun saveConsent(
        context: Context,
        accepted: Boolean,
        callConsent: Boolean,
        smsConsent: Boolean,
        recordingConsent: Boolean,
        autoSync: Boolean,
        wifiOnly: Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                preferences.setWifiOnly(wifiOnly)
                repository.saveConsentPreferences(accepted, callConsent, smsConsent, recordingConsent, autoSync)
                ActivitySyncWorker.schedulePeriodicSync(context.applicationContext, accepted && autoSync, wifiOnly)
                if (accepted) {
                    repository.refreshLocalCacheFromDevice()
                }
            }.onSuccess {
                _bannerMessage.value = if (accepted) "Privacy & consent preferences saved." else "Cloud sync declined. Operating in local-only mode."
            }.onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Failed to save consent preferences."
            }
        }
    }

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoSync(enabled)
            val wifiOnly = privacyState.value.wifiOnlySync
            ActivitySyncWorker.schedulePeriodicSync(context.applicationContext, enabled && privacyState.value.consentAccepted, wifiOnly)
            _bannerMessage.value = if (enabled) "Background sync scheduled." else "Background sync paused."
        }
    }

    fun toggleWifiOnly(context: Context, wifiOnly: Boolean) {
        viewModelScope.launch {
            preferences.setWifiOnly(wifiOnly)
            val auto = privacyState.value.autoSyncEnabled && privacyState.value.consentAccepted
            ActivitySyncWorker.schedulePeriodicSync(context.applicationContext, auto, wifiOnly)
        }
    }

    fun refreshFromDevice() {
        viewModelScope.launch {
            refreshPermissionSnapshot()
            runCatching { repository.refreshLocalCacheFromDevice() }
                .onSuccess { (calls, sms) ->
                    _bannerMessage.value = "Scanned $calls call(s) and $sms SMS record(s) into encrypted cache."
                }
                .onFailure {
                    _bannerMessage.value = it.localizedMessage ?: "Failed to refresh device records."
                }
        }
    }

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            runCatching { repository.performIncrementalSync("manual_sync") }
                .onSuccess { res ->
                    _bannerMessage.value = "Synced ${res.callsSynced} calls, ${res.smsSynced} SMS, ${res.recordingsSynced} recordings."
                }
                .onFailure {
                    _bannerMessage.value = it.localizedMessage ?: "Synchronization failed."
                }
            _isSyncing.value = false
        }
    }

    fun importSmsDocument(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.importSmsFile(uri) }
                .onSuccess { count ->
                    _bannerMessage.value = "Imported $count encrypted SMS record(s)."
                }
                .onFailure {
                    _bannerMessage.value = it.localizedMessage ?: "Unable to parse SMS export file."
                }
        }
    }

    fun importRecording(uri: Uri, phoneNumber: String, contactName: String, authorizeSync: Boolean) {
        viewModelScope.launch {
            runCatching { repository.importRecordingFile(uri, phoneNumber, contactName, authorizeSync) }
                .onSuccess { rec ->
                    _bannerMessage.value = "Encrypted '${rec.fileName}' with Android Keystore."
                }
                .onFailure {
                    _bannerMessage.value = it.localizedMessage ?: "Failed to import recording."
                }
        }
    }

    fun toggleRecordingSyncAuth(recordingId: String, authorized: Boolean) {
        viewModelScope.launch {
            runCatching { repository.toggleRecordingSyncAuthorization(recordingId, authorized) }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to update recording sync authorization." }
        }
    }

    fun togglePlayRecording(context: Context, recording: LocalRecordingEntity) {
        if (_playingRecordingId.value == recording.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        viewModelScope.launch {
            runCatching {
                val tempFile = repository.decryptRecordingToTempFile(context, recording)
                activeTempAudioFile = tempFile
                val player = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener { stopAudioPlayback() }
                    prepare()
                    start()
                }
                mediaPlayer = player
                _playingRecordingId.value = recording.id
            }.onFailure {
                stopAudioPlayback()
                _bannerMessage.value = it.localizedMessage ?: "Unable to play decrypted recording."
            }
        }
    }

    fun stopAudioPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        activeTempAudioFile?.let { runCatching { it.delete() } }
        activeTempAudioFile = null
        _playingRecordingId.value = null
    }

    fun deleteRecording(recording: LocalRecordingEntity) {
        if (_playingRecordingId.value == recording.id) stopAudioPlayback()
        viewModelScope.launch {
            runCatching { repository.deleteLocalAndCloudRecording(recording) }
                .onSuccess { _bannerMessage.value = "Recording deleted from device and cloud." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to delete recording." }
        }
    }

    fun grantShare(email: String, scope: String) {
        viewModelScope.launch {
            runCatching { repository.grantAuthorizedShare(email, scope) }
                .onSuccess { _bannerMessage.value = "Authorized viewer $email ($scope)." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to authorize viewer." }
        }
    }

    fun revokeShare(share: AuthorizedShare) {
        viewModelScope.launch {
            runCatching { repository.revokeAuthorizedShare(share) }
                .onSuccess { _bannerMessage.value = "Revoked viewer access for ${share.viewerEmail}." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to revoke share." }
        }
    }

    fun disconnectDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching { repository.disconnectDevice(deviceId) }
                .onSuccess { _bannerMessage.value = "Device disconnected." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to disconnect device." }
        }
    }

    fun exportPersonalData(context: Context, destinationUri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = repository.buildPersonalDataExportJson()
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Cannot open export destination.")
            }.onSuccess {
                _bannerMessage.value = "Exported personal data archive (JSON)."
            }.onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Failed to export personal data."
            }
        }
    }

    fun revokeConsent(context: Context) {
        viewModelScope.launch {
            runCatching {
                preferences.revokeAllConsent()
                ActivitySyncWorker.schedulePeriodicSync(context.applicationContext, false, true)
                repository.saveConsentPreferences(
                    accepted = false,
                    callConsent = false,
                    smsConsent = false,
                    recordingConsent = false,
                    autoSync = false
                )
            }.onSuccess {
                _bannerMessage.value = "Consent revoked. Background sync stopped."
            }.onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Failed to revoke consent."
            }
        }
    }

    fun deleteCloudData() {
        viewModelScope.launch {
            runCatching { repository.deleteAllCloudData() }
                .onSuccess { _bannerMessage.value = "All cloud-synchronized data deleted." }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to delete cloud data." }
        }
    }

    fun deleteAccount(context: Context, onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                ActivitySyncWorker.schedulePeriodicSync(context.applicationContext, false, true)
                repository.deleteAccountAndAllData()
            }.onSuccess {
                onDeleted()
            }.onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Account deletion requires recent sign-in. Sign out and sign back in to delete."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
    }

    private fun unwrapFirestoreMsg(error: Throwable, fallback: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, fallback, rootError)
        return rootError.message ?: fallback
    }

    private companion object {
        const val TAG = "ConnectViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val PAGE_SIZE = 25
    }
}
