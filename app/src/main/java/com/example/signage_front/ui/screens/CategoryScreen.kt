package com.example.signage_front.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.LanguageManager
import com.example.signage_front.data.MenuCategory
import com.example.signage_front.data.PageWithLanguages
import com.example.signage_front.ui.composables.PageWidget

private val PageBackground = Color(0xFFEEEEEE)
private val CardBorder = Color(0xFFD9D9D9)
private val HeaderStrip = Color(0xFFF5F5F5)
private val TextDark = Color(0xFF222222)

/**
 * One screen per hardcoded category. Renders that category's synced pages
 * ("widgets") as a 3-column grid of rounded square cards, each with a title.
 */
@Composable
fun CategoryScreen(
    categoryId: Long,
    onBack: () -> Unit,
    onPageClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) { AdRepository(context) }
    val language by LanguageManager.language.collectAsState()
    val pages by repository.pageDao.getPagesWithLanguagesFlow(categoryId)
        .collectAsState(initial = emptyList())
    val category = MenuCategory.byId(categoryId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextDark
                )
            }
            Text(
                text = category?.label(language) ?: "",
                color = TextDark,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (pages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = LanguageManager.t(language, "Még nincs tartalom", "No content yet", "Noch keine Inhalte"),
                    color = TextDark.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(pages, key = { it.page.id }) { item ->
                    WidgetCard(
                        item = item,
                        language = language,
                        onClick = { onPageClick(item.page.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(
    item: PageWithLanguages,
    language: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
    ) {
        Text(
            text = item.page.title,
            color = TextDark,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderStrip)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PageWidget(
                page = item.page,
                languages = item.languages,
                language = language,
                modifier = Modifier.fillMaxSize()
            )
            // Transparent click target above the WebView, which otherwise
            // swallows touch events before the card's own clickable sees them.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onClick)
            )
        }
    }
}
