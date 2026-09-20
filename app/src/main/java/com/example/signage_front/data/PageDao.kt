package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    // ---- Reads ----
    @Query("SELECT * FROM page_categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<PageCategory>>

    @Query("SELECT * FROM pages ORDER BY rank ASC, id DESC")
    fun getAllPages(): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE categoryId = :categoryId ORDER BY rank ASC, id DESC")
    fun getPagesByCategory(categoryId: Long): Flow<List<Page>>

    @Transaction
    @Query("SELECT * FROM pages WHERE categoryId = :categoryId ORDER BY rank ASC, id DESC")
    fun getPagesWithLanguagesFlow(categoryId: Long): Flow<List<PageWithLanguages>>

    @Transaction
    @Query("SELECT * FROM pages WHERE categoryId = :categoryId ORDER BY rank ASC, id DESC")
    suspend fun getPagesWithLanguages(categoryId: Long): List<PageWithLanguages>

    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPage(pageId: Long): Page?

    @Query("SELECT * FROM pages")
    suspend fun getAllPagesList(): List<Page>

    @Query("SELECT * FROM page_languages WHERE pageId = :pageId ORDER BY language ASC")
    suspend fun getPageLanguages(pageId: Long): List<PageLanguage>

    @Query("SELECT * FROM page_languages WHERE pageId = :pageId AND language = :language LIMIT 1")
    suspend fun getPageLanguage(pageId: Long, language: String): PageLanguage?

    @Query("SELECT * FROM page_media")
    suspend fun getAllPageMedia(): List<PageMedia>

    @Query("SELECT * FROM page_media WHERE pageId = :pageId")
    suspend fun getPageMedia(pageId: Long): List<PageMedia>

    // ---- Writes ----
    @Query("UPDATE pages SET syncStatus = :status WHERE id = :pageId")
    suspend fun updatePageSyncStatus(pageId: Long, status: String)

    @Query(
        "UPDATE page_media SET localPath = :localPath, downloadedAt = :downloadedAt " +
            "WHERE pageId = :pageId AND isWidget = :isWidget AND name = :name"
    )
    suspend fun updateMediaLocalPath(
        pageId: Long,
        isWidget: Boolean,
        name: String,
        localPath: String,
        downloadedAt: Long
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<PageCategory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<Page>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLanguages(languages: List<PageLanguage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: List<PageMedia>)

    @Query("DELETE FROM page_categories")
    suspend fun clearCategories()

    @Query("DELETE FROM pages")
    suspend fun clearPages()

    @Query("DELETE FROM page_languages")
    suspend fun clearLanguages()

    @Query("DELETE FROM page_languages WHERE pageId = :pageId")
    suspend fun deleteLanguagesForPage(pageId: Long)

    @Query("DELETE FROM page_languages WHERE pageId NOT IN (:pageIds)")
    suspend fun deleteLanguagesNotInPageList(pageIds: List<Long>)

    @Query("DELETE FROM page_media")
    suspend fun clearMedia()

    /**
     * Reconciles the local catalog with the server catalog: drops pages,
     * categories, languages and media rows that no longer exist server-side,
     * then upserts the current set.
     */
    @Transaction
    suspend fun syncCatalog(categories: List<PageCategory>, pages: List<Page>, media: List<PageMedia>) {
        clearCategories()
        clearPages()
        clearMedia()
        if (categories.isNotEmpty()) insertCategories(categories)
        if (pages.isNotEmpty()) insertPages(pages) else clearLanguages()
        if (pages.isNotEmpty()) {
            deleteLanguagesNotInPageList(pages.map { it.id })
        }
        if (media.isNotEmpty()) insertMedia(media)
    }

    @Transaction
    suspend fun syncPageContent(pageId: Long, languages: List<PageLanguage>) {
        deleteLanguagesForPage(pageId)
        if (languages.isNotEmpty()) insertLanguages(languages)
    }

    @Transaction
    suspend fun clearAll() {
        clearCategories()
        clearPages()
        clearLanguages()
        clearMedia()
    }
}
