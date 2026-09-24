package com.example.signage_front.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.signage_front.data.AdRepository
import com.example.signage_front.data.LanguageManager
import com.example.signage_front.data.Page
import com.example.signage_front.data.PageLanguage
import com.example.signage_front.ui.composables.buildPageHtml
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData

private val HeaderStrip = Color(0xFFF5F5F5)
private val TextDark = Color(0xFF222222)

/**
 * Full-screen reader for a single synced page. Renders the stored HTML with a
 * JavaScript-disabled WebView (static HTML only); widget/embedded images are
 * inlined as base64 data URIs by [buildPageHtml].
 */
@Composable
fun PageViewScreen(
    pageId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) { AdRepository(context) }
    val language by LanguageManager.language.collectAsState()

    var page by remember(pageId) { mutableStateOf<Page?>(null) }
    var languages by remember(pageId) { mutableStateOf<List<PageLanguage>>(emptyList()) }
    var loaded by remember(pageId) { mutableStateOf(false) }

    LaunchedEffect(pageId) {
        page = repository.pageDao.getPage(pageId)
        languages = repository.pageDao.getPageLanguages(pageId)
        loaded = true
    }

    val html = remember(page?.id, page?.updatedAt, language, languages) {
        val p = page
        if (p == null) "" else buildPageHtml(context, p, languages, language)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderStrip)
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
                text = page?.title ?: "",
                color = TextDark,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (html.isNotBlank()) {
                val state = rememberWebViewStateWithHTMLData(data = html)
                WebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onCreated = { webView ->
                        webView.setBackgroundColor(android.graphics.Color.WHITE)
                        webView.settings.javaScriptEnabled = false
                        webView.settings.domStorageEnabled = false
                    }
                )
            } else if (loaded) {
                Text(
                    text = when {
                        page == null -> LanguageManager.t(
                            language,
                            "Az oldal nem található",
                            "Page not found",
                            "Seite nicht gefunden"
                        )
                        else -> LanguageManager.t(
                            language,
                            "Még nincs tartalom",
                            "No content yet",
                            "Noch keine Inhalte"
                        )
                    },
                    color = TextDark.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
