package com.example.signage_front.ui.composables

import android.content.Context
import android.util.Base64
import com.example.signage_front.data.Page
import com.example.signage_front.data.PageLanguage
import java.io.File

/**
 * Builds renderable HTML for a page in the requested language, shared by the
 * category-grid widget ([PageWidget]) and the full-screen view.
 *
 * Language resolution order: exact match -> the page's default language -> the
 * first available language.
 *
 * Local widget/embedded images are inlined as base64 data URIs so no WebView
 * file access or network is required. `{{WIDGET_IMAGE}}` and relative
 * `src="name.ext"` refs are resolved to the files stored under
 * filesDir/pages/{id}/.
 */
internal fun buildPageHtml(
    context: Context,
    page: Page,
    languages: List<PageLanguage>,
    language: String
): String {
    val lang = languages.firstOrNull { it.language.equals(language, ignoreCase = true) }
        ?: languages.firstOrNull { it.language.equals(page.defaultLanguage, ignoreCase = true) }
        ?: languages.firstOrNull()
        ?: return ""

    var html = lang.htmlContent

    // Widget image placeholder.
    page.imagePath?.let { name ->
        readDataUri(File(context.filesDir, "pages/${page.id}/widget/$name"), mimeFor(name))?.let {
            html = html.replace("{{WIDGET_IMAGE}}", it)
        }
    }

    // Relative embedded images: src="name.png" / src='name.png' / src=name.png
    val srcRegex = Regex("""src\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""", RegexOption.IGNORE_CASE)
    html = srcRegex.replace(html) { match ->
        val raw = match.groupValues[2]
            .ifBlank { match.groupValues[3] }
            .ifBlank { match.groupValues[4] }
        if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("http://") ||
            raw.startsWith("https://") || raw.contains("{{")
        ) {
            match.value
        } else {
            val safeName = File(raw).name
            val uri = readDataUri(File(context.filesDir, "pages/${page.id}/images/$safeName"), mimeFor(safeName))
            if (uri != null) "src=\"$uri\"" else match.value
        }
    }

    return html
}

private fun readDataUri(file: File, mime: String): String? {
    if (!file.exists() || !file.isFile) return null
    return try {
        val bytes = file.readBytes()
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}
