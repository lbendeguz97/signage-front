# Signage Front - Project Documentation

## Project Overview
**Signage Front** is a secure, kiosk-mode Android digital signage application built with Jetpack Compose. It supports automated device enrollment via mTLS, dynamic ad synchronization (HTML and Video), and advanced system controls like automated screen wake-up.

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
- **Network Security**: Configured via `network_security_config.xml` to trust a private Root CA and allow development traffic.

### Enrollment Flow
1. **Key Generation**: 2048-bit RSA KeyPair generated in KeyStore.
2. **CSR Generation**: PKCS#10 Certificate Signing Request created using Bouncy Castle, using `Build.SERIAL` as Common Name (CN).
3. **Registration**: CSR and OTP are sent via `POST /enroll`.
4. **Certificate Storage**: Signed X.509 certificate received from server is stored in KeyStore alongside the private key.

### Check-in
On every startup, the app performs a `GET /checkin`. 
- **Success (204)**: App proceeds to main functionality.
- **Failure (401/403)**: App forces re-enrollment.

---

## 3. Data Management (Database Layer)

### Room Database
Located in `com.example.signage_front.data`.
- **Entity (`AdStatus`)**: Stores ad metadata (ID, allowed status, path, display settings, checksum, and sync status).
- **Sync Mechanism**: A Transaction-based sync ensures the local DB is a mirror of the server. It inserts/updates new records and deletes orphaned records in one atomic step.

### Ad Repository (Manager Middleware)
Orchestrates the lifecycle of data:
1. Updates Database records.
2. Triggers file downloads for new content.
3. Verifies file integrity.
4. Cleans up unused media files from storage.

---

## 4. Media & Synchronization

### Media Manager
Handles the local filesystem (`filesDir/media/`).
- **Subdirectories**: Categorizes media into `video/`, `image/`, and `html/`.
- **Integrity Check**:
    - **Cheap Check**: Verification of file size against server metadata.
    - **Deep Check**: SHA-256 Checksum verification to prevent corruption or tampering.
- **Cleanup**: Automatically deletes physical files when their corresponding DB record is removed.

### Ad Scheduler
- **Polling**: Background loop runs every 5 minutes.
- **Immediate Sync**: Triggered instantly after successful mTLS check-in.

---

## 5. Frontend (UI Layer)

### Jetpack Compose Components
- **`AdScreen`**: High-performance display using `Media3 ExoPlayer` (for video) and `Multiplatform WebView` (for HTML).
    - *Kiosk Mode*: No playback controls or buttons are visible on video content.
- **`HomeScreen`**: Interactive hub for user selection.
- **`EnrollmentScreen`**: Clean UI for device registration with OTP input.

---

## 6. System & Kiosk Features

### Screen Management
- **Keep Screen On**: App prevents the device from dimming during active display.
- **Auto-Wake/Unlock**: 
    - Detects `ACTION_SCREEN_OFF`.
    - Schedules an `AlarmManager` wake-up for 60 seconds later.
    - Uses `WakeLock` and legacy `KeyguardLock` flags (specifically for API 24) to force the screen on and bypass the lock screen.

---

## 7. Configuration
Centrally managed in `com.example.signage_front.network.Config`.
- **`BASE_URL`**: The single source of truth for the backend server address.

---

*Last Updated: $(date +%Y-%m-%d)*
