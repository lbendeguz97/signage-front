package com.example.signage_front.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.signage_front.data.Page
import com.example.signage_front.data.PageLanguage
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData

/**
 * Renders a single page ("widget") HTML for the selected language.
 *
 * Local widget/embedded images are inlined as base64 data URIs so no WebView file
 * access or network is required. `{{WIDGET_IMAGE}}` and relative `src="name.ext"`
 * refs are resolved to the files stored under filesDir/pages/{id}/.
 */
@Composable
fun PageWidget(
    page: Page,
    languages: List<PageLanguage>,
    language: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val html = remember(page.id, page.updatedAt, language, languages) {
        buildPageHtml(context, page, languages, language)
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (html.isBlank()) {
            Text(
                text = "—",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        } else {
            val state = rememberWebViewStateWithHTMLData(data = html)
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onCreated = { webView ->
                    webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webView.settings.javaScriptEnabled = false
                    webView.settings.domStorageEnabled = false
                }
            )
        }
    }
}
