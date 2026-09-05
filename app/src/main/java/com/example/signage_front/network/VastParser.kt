package com.example.signage_front.network

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Resolved final ad of a (possibly wrapped) VAST 3.0 response.
 */
data class VastAd(
    val adId: String?,
    val adTitle: String?,
    val adSystem: String?,
    val durationSeconds: Int,
    val mediaUrl: String?,
    val mediaType: String,
    val impressions: List<String>,
    val trackingEvents: Map<String, String>,
    val sequence: Int = 0
)

/**
 * Minimal VAST 3.0 parser for LMX ad serving responses.
 *
 * Handles Inline and Wrapper responses (LMX may hand back a VAST wrapper
 * pointing at a secondary ad server). Supports ad pods: multiple sibling
 * `<Ad>` elements (ordered by their `sequence` attribute) are returned in
 * play order, matching LMX's inventory-level "all deals in sequence".
 */
object VastParser {
    private const val TAG = "VastParser"
    private const val VAST_NS = "VAST"

    /** Events the player must fire per LMX docs. */
    val KNOWN_EVENTS = setOf("start", "firstQuartile", "midpoint", "thirdQuartile", "complete", "creativeView")

    /**
     * Parses a VAST XML document into its ads (pod order).
     *
     * @return the resolved ads (with media), or an empty list when the VAST is
     *         empty (no fill) or unparsable.
     */
    fun parse(xmlText: String): List<VastAd> {
        val ads = mutableListOf<VastAd>()
        try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }.newPullParser()
            parser.setInput(StringReader(xmlText))

            var adId: String? = null
            var adTitle: String? = null
            var adSystem: String? = null
            var durationSeconds = 0
            var mediaUrl: String? = null
            var mediaType = "video"
            var sequence = 0
            var bestMediaScore = -1
            val impressions = linkedSetOf<String>()
            val tracking = linkedMapOf<String, String>()

            var inAd = false
            var inLinear = false
            var inMediaFiles = false

            fun resetAd() {
                adId = null; adTitle = null; adSystem = null; durationSeconds = 0
                mediaUrl = null; mediaType = "video"; sequence = 0; bestMediaScore = -1
                impressions.clear(); tracking.clear()
            }
            fun finalizeAd() {
                if (mediaUrl != null) {
                    ads += VastAd(
                        adId = adId, adTitle = adTitle, adSystem = adSystem,
                        durationSeconds = durationSeconds,
                        mediaUrl = mediaUrl, mediaType = mediaType,
                        impressions = impressions.toList(),
                        trackingEvents = tracking.toMap(),
                        sequence = sequence
                    )
                }
                resetAd()
            }

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Ad" -> {
                            inAd = true
                            resetAd()
                            adId = parser.getAttributeValue(null, "id")
                            sequence = parser.getAttributeValue(null, "sequence")?.toIntOrNull() ?: 0
                        }
                        "AdTitle" -> if (inAd) adTitle = parser.nextTextOrNull()
                        "AdSystem" -> if (inAd) adSystem = parser.nextTextOrNull()
                        "Impression" -> {
                            val url = parser.nextTextOrNull()
                            if (!url.isNullOrBlank()) impressions.add(url.trim())
                        }
                        "Linear" -> inLinear = true
                        "Duration" -> if (inLinear) {
                            parser.nextTextOrNull()?.let { durationSeconds = parseDuration(it.trim()) }
                        }
                        "Tracking" -> if (inLinear) {
                            val event = parser.getAttributeValue(null, "event")
                            val url = parser.nextTextOrNull()
                            if (event in KNOWN_EVENTS && !url.isNullOrBlank() && !tracking.containsKey(event)) {
                                tracking[event!!] = url.trim()
                            }
                        }
                        "MediaFiles" -> if (inLinear) inMediaFiles = true
                        "MediaFile" -> if (inMediaFiles) {
                            val mime = parser.getAttributeValue(null, "type") ?: ""
                            val url = parser.nextTextOrNull()
                            if (!url.isNullOrBlank()) {
                                val score = mediaScore(mime)
                                if (score > bestMediaScore) {
                                    bestMediaScore = score
                                    mediaUrl = url.trim()
                                    mediaType = if (mime.startsWith("image/")) "image" else "video"
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "Ad" -> {
                            finalizeAd()
                            inAd = false
                        }
                        "Linear" -> inLinear = false
                        "MediaFiles" -> inMediaFiles = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VAST XML", e)
        }

        // Order by sequence (stable for equal/missing), then document order.
        val sorted = ads.sortedWith(compareBy({ it.sequence }, { ads.indexOf(it) }))
        Log.d(TAG, "VAST parsed: ${sorted.size} ad(s) in pod")
        return sorted
    }

    /** Extracts <VASTAdTagURI> from a Wrapper response, if present. */
    fun wrapperTarget(xmlText: String): String? {
        return try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }.newPullParser()
            parser.setInput(StringReader(xmlText))
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "VASTAdTagURI") {
                    return parser.nextTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to look for VASTAdTagURI", e)
            null
        }
    }

    /** Wrapper trackers gathered from a (possibly wrapper) document, without
     *  requiring a MediaFile. Wrapper impressions/trackers must fire alongside
     *  the inline ones per the VAST spec. */
    data class Trackers(
        val impressions: List<String>,
        val trackingEvents: Map<String, String>
    )

    fun parseTrackers(xmlText: String): Trackers {
        val impressions = linkedSetOf<String>()
        val tracking = linkedMapOf<String, String>()
        try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }.newPullParser()
            parser.setInput(StringReader(xmlText))

            var inLinear = false
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Impression" -> parser.nextTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { impressions.add(it) }
                        "Linear" -> inLinear = true
                        "Tracking" -> if (inLinear) {
                            val event = parser.getAttributeValue(null, "event")
                            val url = parser.nextTextOrNull()
                            if (event in KNOWN_EVENTS && !url.isNullOrBlank() && !tracking.containsKey(event)) {
                                tracking[event!!] = url.trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "Linear") inLinear = false
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VAST trackers", e)
        }
        return Trackers(impressions.toList(), tracking)
    }

    private fun XmlPullParser.nextTextOrNull(): String? {
        return try { nextText() } catch (e: Exception) { null }
    }

    /** "00:00:15" or "15" -> seconds. */
    private fun parseDuration(raw: String): Int {
        val parts = raw.split(":")
        return when {
            parts.size == 3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toFloatOrNull() ?: 0f).toInt()
            parts.size == 2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toFloatOrNull() ?: 0f).toInt()
            else -> raw.toFloatOrNull()?.toInt() ?: 0
        }
    }

    private fun mediaScore(mime: String): Int = when {
        mime.equals("video/mp4", ignoreCase = true) -> 3
        mime.startsWith("video/", ignoreCase = true) -> 2
        mime.startsWith("image/", ignoreCase = true) -> 1
        else -> 0
    }
}
