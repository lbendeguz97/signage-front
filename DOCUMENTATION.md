# Signage Front - Project Documentation

## Project Overview
**Signage Front** is a secure, kiosk-mode Android digital signage application built with Jetpack Compose. It supports automated device enrollment via mTLS, dynamic ad synchronization (HTML, Video, and programmatic SSP slots), and advanced system controls like automated screen wake-up.

---

## 1. System Architecture

### Target Platform
- **Min SDK**: 24 (Android 7.0 Nougat)
- **Target SDK**: 36
- **Architecture**: MVVM-lite with Repository pattern.

---

## 2. Security & Enrollment (Network Layer)

### mTLS Authentication
The app uses Mutual TLS for all sensitive communication.
- **Client Auth**: Private keys are generated and stored securely within the `AndroidKeyStore` (TEE/Hardware-backed).
- **Network Security**: Configured via `NetworkClientProvider` to trust a private Root CA (stored in `res/raw/ca_cert.pem`) and allow development traffic.
- **Protocol Constraint**: All clients are forced to use **HTTP/1.1** to avoid ALPN-related hangs on specific Android/Server combinations.

### Enrollment Flow
1. **Key Generation**: 2048-bit RSA KeyPair generated in KeyStore.
2. **CSR Generation**: Create PKCS#10 Certificate Signing Request using Bouncy Castle, using `Build.SERIAL` as Common Name (CN).
3. **Registration**: CSR and OTP are sent via `POST /enroll`.
4. **Certificate Storage**: Signed X.509 certificate received from server is stored in KeyStore using `setKeyEntry` to maintain the chain without orphaning the private key.

### Check-in & Failover
On startup, the app performs a connectivity check (`/echo`) across primary and backup URLs.
- **Check-in (`GET /checkin`)**: 
    - **Success (204)**: App proceeds to main functionality.
    - **Failure (401/403)**: App forces re-enrollment.
- **Failover**: If the primary `BASE_URL` is unreachable, the app automatically switches to `BASE_URL_BACKUP`.

---

## 3. Data Management (Database Layer)

### Room Database
Located in `com.example.signage_front.data`.
* **Database Version**: `11` (with destructive migration support).
* **Entities**:
  - `AdStatus`: Stores ad metadata (ID, allowed status, path, display settings, checksum, and sync status).
  - `SyncState`: Tracks synchronization timestamps to optimize server delta queries.
  - `GroupConfig`: Stores group config attributes including the active `sspConnectivityId`.
  - `TabletMetadata`: Caches local device metadata (`androidId` and `refId`).
  - `SspConnectivity`: Caches programmatic partner details (name, provider, endpoint URL, deal ID, line item ID, additional parameters).
  - `CachedSspAd`: Tracks pre-fetched SSP media files locally, storing `mediaUrl`, `localPath`, `expiresAt` (TTL), and `lastAccessed`.
  - `PendingBeacon`: Queues offline impression and click beacons (`url`, `createdAt`, `retryCount`).
* **Sync Mechanism**: A Transaction-based sync ensures the local DB is a mirror of the server. It inserts/updates new records and deletes orphaned records in one atomic step. 
* **Thread Safety**: Wrapped repository write transactions with `Mutex` locks to prevent concurrent database writes or overlapping file downloads.

---

## 4. Media & Synchronization

### Media Manager
Handles the local filesystem (`filesDir/media/`).
- **Subdirectories**: Categorizes media into `video/`, `image/`, and `html/`.
- **Integrity Check**:
    - **Cheap Check**: Verification of file size against server metadata.
    - **Deep Check**: SHA-1 Checksum verification to prevent corruption or tampering.
- **Sync Status**: Files transition from `PENDING` -> `DOWNLOADING` -> `VERIFIED`. Only `VERIFIED` content is displayed.

### SSP Cache Manager (`SspCacheManager`)
Handles local file caching and eviction for programmatic advertisements:
- **Max Cache Footprint**: Capped at **200 MB**.
- **Eviction Policies**: Employs TTL-based pruning (deleting expired ads) followed by LRU eviction (deleting oldest files based on `lastAccessed`) to enforce limits.
- **Beacon Tracker**: Launches GET request loops to fire tracking beacons, queueing failed calls in `pending_beacons` on offline states.

### Ad Scheduler
- **Polling**: Background loop runs every 1 minute.
- **Immediate Sync**: Triggered instantly after successful mTLS check-in.
- **Programmatic Routines**: Periodically pre-fetches up to 3 programmatic ads and flushes pending offline impression tracking beacons.

---

## 5. Frontend (UI Layer)

### Jetpack Compose Components
- **`AdScreen`**: High-performance display using `Media3 ExoPlayer` (for video), `Multiplatform WebView` (for HTML), and `SspContent` (for programmatic ads).
    - *SSP rendering*: Inspects cache, plays video/image, fires beacons, triggers redirects, and falls back to skipping the slot if cache is empty.
    - *Kiosk Mode*: No playback controls or buttons are visible.
- **`HomeScreen`**: Interactive hub for user selection.
- **`EnrollmentScreen`**: UI for device registration with OTP input.
- **`QrCodeScreen`**: Displays a QR code generated from a redirect URL.
- **`DebugScreen`**: Available only in `dev` environment to assist with connectivity issues.

---

## 6. System & Kiosk Features

### Screen Management
- **Keep Screen On**: App prevents the device from dimming during active display via `FLAG_KEEP_SCREEN_ON`.
- **Auto-Wake/Unlock**: 
    - Detects `ACTION_SCREEN_OFF`.
    - Schedules an `AlarmManager` wake-up for 60 seconds later via `WakeReceiver`.
    - Uses `WakeLock` and `KeyguardLock` flags (specifically for API 24 compatibility) to force the screen on and bypass the lock screen.

---

## 7. Configuration
Centrally managed in Central Configuration [`Config.kt`](file:///home/lbendeguz97/git/signage-full/signage-front/app/src/main/java/com/example/signage_front/network/Config.kt).
- **`ENV`**: Toggles between `dev` (shows debug screens) and `prod`.
- **`BASE_URL` / `BASE_URL_BACKUP`**: Server endpoint configuration.
- **`REDIRECT_ROOT`**: The base URL for user-facing QR code redirects.

---

*Last Updated: 2026-08-11*
