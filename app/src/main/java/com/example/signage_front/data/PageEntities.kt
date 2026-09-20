package com.example.signage_front.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

// Browseable HTML content ("Pages" module) synced from the signage server.
// Metadata lives in Room; HTML is plain text in Room; image bytes live on disk
// (see PageMediaManager).

@Entity(tableName = "page_categories")
data class PageCategory(
    @PrimaryKey val id: Long,
    val name: String
)

@Entity(tableName = "pages")
data class Page(
    @PrimaryKey val id: Long,
    val title: String,
    val categoryId: Long?,
    val rank: Int,
    val imagePath: String?,
    val defaultLanguage: String?,
    val updatedAt: String, // server pages.updated_at token for this page
    val syncStatus: String = "PENDING" // PENDING, DOWNLOADING, VERIFIED, ERROR
)

@Entity(tableName = "page_languages", primaryKeys = ["pageId", "language"])
data class PageLanguage(
    val pageId: Long,
    val language: String,
    val htmlContent: String,
    val updatedAt: String
)

// One row per page image (widget + embedded). localPath is null until the file
// has been downloaded/verified; the delete job reconciles rows and files.
@Entity(tableName = "page_media", primaryKeys = ["pageId", "isWidget", "name"])
data class PageMedia(
    val pageId: Long,
    val name: String,
    val isWidget: Boolean,
    val sizeBytes: Long,
    val localPath: String? = null,
    val downloadedAt: Long = 0L
)

// Page joined with its per-language HTML (used by the browsing UI).
data class PageWithLanguages(
    @Embedded val page: Page,
    @Relation(parentColumn = "id", entityColumn = "pageId")
    val languages: List<PageLanguage>
)
