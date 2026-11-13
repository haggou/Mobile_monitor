# Connect Activity Manager

Production-oriented Android application (Kotlin + Jetpack Compose + Material 3 + Room + WorkManager + Firebase) with companion Next.js Web Dashboard, zero-trust Firestore security rules, Android Keystore AES-256-GCM encryption, and explicit consent management.

## Repository Layout

- `app/` — Android Jetpack Compose application (`com.aistudio.connectactivity.kxmpzq`)
- `web-dashboard/` — Next.js + TypeScript + Tailwind CSS authenticated web dashboard
- `firebase/` — Storage security rules (`storage.rules`)
- `firestore.rules` & `firebase-blueprint.json` — Deployed zero-trust Cloud Firestore rules & schema IR
- `firestore.test.js` — Automated JavaScript Firestore security rules test suite
- `documentation/` — Database schema (`DATABASE.md`) and sync API documentation (`API.md`)

## Key Features & Compliance

1. **Google Sign-In via Jetpack Credential Manager**: Exclusive verified Google authentication (`GetSignInWithGoogleOption` + `GetGoogleIdOption`).
2. **Mandatory First-Install Consent**: Explains data categories, cloud sync, authorized viewers, and lets users opt in or decline optional scopes before any collection occurs.
3. **Call History Management**: Permission-gated (`READ_CALL_LOG`, optional `READ_CONTACTS`), search, call-type & date-range filters, pagination, Room local cache, and deduplicated Firestore sync.
4. **Transparent SMS Management**: Respects Android restricted SMS policies—supports user-initiated JSON/CSV import via zero-permission Storage Access Framework (`OpenDocument`), plus eligible `READ_SMS` scan when granted. Message bodies are encrypted locally with Android Keystore AES-256-GCM.
5. **Lawful Call Recording Vault**: Detects system recording capability honestly (`"Call recording is not supported by this device or current Android configuration."` on standard Android 10+ devices), never uses Accessibility Services or silent mic capture, and lets users import, Keystore-encrypt, play back, authorize cloud metadata sync, or delete authorized recordings.
6. **WorkManager Background Sync**: Network-aware (`UNMETERED` or `CONNECTED`), exponential backoff retries, incremental batching, and SHA-256 deduplication.
7. **Privacy Dashboard**: Permission status, sync toggles, connected device disconnection, authorized viewer sharing & revocation, JSON personal data export, cloud data wipe, and permanent account deletion.

## Setup & Deployment

### Android Build & Tests
```bash
gradle :app:testDebugUnitTest
gradle assembleRelease
```

### Deploy Firestore Rules
Rules in `/firestore.rules` target the provisioned database `ai-studio-android-connecta-12188737-fe09-4fd1-9ee7-2865dd16c80c` in project `gen-lang-client-0587220162`.

### Web Dashboard
```bash
cd web-dashboard
cp .env.local.example .env.local
# Populate Firebase Web App credentials from Firebase Console
npm install && npm run dev
```

### Release Signing Configuration
Release builds read signing credentials from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) in `app/build.gradle.kts` without hardcoding secrets in source control.
