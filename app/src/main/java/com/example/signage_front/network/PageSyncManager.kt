package com.example.signage_front.network

import android.content.Context
import android.util.Log
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.Page
import com.example.signage_front.data.PageCategory
import com.example.signage_front.data.PageLanguage
import com.example.signage_front.data.PageMedia
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separate handler for the "Pages" module. Keeps the local Room catalog + page
 * image files in sync with the server:
 *
 *  1. GET /getPages                      -> reconcile categories/pages/media manifest
 *  2. GET /getPageContent?page_id=       -> fetch HTML for new/changed pages
 *  3. download widget/embedded images    -> size-verified local files
 *  4. reconcile + delete orphans          -> local state matches server DB
 *
 * The global `sync_state['pages']` token is only advanced after a fully
 * successful sync (mirrors the ad_registry flow).
 */
object PageSyncManager {
    private const val TAG = "PageSyncManager"

    /**
     * Full catalog sync. Returns true when every page's HTML and media were
     * fetched/verified (so the caller may advance the sync token).
     */
    suspend fun syncPagesIfNeeded(context: Context): Boolean {
        val repository = AdRepository(context)
        val pageDao = repository.pageDao

        return try {
            val catalogJson = fetch(context, "getPages") ?: return false
            val catalog = JSONObject(catalogJson)

            val localPages = pageDao.getAllPagesList().associateBy { it.id }
            val localMedia = pageDao.getAllPageMedia().associateBy { mediaKey(it) }

            // ---- Parse categories ----
            val categoriesArr = catalog.optJSONArray("categories") ?: JSONArray()
            val categories = buildList {
                for (i in 0 until categoriesArr.length()) {
                    val o = categoriesArr.getJSONObject(i)
                    add(PageCategory(o.getLong("id"), o.getString("name")))
                }
            }

            // ---- Parse pages + media manifest ----
            val pagesArr = catalog.optJSONArray("pages") ?: JSONArray()
            val pages = mutableListOf<Page>()
            val media = mutableListOf<PageMedia>()
            val changedPages = mutableSetOf<Long>()
            val expectedLanguages = mutableMapOf<Long, Int>()

            for (i in 0 until pagesArr.length()) {
                val o = pagesArr.getJSONObject(i)
                val id = o.getLong("id")
                val updatedAt = o.optString("updated_at", "")
                val existing = localPages[id]
                val changed = existing == null || existing.updatedAt != updatedAt
                if (changed) changedPages.add(id)
                expectedLanguages[id] = o.optJSONArray("languages")?.length() ?: 0

                pages.add(
                    Page(
                        id = id,
                        title = o.optString("title", ""),
                        categoryId = if (o.isNull("category_id")) null else o.getLong("category_id"),
                        rank = o.optInt("rank", 0),
                        imagePath = if (o.isNull("image_path")) null else o.getString("image_path"),
                        defaultLanguage = if (o.isNull("default_language")) null else o.getString("default_language"),
                        updatedAt = updatedAt,
                        // Keep the local status when the page content is unchanged.
                        syncStatus = if (existing != null && !changed) existing.syncStatus else "PENDING"
                    )
                )

                if (!o.isNull("widget")) {
                    val w = o.getJSONObject("widget")
                    media.add(buildMedia(id, w.getString("name"), true, w.optLong("size", 0L), localMedia))
                }
                val imagesArr = o.optJSONArray("images") ?: JSONArray()
                for (j in 0 until imagesArr.length()) {
                    val im = imagesArr.getJSONObject(j)
                    media.add(buildMedia(id, im.getString("name"), false, im.optLong("size", 0L), localMedia))
                }
            }

            // 1. Persist catalog; deletes local rows that vanished server-side.
            pageDao.syncCatalog(categories, pages, media)

            val failedPages = mutableSetOf<Long>()

            // 2. Fetch HTML for new/changed pages.
            for (page in pages) {
                if (page.id !in changedPages) continue
                val contentJson = fetch(context, "getPageContent", "page_id" to page.id.toString())
                if (contentJson == null) {
                    Log.w(TAG, "Failed to fetch content for page ${page.id}")
                    failedPages.add(page.id)
                    continue
                }
                val content = JSONObject(contentJson)
                val langsArr = content.optJSONArray("languages") ?: JSONArray()
                val languages = buildList {
                    for (k in 0 until langsArr.length()) {
                        val l = langsArr.getJSONObject(k)
                        add(
                            PageLanguage(
                                pageId = page.id,
                                language = l.getString("language"),
                                htmlContent = l.optString("html_content", ""),
                                updatedAt = l.optString("updated_at", "")
                            )
                        )
                    }
                }
                // Guard against a transient/empty content response: if the catalog
                // says this page has languages but we got none, keep it unverified
                // so the next cycle retries instead of showing an empty widget.
                if (languages.isEmpty() && (expectedLanguages[page.id] ?: 0) > 0) {
                    Log.w(TAG, "Page ${page.id}: catalog listed ${expectedLanguages[page.id]} language(s) but content was empty")
                    failedPages.add(page.id)
                    continue
                }
                pageDao.syncPageContent(page.id, languages)
            }

            // 3. Download media for changed pages, or whenever a file is missing/stale.
            for (m in media) {
                val needsDownload = m.pageId in changedPages || !PageMediaManager.isPresent(context, m)
                if (!needsDownload) continue

                pageDao.updatePageSyncStatus(m.pageId, "DOWNLOADING")
                if (PageMediaManager.downloadIfNeeded(context, m)) {
                    val file = PageMediaManager.resolveLocalFile(context, m)
                    pageDao.updateMediaLocalPath(
                        m.pageId, m.isWidget, m.name, file.absolutePath, System.currentTimeMillis()
                    )
                } else {
                    Log.w(TAG, "Failed to download media ${m.name} for page ${m.pageId}")
                    failedPages.add(m.pageId)
                }
            }

            // 4. Mark each page verified / error.
            for (page in pages) {
                val pageMedia = media.filter { it.pageId == page.id }
                val mediaReady = pageMedia.all { PageMediaManager.isPresent(context, it) }
                val status = if (page.id in failedPages || !mediaReady) "ERROR" else "VERIFIED"
                pageDao.updatePageSyncStatus(page.id, status)
            }

            // 5. Delete local image files that no longer map to a page.
            PageMediaManager.cleanupOrphanedPageMedia(context, pageDao.getAllPageMedia())

            val success = failedPages.isEmpty()
            Log.d(TAG, "Pages sync done: ${pages.size} pages, ${media.size} media, failures=$failedPages")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Pages sync failed", e)
            false
        }
    }

    /**
     * Lightweight periodic pass: retries any page that is not VERIFIED or whose
     * media file went missing locally, otherwise just removes orphaned files so
     * local state keeps matching the server.
     */
    suspend fun checkup(context: Context) {
        try {
            val repository = AdRepository(context)
            val pageDao = repository.pageDao

            val pages = pageDao.getAllPagesList()
            val media = pageDao.getAllPageMedia()
            val hasUnverified = pages.any { it.syncStatus != "VERIFIED" }
            val hasMissingMedia = media.any { !PageMediaManager.isPresent(context, it) }

            if (hasUnverified || hasMissingMedia) {
                syncPagesIfNeeded(context)
                return
            }
            PageMediaManager.cleanupOrphanedPageMedia(context, media)
        } catch (e: Exception) {
            Log.e(TAG, "Pages checkup failed", e)
        }
    }

    private fun mediaKey(media: PageMedia) = "${media.pageId}:${media.isWidget}:${media.name}"

    private fun buildMedia(
        pageId: Long,
        name: String,
        isWidget: Boolean,
        size: Long,
        existing: Map<String, PageMedia>
    ): PageMedia {
        // Preserve the local path only when the file's expected size is unchanged.
        val prev = existing["$pageId:$isWidget:$name"]?.takeIf { it.sizeBytes == size }
        return PageMedia(
            pageId = pageId,
            name = name,
            isWidget = isWidget,
            sizeBytes = size,
            localPath = prev?.localPath,
            downloadedAt = prev?.downloadedAt ?: 0L
        )
    }

    private suspend fun fetch(
        context: Context,
        path: String,
        vararg params: Pair<String, String>
    ): String? {
        if (!SecurityManager.hasValidKey()) return null
        val client = NetworkClientProvider.getMTlsClient(context)
        val urlBuilder = "${Config.currentBaseUrl}/$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        params.forEach { urlBuilder.addQueryParameter(it.first, it.second) }

        val request = Request.Builder().url(urlBuilder.build()).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request to /$path failed", e)
            null
        }
    }
}
