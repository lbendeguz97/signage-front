# Signage Front - Gemini Developer Guide

This document provides a comprehensive overview of the **Signage Front** codebase, architecture, dependencies, and key components. It serves as the primary context guide for developer agents (like Gemini / Antigravity) working on this project.

---

## 1. Project Overview

**Signage Front** is a secure, kiosk-mode digital signage application for Android, built with **Jetpack Compose** and **MVVM-lite** architecture.

### Key Features:
- **mTLS Device Enrollment**: Secure device registration utilizing certificate signing requests (CSR) via Bouncy Castle and keystore storage.
- **Failover Connection Routing**: Checks health on startup using dual endpoints (primary and backup) and selects the operational server.
- **Transactional Synchronization**: ROOM-based database mirroring the backend database.
- **Media Lifecycle Manager**: Handles video, image, and HTML assets, verifying sizes and SHA-256 integrity.
- **Kiosk-Mode & Auto-Wake**: Locks app focus, bypasses screensavers, disables lockscreens, and auto-wakes screen 60 seconds after detect screen-off using AlarmManager.
- **Audience Analyzer (Throttled)**: Live camera face detection, gender classification, and age estimation using CameraX, Google ML Kit, and TensorFlow Lite (TFLite) with a robust heuristic fallback.

---

## 2. Directory & Package Structure

```
c:\Users\lbend\Documents\GIT\signage-front
├── app
│   ├── src
│   │   ├── main
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/example/signage_front
│   │   │   │   ├── MainActivity.kt            # Entrypoint, Navigation, Check-in, Enrollment Flow
│   │   │   │   ├── camera/                    # Camera & ML Analysis Layer
│   │   │   │   │   ├── AgeGenderClassifier.kt # TFLite demographic classifier and fallback heuristics
│   │   │   │   │   └── FaceDetectionManager.kt # CameraX frame analyzer (1.5s throttle) and ML Kit Face Detector
│   │   │   │   ├── data/                      # Database Layer
│   │   │   │   │   ├── AdDao.kt               # Ad database operations
│   │   │   │   │   ├── AdRepository.kt        # Repository bridging network sync & media manager
│   │   │   │   │   ├── AdStatus.kt            # Room Entity for Ads
│   │   │   │   │   ├── AppDatabase.kt         # Database instance
│   │   │   │   │   ├── SyncDao.kt             # Sync status operations
│   │   │   │   │   └── SyncState.kt           # Room Entity tracking last sync time
│   │   │   │   ├── network/                   # Network & Security Layer
│   │   │   │   │   ├── AdScheduler.kt         # Sync polling & immediate checkins
│   │   │   │   │   ├── Config.kt              # Environment toggle & endpoint configs
│   │   │   │   │   ├── MediaManager.kt        # Media download, cache & integrity checks
│   │   │   │   │   ├── NetworkClientProvider.kt # Standard & mTLS OkHttp Clients
│   │   │   │   │   └── SecurityManager.kt     # KeyStore, RSA Generation, and CSR (Bouncy Castle)
│   │   │   │   ├── receiver/
│   │   │   │   │   └── WakeReceiver.kt        # Broadcast receiver to force-wake the screen
│   │   │   │   └── ui/                        # Presentation Layer
│   │   │   │       ├── composables/           # Reusable UI widgets
│   │   │   │       │   └── CameraPreviewComposables.kt # FaceDetectionCameraPreview with PIP HUD
│   │   │   │       ├── screens/
│   │   │   │       │   ├── AdScreen.kt        # Media3 ExoPlayer, Multiplatform WebView, and camera analyzer
│   │   │   │       │   ├── DebugScreen.kt     # Connectivity and live camera analysis diagnostics
│   │   │   │       │   ├── EnrollmentScreen.kt # Device registration input
│   │   │   │       │   ├── HomeScreen.kt      # Hub UI
│   │   │   │       │   └── QrCodeScreen.kt    # Rendered QR code redirects
│   │   │   │       └── theme/                 # Styling, Colors & Material 3 Theme
│   │   │   └── res/
│   │   │       ├── raw/ca_cert.pem            # Root CA certificate
│   │   │       └── xml/network_security_config.xml
│   │   └── test/                              # JVM Unit Tests
│   └── build.gradle.kts                       # App module configuration
├── build.gradle.kts                           # Root Gradle configuration
├── settings.gradle.kts                        # Gradle settings
├── download_models.ps1                        # Script to download TFLite model files
└── DOCUMENTATION.md                           # Core system requirements & architecture
```

---

## 3. Technology Stack & Core Configurations

| Tech Area | Libraries / APIs used |
| :--- | :--- |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Navigation** | Jetpack Compose Navigation (`rememberNavController`) |
| **Local Database** | Room Database |
| **HTTP client** | OkHttp 4.x |
| **Media Playback** | Media3 ExoPlayer |
| **Web Rendering** | Multiplatform WebView |
| **Camera Framework** | CameraX (Core, Camera2, Lifecycle, View) |
| **ML & AI Engine** | Google ML Kit (Face Detection) & TensorFlow Lite (Interpreter, Support) |
| **Crypto & CSR** | Bouncy Castle (`org.bouncycastle:bcpkix-jdk18on`) |
| **Asynchronous Logic** | Kotlin Coroutines & Flow |

---

## 4. Key Implementation Details

### A. Mutual TLS & KeyStore Setup
- Located in `SecurityManager.kt` & `NetworkClientProvider.kt`.
- KeyStore generates a 2048-bit RSA key pair internally using key alias `signage-client-key`.
- KeyStore is hardware-backed (TEE) if supported by the device.
- The Certificate Signing Request (CSR) is built with `PKCS10CertificationRequestBuilder` using `Build.SERIAL` as the Common Name.
- All secure client traffic enforces **HTTP/1.1** inside `NetworkClientProvider` to circumvent ALPN-negotiation hangs.

### B. Room Database Transactions
- Located in `AdRepository.kt`.
- Uses database transactions to mirror local data structures against the server. Updates existings, inserts news, and wipes obsolete elements in one single database block.

### C. File Downloads & Integrity Checks
- Handled in `MediaManager.kt`.
- Downloads categorized to subdirectories `video/`, `image/`, and `html/`.
- Validates downloaded files against:
  - *Size validation*: Matches bytes.
  - *Checksum validation*: SHA-256 hash comparison.
- Unverified/pending media is ignored during display; only `VERIFIED` sync status is allowed.

### D. Screen Auto-Wake & Lock Bypass
- Screen state tracked via `ACTION_SCREEN_OFF`.
- Activates an exact alarm via `AlarmManager` 60 seconds into the future.
- `WakeReceiver` uses a `WakeLock` combined with keyguard layout flags:
  - `FLAG_SHOW_WHEN_LOCKED` / `setShowWhenLocked(true)`
  - `FLAG_TURN_SCREEN_ON` / `setTurnScreenOn(true)`
  - `FLAG_DISMISS_KEYGUARD` / `KeyguardManager.requestDismissKeyguard(...)`

### E. Face Detection & Demographic Estimation (Performance Optimized)
- **Throttling**: To run efficiently on low-end hardware, frames are analyzed at most once every **1.5 seconds**, avoiding processor overload.
- **Heuristic Fallback**: Loads `model_age.tflite` and `model_gender.tflite` from assets if present. Otherwise, it defaults to a stable fallback heuristic based on ML Kit face crop coordinates/aspect ratios.
- **Compose PIP**: Built-in `FaceDetectionCameraPreview` handles CameraX lifecycle binding, permission prompts, and overlay statistics dynamically.

---

## 5. Development Tasks & Commands

### Running Unit Tests
Execute the JUnit test suites from the root directory:
```bash
./gradlew test
```

### Building the Project
To compile the application in debug mode:
```bash
./gradlew assembleDebug
```

### Fetching TFLite Models
Download the age and gender quantized models to assets directory:
```powershell
.\download_models.ps1
```

---

*Last Updated: 2026-07-04*
