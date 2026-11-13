# Database Schema & Security Architecture

Connect Activity uses **Cloud Firestore (Enterprise Edition)** with database ID `ai-studio-android-connecta-12188737-fe09-4fd1-9ee7-2865dd16c80c` and a local encrypted **Room SQLite** cache (`connect_activity_vault.db`).

## Firestore Collections

| Collection | Document ID | Owner Field | Purpose |
|---|---|---|---|
| `users/{userId}` | Firebase Auth UID | `userId` | Account profile, `syncEnabled`, `consentAccepted`, `consentVersion` |
| `devices/{deviceId}` | Stable UUID (`dev_*`) | `ownerUserId` | Registered device metadata, `isConnected`, `callRecordingSupported` |
| `call_records/{recordId}` | `call_<sha256_prefix>` | `ownerUserId` | Deduplicated call metadata (`incoming`, `outgoing`, `missed`, `rejected`) |
| `sms_records/{recordId}` | `sms_<sha256_prefix>` | `ownerUserId` | Authorized or user-imported SMS records |
| `recordings/{recordingId}` | `rec_<uuid>` | `ownerUserId` | Encrypted recording metadata, `authorizedForSync`, `encryptionAlgorithm` |
| `consent_records/{consentId}` | `<uid>_<deviceId>_<scope>` | `ownerUserId` | Immutable-history ledger of consent grants & revocations |
| `sync_status/{statusId}` | `<uid>_<deviceId>` | `ownerUserId` | Per-device sync status (`idle`, `syncing`, `success`, `error`) |
| `authorized_shares/{shareId}` | `share_<sha256_prefix>` | `ownerUserId` | Explicit viewer access grants (`viewerEmail`, `scope`, `status`) |
| `audit_logs/{logId}` | `log_<uuid>` | `ownerUserId` | Append-only audit events (`DEVICE_REGISTERED`, `SYNC_COMPLETED`, `SHARE_GRANTED`, etc.) |

## Local Encryption & Deduplication

- **Android Keystore (`AES/GCM/NoPadding`, 256-bit)**: Encrypts local SMS message bodies inside Room (`local_sms.encryptedBody`) and imported audio recordings (`filesDir/encrypted_recordings/*.enc`).
- **SHA-256 Deduplication**: Every call and SMS record generates a deterministic `recordHash` indexed uniquely in Room (`ownerUserId, recordHash`) and used as the Firestore document ID prefix to prevent duplicates across retries or WorkManager runs.
- **Zero-Trust Security Rules**: Every collection in `firestore.rules` verifies `request.auth != null`, `ownerUserId == request.auth.uid`, strict key allowlists (`hasAll`/`hasOnly`), string length bounds, and server timestamp bounds (`<= request.time`).
