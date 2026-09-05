# Signage Front - Android Digital Signage App

## Project Overview

This is an Android digital signage application that displays rotating ads (videos, images, HTML content, and programmatic SSP connections) on devices. It uses mTLS (mutual TLS) for secure communication with a backend server.

**Package:** `com.example.signage_front`  
**Min SDK:** 24 (Android 7.0)  
**Target SDK:** 36  
**Language:** Kotlin  
**UI Framework:** Jetpack Compose  

---

## Architecture

### Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `MainActivity` | `MainActivity.kt` | Entry point, navigation, enrollment flow, kiosk mode |
| `SignageApplication` | `SignageApplication.kt` | App-level initialization, Conscrypt security provider |
| `SecurityManager` | `network/SecurityManager.kt` | Key generation, CSR creation, certificate management |
| `NetworkClientProvider` | `network/NetworkClientProvider.kt` | OkHttp clients for mTLS and standard HTTPS |
| `AdScheduler` | `network/AdScheduler.kt` | Polling for ad updates, sync orchestration, and beacon flushing |
| `MediaManager` | `network/MediaManager.kt` | Media file download and local storage |
| `SspCacheManager` | `network/SspCacheManager.kt` | Eviction (TTL + LRU), background downloads, and tracking beacons |
| `AdRepository` | `data/AdRepository.kt` | Room database operations for ads (Mutex-guarded) |
| `AdScreen` | `ui/screens/AdScreen.kt` | Ad carousel display (video/image/HTML/ssp content) |

### Data Flow

```
Server (port 4000, HTTPS)
    │
    ├── /echo (health check, no auth)
    ├── /enroll (OTP + CSR → certificate)
    ├── /checkin (mTLS required)
    ├── /getDatabaseStatus (mTLS)
    ├── /getAdStatus (mTLS)
    ├── /getAd?ad_id=X (mTLS, returns media file)
    ├── /getConfig (mTLS)
    └── /getSspConnectivity (mTLS)
    
    ▼
    
    // Android App
    ├── SecurityManager (AndroidKeyStore EC P-256 keys)
    ├── NetworkClientProvider (mTLS OkHttp client)
    ├── AdScheduler (polls every 1 minute)
    ├── SspCacheManager (enforces 200MB TTL + LRU programmatic cache)
    ├── Room Database (ad_status, group_config, ssp_connectivities, etc.)
    └── Local media files (/files/media/{video,image,html}/ & /files/ssp_cache/)
```

---

## Security & Thread Safety

### Conscrypt & Keys
Conscrypt is installed in `SignageApplication.onCreate()` to support hardware-backed client authentication keys via AndroidKeyStore with `DIGEST_NONE`. BouncyCastle PKCS#10 wraps CSR generation using standard EC signature providers.

### Concurrency Lock (Mutex)
To prevent race conditions during concurrent startup sync loops, all repository synchronization methods in `AdRepository` are guarded by static `Mutex` locks (`adSyncMutex` and `configSyncMutex`). This guarantees only one database write or media download thread runs at a time.

---

## Database Schema

### Room Database: `signage_database` (Schema version = 11)

**Table: `ad_status`** (tracks standard media registry)
* Tracks columns: `adId`, `adAllowed`, `adult`, `path`, `url`, `display`, `displayTime`, `mediaType`, `expectedChecksum` (SHA-1), `expectedSize`, `syncStatus` (`PENDING`/`DOWNLOADING`/`VERIFIED`/`ERROR`), `lastUpdated`.

**Table: `group_config`** (tracks active group config layout)
* Tracks columns: `groupId`, `name`, `configJson`, `playlistId`, `scheduleId`, `sspConnectivityId`.

**Table: `tablet_metadata`** (caches device mapping)
* Tracks columns: `androidId`, `refId`.

**Table: `ssp_connectivities`** (caches programmatic servers)
* Tracks columns: `id`, `name`, `provider`, `endpointUrl`, `refId`, `dealId`, `lineItemId`, `additionalParams` (stored as raw JSON string).

**Table: `cached_ssp_ads`** (tracks programmatic media caching)
* Tracks columns: `mediaUrl` (PK), `localPath`, `mediaType`, `sizeBytes`, `expiresAt` (TTL), `lastAccessed` (LRU).

**Table: `pending_beacons`** (queues offline tracking requests)
* Tracks columns: `id` (auto-gen PK), `url`, `createdAt`, `retryCount`.

---

## File Storage

```
/data/data/com.example.signage_front/files/
├── media/
│   ├── video/ (standard verified mp4 files)
│   └── image/ (standard verified images)
├── ssp_cache/
│   └── *.mp4, *.jpg (programmatic cached files)
└── app_config.json
```

---

## Issues Solved (Session 2026-08-11)

### 1. Concurrent Write Collisions on Startup
* **Problem**: Multi-threaded sync requests triggered concurrently on startup led to checksum corruption, missing media, and `FileNotFoundException` logs.
* **Solution**: Implemented mutual exclusion (`Mutex` locks) inside `AdRepository.kt` to synchronize operations.

### 2. Programmatic SSP Content Support
* **Problem**: Tablets did not pull or render programmatic programmatic slots.
* **Solution**: Developed `SspCacheManager` with pre-fetching, TTL + LRU eviction (max 200MB), offline tracking beacon queues, and a dedicated `"ssp"` media branching handler in `AdScreen`.

---

*Last updated: 2026-08-11*
*Session: Implemented thread-safe Mutex locking, SspCacheManager caching (TTL/LRU), and SspContent playback with offline beacons.*
