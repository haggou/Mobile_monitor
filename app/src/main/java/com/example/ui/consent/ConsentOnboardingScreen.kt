package com.example.ui.consent

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun ConsentOnboardingScreen(
    onSaveConsent: (
        accepted: Boolean,
        callConsent: Boolean,
        smsConsent: Boolean,
        recordingConsent: Boolean,
        autoSync: Boolean,
        wifiOnly: Boolean
    ) -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    var callOptIn by remember { mutableStateOf(true) }
    var smsOptIn by remember { mutableStateOf(false) }
    var recordingOptIn by remember { mutableStateOf(false) }
    var autoSyncOptIn by remember { mutableStateOf(true) }
    var wifiOnlyOptIn by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
        onSaveConsent(true, callOptIn, smsOptIn, recordingOptIn, autoSyncOptIn, wifiOnlyOptIn)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.consent_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                Text(
                    text = stringResource(R.string.consent_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DisclosureCard(
                    title = stringResource(R.string.consent_what_collected_title),
                    body = stringResource(R.string.consent_what_collected_body)
                )
                DisclosureCard(
                    title = stringResource(R.string.consent_cloud_sync_title),
                    body = stringResource(R.string.consent_cloud_sync_body)
                )
                DisclosureCard(
                    title = stringResource(R.string.consent_sharing_title),
                    body = stringResource(R.string.consent_sharing_body)
                )

                Text(
                    text = "Choose Permitted Scopes",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )

                ConsentToggleRow(
                    icon = Icons.Default.Call,
                    title = "Call History & Contacts",
                    subtitle = "Requests READ_CALL_LOG and optional READ_CONTACTS",
                    checked = callOptIn,
                    onCheckedChange = { callOptIn = it },
                    tag = "consent_call_switch"
                )
                ConsentToggleRow(
                    icon = Icons.Default.Sms,
                    title = "Authorized SMS Records",
                    subtitle = "Optional READ_SMS if eligible, or manual JSON/CSV import anytime",
                    checked = smsOptIn,
                    onCheckedChange = { smsOptIn = it },
                    tag = "consent_sms_switch"
                )
                ConsentToggleRow(
                    icon = Icons.Default.Mic,
                    title = "Recording Vault Metadata Sync",
                    subtitle = "Syncs metadata for recordings you explicitly import and authorize",
                    checked = recordingOptIn,
                    onCheckedChange = { recordingOptIn = it },
                    tag = "consent_recording_switch"
                )
                ConsentToggleRow(
                    icon = Icons.Default.CloudSync,
                    title = "Background WorkManager Sync",
                    subtitle = "Periodic Wi-Fi aware incremental sync with duplicate prevention",
                    checked = autoSyncOptIn,
                    onCheckedChange = { autoSyncOptIn = it },
                    tag = "consent_autosync_switch"
                )

                Button(
                    onClick = {
                        val permissionsToRequest = buildList {
                            if (callOptIn) {
                                add(Manifest.permission.READ_CALL_LOG)
                                add(Manifest.permission.READ_CONTACTS)
                            }
                            if (smsOptIn) {
                                add(Manifest.permission.READ_SMS)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (permissionsToRequest.isNotEmpty()) {
                            permissionLauncher.launch(permissionsToRequest.toTypedArray())
                        } else {
                            onSaveConsent(true, callOptIn, smsOptIn, recordingOptIn, autoSyncOptIn, wifiOnlyOptIn)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("grant_consent_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.consent_grant_button))
                }

                OutlinedButton(
                    onClick = {
                        onSaveConsent(false, false, false, false, false, true)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("decline_consent_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.consent_decline_button))
                }
            }
        }
    }
}

@Composable
private fun DisclosureCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConsentToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag)
            )
        }
    }
}
