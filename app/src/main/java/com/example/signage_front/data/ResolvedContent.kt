package com.example.signage_front.data

/**
 * The resolved active content source at any given moment, produced by [ContentOrchestrator].
 *
 * Levels 1–3 and 5 resolve to a playlist; level 4 is a pure programmatic SSP loop;
 * level 6 is a static logo fallback.
 */
sealed class ResolvedContent {

    // Levels 1–3 and 5: content from a playlist
    data class PlaylistContent(
        val playlistId: Long,
        val source: Source
    ) : ResolvedContent() {
        enum class Source { TRIGGER, CAMPAIGN_SCHEDULE, DEFAULT_SCHEDULE, DEFAULT_PLAYLIST }
    }

    // Level 4: pure programmatic SSP loop
    data class GroupSsp(val connectivity: SspConnectivity) : ResolvedContent()

    // Level 6: show logo
    object Logo : ResolvedContent()
}
