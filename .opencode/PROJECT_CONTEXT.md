# Signage Front - Android Digital Signage App

## Project Overview

This is an Android digital signage application that displays rotating ads (videos, images, HTML content) on devices. It uses mTLS (mutual TLS) for secure communication with a backend server.

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
| `AdScheduler` | `network/AdScheduler.kt` | Polling for ad updates, sync orchestration |
| `MediaManager` | `network/MediaManager.kt` | Media file download and local storage |
| `AdRepository` | `data/AdRepository.kt` | Room database operations for ads |
| `AdScreen` | `ui/screens/AdScreen.kt` | Ad carousel display (video/image/HTML) |

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
    └── /getConfig (mTLS)
    
    ▼
    
Android App
    │
    ├── SecurityManager (AndroidKeyStore EC P-256 keys)
    ├── NetworkClientProvider (mTLS OkHttp client)
    ├── AdScheduler (polls every 1 minute)
    ├── Room Database (ad_status table)
    └── Local media files (/files/media/{video,image}/)
```

---

## Security Implementation

### Key Generation (AndroidKeyStore)

```kotlin
// SecurityManager.kt - EC P-256 key with DIGEST_NONE for TLS client auth
KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN or PURPOSE_VERIFY)
    .setKeySize(256)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(
        KeyProperties.DIGEST_NONE,    // Required for Conscrypt TLS client auth
        KeyProperties.DIGEST_SHA256,
        KeyProperties.DIGEST_SHA384,
        KeyProperties.DIGEST_SHA512
    )
    .build()
```

**Key Alias:** `client_auth_key_v5`

### CSR Generation

Uses BouncyCastle with a custom `ContentSigner` that works with AndroidKeyStore:

```kotlin
// AndroidKeyStoreContentSigner - wraps AndroidKeyStore key for BouncyCastle
class AndroidKeyStoreContentSigner(private val privateKey: PrivateKey) : ContentSigner {
    override fun getAlgorithmIdentifier() = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
    override fun getSignature(): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(outputStream.toByteArray())
        return signature.sign()
    }
}
```

### Conscrypt Provider

Installed in `SignageApplication.onCreate()` as the primary security provider:

```kotlin
Security.insertProviderAt(Conscrypt.newProvider(), 1)
```

**Why:** Conscrypt's TLS client auth uses `NONEwithECDSA` internally. AndroidKeyStore keys must have `DIGEST_NONE` enabled for this to work.

---

## Issues Solved (Session 2026-05-21)

### 1. CSR Signing Failed: `NONEwithECDSA` Provider Not Found

**Problem:** BouncyCastle's `JcaContentSignerBuilder` couldn't use AndroidKeyStore keys directly.

**Solution:** Created custom `AndroidKeyStoreContentSigner` that uses `Signature.getInstance("SHA256withECDSA")` with the AndroidKeyStore private key.

### 2. mTLS Handshake Failed: `NONEwithECDSA` Not Supported

**Problem:** Conscrypt's TLS client auth requires `NONEwithECDSA`, but the key was generated without `DIGEST_NONE`.

**Solution:** 
- Added `KeyProperties.DIGEST_NONE` to `KeyGenParameterSpec`
- Bumped key alias to `v5` to force new key generation
- Installed Conscrypt as primary security provider

### 3. Video Plays Once, Then Black Screen

**Problem:** `PlayerView` uses `SurfaceView` which has Z-ordering issues. The black surface persisted after video ended, covering the image.

**Solution:** Switched to `TextureView` which renders in the normal view hierarchy:

```kotlin
AndroidView(
    factory = { ctx ->
        TextureView(ctx).also { textureView ->
            exoPlayer.setVideoTextureView(textureView)
        }
    },
    onRelease = { textureView ->
        exoPlayer.clearVideoTextureView(textureView)
    }
)
```

### 4. Fullscreen Mode

**Implementation:**
- `WindowCompat.setDecorFitsSystemWindows(window, false)`
- Hide system bars: `windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())`
- Sticky immersive: `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
- Re-apply in `onWindowFocusChanged()` and `onResume()`

---

## Database Schema

### Room Database: `signage_database`

**Table: `ad_status`**

| Column | Type | Description |
|--------|------|-------------|
| `adId` | String (PK) | Unique ad identifier |
| `adAllowed` | Boolean | Whether ad is enabled |
| `adult` | Boolean | Adult content flag |
| `path` | String | Filename (e.g., `video.mp4`) |
| `url` | String? | Redirect URL for QR code |
| `display` | String | Display mode |
| `displayTime` | Int? | Duration in seconds (for images/HTML) |
| `mediaType` | String? | `video`, `image`, `html` |
| `expectedChecksum` | String? | SHA-1 hash for verification |
| `expectedSize` | Long | File size in bytes |
| `syncStatus` | String | `PENDING`, `DOWNLOADING`, `VERIFIED`, `ERROR` |
| `lastUpdated` | Long | Timestamp |

**Table: `sync_state`**

| Column | Type | Description |
|--------|------|-------------|
| `tableName` | String (PK) | Table being tracked |
| `timestamp` | String | Server timestamp token |

---

## File Storage

```
/data/data/com.example.signage_front/files/
├── media/
│   ├── video/
│   │   └── *.mp4
│   └── image/
│       └── *.png, *.jpg
└── app_config.json
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | (latest) | UI framework |
| Media3 ExoPlayer | 1.5.1 | Video playback |
| OkHttp | (libs) | HTTP client |
| BouncyCastle | (libs) | CSR generation |
| Conscrypt | 2.5.0 | TLS provider for AndroidKeyStore |
| Room | (libs) | Local database |
| ZXing | (libs) | QR code generation |

---

## Server Endpoints

| Endpoint | Auth | Method | Purpose |
|----------|------|--------|---------|
| `/echo` | None | GET | Health check (returns 204) |
| `/enroll` | None | POST | Submit OTP + CSR, receive certificate |
| `/checkin` | mTLS | GET | Device check-in (returns 204) |
| `/getDatabaseStatus` | mTLS | GET | Get table timestamps for sync |
| `/getAdStatus` | mTLS | GET | Get ad registry JSON |
| `/getAd?ad_id=X` | mTLS | GET | Download media file |
| `/getConfig` | mTLS | GET | Get app configuration |

---

## Build & Run

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s SignageAuth:D SecurityManager:D NetworkClient:D AdScreen:D VideoContent:D ImageContent:D AdScheduler:D SignageApplication:D
```

---

## Known Emulator Issues

These errors appear in the emulator but don't affect functionality:

- `EGL_BAD_ATTRIBUTE` - Emulator OpenGL issue
- `setPortMode on output to DynamicANWBuffer failed` - Goldfish codec limitation
- `OMX.android.goldfish.h264.decoder` errors - Emulator video decoder

These do NOT occur on real devices.

---

## TODO / Future Work

- [ ] Add error UI for failed media downloads
- [ ] Implement ad impression tracking
- [ ] Add offline mode with cached ads
- [ ] Implement remote configuration updates
- [ ] Add device registration/management UI
- [ ] Support landscape/portrait mode switching
- [ ] Add transition animations between ads

---

## Useful Commands

```bash
# Clear app data
adb shell pm clear com.example.signage_front

# Check stored keys
adb logcat -s SecurityManager:D | grep -i key

# Check network requests
adb logcat -s OkHttp:D NetworkClient:D

# Check ad playback
adb logcat -s AdScreen:D VideoContent:D ImageContent:D

# Query database (if sqlite3 available)
adb shell run-as com.example.signage_front sqlite3 databases/signage_database "SELECT * FROM ad_status;"
```

---

*Last updated: 2026-05-21*
*Session: Fixed mTLS, video playback, fullscreen mode*
