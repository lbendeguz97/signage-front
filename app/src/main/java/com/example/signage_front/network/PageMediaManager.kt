package com.example.signage_front.network

import android.content.Context
import android.util.Log
import com.example.signage_front.data.Page
import com.example.signage_front.data.PageMedia
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Local file storage for page images (widget + embedded), mirroring MediaManager:
 * downloads are idempotent (size-verified) and a cleanup pass removes files that
 * no longer map to a server-side page.
 */
object PageMediaManager {
    private const val TAG = "PageMediaManager"
    private const val ROOT = "pages"

    fun getPageDir(context: Context, pageId: Long): File {
        val dir = File(context.filesDir, "$ROOT/$pageId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Absolute path a given media row should live at. */
    fun resolveLocalFile(context: Context, media: PageMedia): File {
        val sub = if (media.isWidget) "widget" else "images"
        val dir = File(getPageDir(context, media.pageId), sub)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, media.name)
    }

    fun isPresent(context: Context, media: PageMedia): Boolean {
        val file = resolveLocalFile(context, media)
        return file.exists() && (media.sizeBytes <= 0L || file.length() == media.sizeBytes)
    }

    /**
     * Downloads the image if it is missing or the size does not match. Returns true
     * when a valid local file is present afterwards.
     */
    suspend fun downloadIfNeeded(context: Context, media: PageMedia): Boolean {
        val localFile = resolveLocalFile(context, media)
        if (localFile.exists() && (media.sizeBytes <= 0L || localFile.length() == media.sizeBytes)) {
            return true
        }

        val path = if (media.isWidget) "getPageWidget" else "getPageImage"
        val url = "${Config.currentBaseUrl}/$path".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("page_id", media.pageId.toString())
            ?.apply { if (!media.isWidget) addQueryParameter("name", media.name) }
            ?.build() ?: return false

        val client = NetworkClientProvider.getMTlsClient(context)
        val request = Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Page media download ${media.name} failed: HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                FileOutputStream(localFile).use { out ->
                    body.byteStream().use { it.copyTo(out) }
                }
                if (media.sizeBytes > 0L && localFile.length() != media.sizeBytes) {
                    Log.w(TAG, "Size mismatch for ${media.name}: ${localFile.length()} != ${media.sizeBytes}")
                    localFile.delete()
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading page media ${media.name}", e)
            if (localFile.exists()) localFile.delete()
            false
        }
    }

    /** Deletes page files that are not backed by an active page_media row. */
    fun cleanupOrphanedPageMedia(context: Context, activeMedia: List<PageMedia>) {
        val root = File(context.filesDir, ROOT)
        if (!root.exists()) return

        val activePaths = activeMedia.mapNotNull { media ->
            media.localPath?.takeIf { it.isNotBlank() }
                ?: resolveLocalFile(context, media).absolutePath
        }.toSet()

        root.walkTopDown().forEach { file ->
            if (file.isFile && !activePaths.contains(file.absolutePath)) {
                file.delete()
            }
        }
        // Prune now-empty directories.
        root.walkBottomUp().forEach { file ->
            if (file.isDirectory && file != root && (file.list()?.isEmpty() != false)) {
                file.delete()
            }
        }
    }

    fun deletePageFiles(context: Context, pageId: Long) {
        File(context.filesDir, "$ROOT/$pageId").deleteRecursively()
    }

    /** Convenience for callers that only have page metadata. */
    fun isPageMediaPresent(context: Context, page: Page, media: List<PageMedia>): Boolean =
        media.filter { it.pageId == page.id }.all { isPresent(context, it) }
}
