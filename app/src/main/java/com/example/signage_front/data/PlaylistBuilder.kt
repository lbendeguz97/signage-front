package com.example.signage_front.data

import android.util.Log

/**
 * Builds a [List] of [PlaylistItem] from a playlist id plus the verified ad registry.
 *
 * Phase 2: only the playlist path is wired. Orchestrator-driven sources
 * (group SSP, logo) will feed [PlaylistItem]s directly in Phase 3.
 *
 * Invariants (see ORCHESTRATION_PLAN §13):
 *  - Non-verified / missing ads are skipped (no crash, no black screen).
 *  - An empty result is valid and signals the caller to fall through.
 */
object PlaylistBuilder {
    private const val TAG = "PlaylistBuilder"

    /**
     * @param playlistAds ordered playlist entries (position ASC) for the active playlist
     * @param allAds all ads from the registry (already filtered to verified+allowed by the caller)
     * @param sspConnectivity group-level SSP connectivity (used for virtual SSP slots)
     * @param fallbackFile group-level fallback media file (fills idle SSP slot time)
     */
    fun buildPlaylistItems(
        playlistAds: List<PlaylistAd>,
        allAds: List<AdStatus>,
        sspConnectivity: SspConnectivity?,
        fallbackFile: java.io.File? = null
    ): List<PlaylistItem> {
        val byAdId = allAds.associateBy { it.adId }

        return playlistAds.mapNotNull { pa ->
            val ad = byAdId[pa.adId.toString()]
            if (ad == null) {
                Log.w(TAG, "Skipping playlist entry: ad_id=${pa.adId} not present in verified registry")
                return@mapNotNull null
            }
            if (ad.syncStatus != "VERIFIED" || !ad.adAllowed) {
                Log.d(TAG, "Skipping unverified/not-allowed ad: id=${ad.adId} status=${ad.syncStatus}")
                return@mapNotNull null
            }

            val durationOverride = pa.duration.takeIf { it > 0 }

            // CMS "Idle (SSP/Fallback)" slots are stored with media_type 'idle';
            // 'ssp' is accepted too. Both become virtual SSP slots that delegate
            // to the group's SSP connectivity.
            if (ad.mediaType?.lowercase() == "ssp" || ad.mediaType?.lowercase() == "idle") {
                PlaylistItem.VirtualSsp(
                    adStatusId = pa.adId,
                    durationBudget = durationOverride ?: (ad.displayTime ?: 10),
                    sspConnectivity = sspConnectivity,
                    fallbackFile = fallbackFile
                )
            } else {
                PlaylistItem.Standard(
                    adStatus = ad,
                    durationOverride = durationOverride
                )
            }
        }
    }
}
