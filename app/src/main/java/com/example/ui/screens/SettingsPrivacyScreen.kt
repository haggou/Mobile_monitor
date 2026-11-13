package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.device.PermissionStatusSnapshot
import com.example.data.model.AuthorizedShare
import com.example.data.model.DeviceRecord
import com.example.data.preferences.PrivacyPreferencesState
import com.example.ui.viewmodel.UiState

@Composable
fun SettingsPrivacyScreen(
    userEmail: String,
    privacyState: PrivacyPreferencesState,
    permissionSnapshot: PermissionStatusSnapshot,
    devicesState: UiState<List<DeviceRecord>>,
    sharesState: UiState<List<AuthorizedShare>>,
    onToggleAutoSync: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onReviewConsent: () -> Unit,
    onRevokeConsent: () -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onGrantShare: (String, String) -> Unit,
    onRevokeShare: (AuthorizedShare) -> Unit,
    onExportPersonalData: (Uri) -> Unit,
    onDeleteCloudData: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    var shareEmailInput by remember { mutableStateOf("") }
    var shareScope by remember { mutableStateOf("calls") }
    var confirmDeleteCloud by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportPersonalData(uri)
    }

    if (confirmDeleteCloud) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCloud = false },
            title = { Text("Delete All Cloud Data?") },
            text = { Text("This permanently deletes all synchronized call, SMS, recording metadata, device, and share records from Cloud Firestore.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmDeleteCloud = false
                        onDeleteCloudData()
                    }
                ) { Text("Delete Cloud Data") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCloud = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAccount = false },
            title = { Text("Delete Account Permanently?") },
            text = { Text("Deletes your Firebase account, all cloud records, and all local encrypted vault data.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmDeleteAccount = false
                        onDeleteAccount()
                    }
                ) { Text("Delete Account") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAccount = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Privacy & Settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Signed in as ${userEmail.ifBlank { "Verified Google Account" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permission Status", style = MaterialTheme.typography.titleLarge)
                    PermissionRow("Call Log (READ_CALL_LOG)", permissionSnapshot.callLogGranted)
                    PermissionRow("Contacts (READ_CONTACTS)", permissionSnapshot.contactsGranted)
                    PermissionRow("SMS (READ_SMS)", permissionSnapshot.smsGranted)
                    PermissionRow("Native Call Recording", permissionSnapshot.callRecordingSupported)
                    OutlinedButton(onClick = onReviewConsent, modifier = Modifier.fillMaxWidth()) {
                        Text("Review & Update Consent Scopes")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Synchronization Controls", style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background WorkManager Sync", style = MaterialTheme.typography.titleMedium)
                            Text("Requires active consent", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = privacyState.autoSyncEnabled,
                            onCheckedChange = onToggleAutoSync,
                            enabled = privacyState.consentAccepted,
                            modifier = Modifier.testTag("settings_autosync_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unmetered Wi-Fi Only", style = MaterialTheme.typography.titleMedium)
                            Text("Pause uploads on cellular data", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = privacyState.wifiOnlySync,
                            onCheckedChange = onToggleWifiOnly,
                            modifier = Modifier.testTag("settings_wifi_switch")
                        )
                    }
                    if (privacyState.consentAccepted) {
                        OutlinedButton(onClick = onRevokeConsent, modifier = Modifier.fillMaxWidth()) {
                            Text("Revoke Sync Consent")
                        }
                    }
                }
            }
        }

        item {
            Text("Connected Devices", style = MaterialTheme.typography.titleLarge)
        }

        when (devicesState) {
            is UiState.Success -> {
                items(devicesState.data, key = { it.id }) { dev ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Android ${dev.osVersion} • ${if (dev.isConnected) "Connected" else "Disconnected"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (dev.isConnected) {
                                OutlinedButton(onClick = { onDisconnectDevice(dev.deviceId) }) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                }
            }
            is UiState.Error -> {
                item { Text(devicesState.message, color = MaterialTheme.colorScheme.error) }
            }
            UiState.Loading -> {
                item { Text("Loading devices…") }
            }
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Authorized Viewers", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = shareEmailInput,
                        onValueChange = { shareEmailInput = it },
                        label = { Text("Authorized Viewer Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("calls", "sms", "all").forEach { sc ->
                            FilterChip(
                                selected = shareScope == sc,
                                onClick = { shareScope = sc },
                                label = { Text(sc.uppercase()) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (shareEmailInput.isNotBlank()) {
                                onGrantShare(shareEmailInput, shareScope)
                                shareEmailInput = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Text(" Authorize Viewer")
                    }

                    if (sharesState is UiState.Success && sharesState.data.isNotEmpty()) {
                        sharesState.data.forEach { share ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(share.viewerEmail, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${share.scope} • ${share.status}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (share.status == "active") {
                                    TextButton(onClick = { onRevokeShare(share) }) {
                                        Text("Revoke")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Data Portability & Deletion", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(
                        onClick = { exportLauncher.launch("connect_activity_export.json") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_data_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(" Export Personal Data (JSON)")
                    }
                    OutlinedButton(
                        onClick = { confirmDeleteCloud = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_cloud_button")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" Delete Cloud Data")
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Text(" Sign Out")
                    }
                    Button(
                        onClick = { confirmDeleteAccount = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_account_button")
                    ) {
                        Text("Delete Account Permanently")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (granted) "GRANTED" else "NOT GRANTED",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
