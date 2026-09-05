# Signage Front - Gemini Developer Guide

This document provides a comprehensive overview of the **Signage Front** codebase, architecture, dependencies, and key components. It serves as the primary context guide for developer agents (like Gemini / Antigravity) working on this project.

---

## 1. Project Overview

**Signage Front** is a secure, kiosk-mode digital signage application for Android, built with **Jetpack Compose** and **MVVM-lite** architecture.

### Key Features:
- **mTLS Device Enrollment**: Secure device registration utilizing certificate signing requests (CSR) via Bouncy Castle and keystore storage.
- **Failover Connection Routing**: Checks health on startup using dual endpoints (primary and backup) and selects the operational server.
- **Transactional Synchronization**: ROOM-based database mirroring the backend database.
- **Media Lifecycle Manager**: Handles video, image, and HTML assets, verifying sizes and SHA-1 integrity.
- **Thread-safe Database Operations**: Guards database writes and media downloads with `Mutex` locks to prevent race condition write collisions.
- **SSP Programmatic Content Caching**: Employs `SspCacheManager` with background pre-fetching, TTL + LRU eviction (200MB maximum size limit), and offline impression beacon queueing with automated scheduling flushes.
- **Kiosk-Mode & Auto-Wake**: Locks app focus, bypasses screensavers, disables lockscreens, and auto-wakes screen 60 seconds after detect screen-off using AlarmManager.
- **Audience Analyzer (Throttled)**: Live camera face detection, gender classification, and age estimation using CameraX, Google ML Kit, and TensorFlow Lite (TFLite) with a robust heuristic fallback.

---

## 2. Directory & Package Structure

```
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
│   │   │   │   │   ├── AdRepository.kt        # Repository bridging network sync & media manager (Mutex locked)
│   │   │   │   │   ├── ConfigEntities.kt      # Room database entities (GroupConfig, SspConnectivity, CachedSspAd, etc.)
│   │   │   │   │   ├── ConfigDao.kt           # Room DAO queries for configurations, cached ads, and beacons
│   │   │   │   │   ├── AdStatus.kt            # Room Entity for Ads
│   │   │   │   │   ├── AppDatabase.kt         # Room database instance (Schema version = 11)
│   │   │   │   │   ├── SyncDao.kt             # Sync status operations
│   │   │   │   │   └── SyncState.kt           # Room Entity tracking last sync time
│   │   │   │   ├── network/                   # Network & Security Layer
│   │   │   │   │   ├── AdScheduler.kt         # Sync polling, immediate checkins, and beacon flushing
│   │   │   │   │   ├── Config.kt              # Environment toggle & endpoint configs
│   │   │   │   │   ├── MediaManager.kt        # Media download, cache & integrity checks
│   │   │   │   │   ├── SspCacheManager.kt     # Eviction (TTL + LRU), background downloads, and beacons
│   │   │   │   │   ├── NetworkClientProvider.kt # Standard & mTLS OkHttp Clients
│   │   │   │   │   └── SecurityManager.kt     # KeyStore, RSA Generation, and CSR (Bouncy Castle)
│   │   │   │   ├── receiver/
│   │   │   │   │   └── WakeReceiver.kt        # Broadcast receiver to force-wake the screen
│   │   │   │   └── ui/                        # Presentation Layer
│   │   │   │       ├── composables/           # Reusable UI widgets
│   │   │   │       │   └── CameraPreviewComposables.kt # FaceDetectionCameraPreview with PIP HUD
│   │   │   │       ├── screens/
│   │   │   │       │   ├── AdScreen.kt        # Media3 ExoPlayer, WebView, and SspContent Composable
│   │   │   │       │   ├── DebugScreen.kt     # Diagnostics
│   │   │   │       │   ├── EnrollmentScreen.kt # Device registration input
│   │   │   │       │   ├── HomeScreen.kt      # Hub UI
│   │   │   │       │   └── QrCodeScreen.kt    # Rendered QR code redirects
│   │   │   │       └── theme/                 # Styling, Colors & Material 3 Theme
│   │   │   └── res/
│   │   │       ├── raw/ca_cert.pem            # Root CA certificate
│   │   │       └── xml/network_security_config.xml
│   │   └── test/                              # JVM Unit Tests
│   └── build.gradle.kts                       # App module configuration
```

---

## 3. Technology Stack & Core Configurations

| Tech Area | Libraries / APIs used |
| :--- | :--- |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Navigation** | Jetpack Compose Navigation (`rememberNavController`) |
| **Local Database** | Room Database (Schema Version 11) |
| **HTTP client** | OkHttp 4.x |
| **Media Playback** | Media3 ExoPlayer |
| **Web Rendering** | Multiplatform WebView |
| **Camera Framework** | CameraX (Core, Camera2, Lifecycle, View) |
| **ML & AI Engine** | Google ML Kit (Face Detection) & TensorFlow Lite |
| **Crypto & CSR** | Bouncy Castle (`org.bouncycastle:bcpkix-jdk18on`) |
| **Asynchronous Logic** | Kotlin Coroutines & Flow |

---

## 4. Key Implementation Details

### A. Mutual TLS & KeyStore Setup
- Located in `SecurityManager.kt` & `NetworkClientProvider.kt`.
- KeyStore generates a 2048-bit RSA key pair internally using key alias `client_auth_key_v5`.
- All secure client traffic enforces **HTTP/1.1** inside `NetworkClientProvider` to circumvent ALPN-negotiation hangs.

### B. Room Database Transactions & Mutex
- Located in `AdRepository.kt`.
- Uses database transactions to mirror local data structures against the server.
- Uses `Mutex` locks (`adSyncMutex` and `configSyncMutex`) to prevent multi-threaded startup write collisions.

### C. File Downloads & Integrity Checks
- Handled in `MediaManager.kt` and `SspCacheManager.kt`.
- Validates downloaded standard ad files using SHA-1 hash comparison.
- Programmatic cache directory enforces a **200 MB** maximum limit with TTL-expiration pruning and LRU sorting.

---

*Last Updated: 2026-08-11*
