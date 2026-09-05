package com.example.signage_front.data

import java.io.File

/**
 * Unified content model that [com.example.signage_front.ui.screens.AdScreen] operates on.
 * Decouples the player from raw [AdStatus] rows and lets any content source
 * (playlist, group SSP, logo) be represented uniformly.
 */
sealed class PlaylistItem {

    // Standard verified local media (video / image / html)
    data class Standard(
        val adStatus: AdStatus,
        val durationOverride: Int? // playlist_ads.duration override; null = use ad's default displayTime
    ) : PlaylistItem()

    // SSP slot embedded in a playlist
    data class VirtualSsp(
        val adStatusId: Long,
        val durationBudget: Int,
        val sspConnectivity: SspConnectivity?,
        val fallbackFile: File? = null
    ) : PlaylistItem()

    // Pure group-level SSP (no playlist context)
    data class GroupSspSlot(
        val connectivity: SspConnectivity,
        val fallbackFile: File? = null
    ) : PlaylistItem()

    // Logo fallback
    object Logo : PlaylistItem()
}
