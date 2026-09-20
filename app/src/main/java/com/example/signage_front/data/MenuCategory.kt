package com.example.signage_front.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.ui.graphics.vector.ImageVector

// Hardcoded browse categories. IDs must stay in sync with:
//   - signagedashboard/backend/src/migrations/007_seed_page_categories.sql
//   - signage/server/categories.js
// Pages are associated to a category through pages.category_id.
enum class MenuCategory(
    val id: Long,
    val key: String,
    val labelHu: String,
    val labelEn: String,
    val icon: ImageVector
) {
    RESTAURANTS(1L, "restaurants", "Éttermek", "Restaurants", Icons.Filled.Restaurant),
    HOTELS(2L, "hotels", "Szállodák", "Hotels", Icons.Filled.Hotel),
    ENTERTAINMENT(3L, "entertainment", "Szórakozás", "Entertainment", Icons.Filled.TheaterComedy),
    EVENTS(4L, "events", "Események", "Events", Icons.Filled.Event);

    fun label(language: String): String = if (language == LanguageManager.EN) labelEn else labelHu

    companion object {
        fun byId(id: Long): MenuCategory? = entries.firstOrNull { it.id == id }
    }
}
