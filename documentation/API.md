# API & Synchronization Contract

Connect Activity communicates directly with Firebase Authentication (Google Sign-In via Jetpack Credential Manager) and Cloud Firestore over TLS/HTTPS.

## Client-to-Firestore Operations

### 1. Device Registration (`devices/{deviceId}`)
- Executed after Google Sign-In on first session or launch.
- Checks `PackageManager` / `CAPTURE_AUDIO_OUTPUT` to report `callRecordingSupported` honestly.
- Supports `disconnectDevice(deviceId)` which sets `isConnected = false` and appends an `audit_logs` entry.

### 2. Incremental Sync (`ActivitySyncRepository.performIncrementalSync`)
- Triggered manually via **Sync Now** or periodically via `ActivitySyncWorker` (WorkManager, 6h interval, exponential backoff, unmetered Wi-Fi constraint option).
- Queries Room for `syncedToCloud = 0` rows only when `consentAccepted == true` and the corresponding scope (`callHistoryConsent`, `smsHistoryConsent`, `recordingSyncConsent`) is active.
- Writes in batches of up to 200 documents using `FieldValue.serverTimestamp()` for `syncedAt` and `createdAt`.
- Updates `sync_status/{uid}_{deviceId}` with `callsSyncedCount`, `smsSyncedCount`, `recordingsSyncedCount`, and `status`.

### 3. Transparent SMS Import
- **Android SAF Importer**: Accepts JSON arrays (`[{"address":"+15550101","body":"...","direction":"incoming","timestamp":1700000000000}]`), CSV, or pipe-delimited files via `ActivityResultContracts.OpenDocument`.
- **Eligible Device Scan**: Uses `Telephony.Sms.CONTENT_URI` only when `READ_SMS` is explicitly granted by the user.

### 4. Recording Vault & Playback
- Imports authorized audio files via `ActivityResultContracts.OpenDocument("audio/*")`.
- Encrypts audio bytes at rest with `KeystoreCryptoManager` (`AES-256-GCM`).
- Playback decrypts into an app-private temporary cache file and deletes the temporary file immediately when playback stops or the ViewModel clears.

### 5. Data Portability & Deletion
- **Export**: `buildPersonalDataExportJson()` writes a portable JSON archive via `ActivityResultContracts.CreateDocument("application/json")`.
- **Delete Cloud Data**: Deletes all user-owned documents across all 8 activity collections in batches.
- **Delete Account**: Removes cloud data, user document, local encrypted files, Room tables, DataStore preferences, and deletes the Firebase Auth user.
