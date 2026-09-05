package com.example.signage_front.network
//MediaManager.kt
import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object MediaManager {
    private const val TAG = "MediaManager"
    private const val MEDIA_ROOT = "media"

    fun getLocalFile(context: Context, ad: AdStatus): File {
        val typeDir = ad.mediaType ?: "unknown"
        val directory = File(context.filesDir, "$MEDIA_ROOT/$typeDir")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, ad.path)
    }

    suspend fun downloadMediaIfNeeded(context: Context, ad: AdStatus): Boolean {
        val localFile = getLocalFile(context, ad)
        
        // 1. If file exists and checksum matches, skip downloading
        if (localFile.exists() && ad.expectedChecksum != null && verifyChecksum(localFile, ad.expectedChecksum)) {
            Log.d(TAG, "Media already exists and checksum is verified: ${ad.path}")
            return true
        }

        // 2. Fallback to size-based check if checksum is not available but size is specified
        if (localFile.exists() && ad.expectedSize > 0L && localFile.length() == ad.expectedSize) {
            Log.d(TAG, "Media already exists with correct size: ${ad.path}")
            return true
        }

        Log.d(TAG, "Downloading media for ad_id: ${ad.adId}")
        
        val client = NetworkClientProvider.getMTlsClient(context)
        // Use currentBaseUrl to support failover
        val url = "${Config.currentBaseUrl}/getAd".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ad_id", ad.adId)
            ?.build() ?: return false

        val request = Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val body = response.body ?: return false
                FileOutputStream(localFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading media: ${ad.adId}", e)
            if (localFile.exists()) localFile.delete()
            false
        }
    }

    fun verifyChecksum(file: File, expectedHash: String?): Boolean {
        if (expectedHash == null) return true // Can't verify if no hash provided
        
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            actualHash.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum calculation failed", e)
            false
        }
    }

    fun cleanupOrphanedMedia(context: Context, currentAds: List<AdStatus>) {
        val activeFiles = currentAds.map { getLocalFile(context, it).absolutePath }.toSet()
        val mediaRootDir = File(context.filesDir, MEDIA_ROOT)
        if (!mediaRootDir.exists()) return

        mediaRootDir.walkTopDown().forEach { file ->
            if (file.isFile && !activeFiles.contains(file.absolutePath)) {
                file.delete()
            }
        }
    }
}
