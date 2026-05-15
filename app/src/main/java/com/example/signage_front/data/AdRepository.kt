package com.example.signage_front.data

import android.content.Context
import android.util.Log
import com.example.signage_front.network.MediaManager
import kotlinx.coroutines.flow.Flow

class AdRepository(private val context: Context) {
    private val TAG = "AdRepository"
    private val database = AppDatabase.getDatabase(context)
    val adDao = database.adDao()
    private val syncDao = database.syncDao()

    fun getAllAds(): Flow<List<AdStatus>> = adDao.getAllAds()

    /**
     * Performs a full sync of ads and manages media files.
     * Returns true only if the database sync and ALL media downloads/verifications succeeded.
     */
    suspend fun syncAds(ads: List<AdStatus>): Boolean {
        return try {
            // 1. Merge status from local DB to avoid overwriting "VERIFIED" with "PENDING"
            val existingAds = adDao.getAllAdsList().associateBy { it.adId }
            val mergedAds = ads.map { incoming ->
                val existing = existingAds[incoming.adId]
                // If the ad exists and the checksum is the same, keep the local sync status
                if (existing != null && existing.expectedChecksum == incoming.expectedChecksum) {
                    incoming.copy(syncStatus = existing.syncStatus)
                } else {
                    incoming
                }
            }

            // 2. Sync Database records (Transaction)
            adDao.syncAds(mergedAds)
            
            // 3. Download and Verify media for anything not yet VERIFIED
            var allMediaSynced = true
            val adsToProcess = adDao.getAllAdsList()
            
            adsToProcess.forEach { ad ->
                if (ad.syncStatus == "VERIFIED") return@forEach

                try {
                    adDao.updateSyncStatus(ad.adId, "DOWNLOADING")
                    
                    val downloadSuccess = MediaManager.downloadMediaIfNeeded(context, ad)
                    if (downloadSuccess) {
                        val localFile = MediaManager.getLocalFile(context, ad)
                        val isVerified = MediaManager.verifyChecksum(localFile, ad.expectedChecksum)
                        
                        if (isVerified) {
                            adDao.updateSyncStatus(ad.adId, "VERIFIED")
                            Log.d(TAG, "Ad ${ad.adId} verified and ready.")
                        } else {
                            // If verification fails, delete the file to force a redownload next time
                            if (localFile.exists()) localFile.delete()
                            adDao.updateSyncStatus(ad.adId, "ERROR")
                            Log.e(TAG, "Checksum mismatch for ad ${ad.adId}. File deleted.")
                            allMediaSynced = false
                        }
                    } else {
                        adDao.updateSyncStatus(ad.adId, "ERROR")
                        Log.e(TAG, "Failed to download media for ad ${ad.adId}")
                        allMediaSynced = false
                    }
                } catch (e: Exception) {
                    adDao.updateSyncStatus(ad.adId, "ERROR")
                    Log.e(TAG, "Error processing ad ${ad.adId}", e)
                    allMediaSynced = false
                }
            }
            
            // 4. Cleanup files for removed ads
            MediaManager.cleanupOrphanedMedia(context, ads)
            
            allMediaSynced
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during syncAds", e)
            false
        }
    }

    suspend fun getSyncState(tableName: String): SyncState? = syncDao.getSyncStateByTable(tableName)
    
    suspend fun updateSyncState(tableName: String, token: String) {
        syncDao.updateSyncState(SyncState(tableName, token))
    }

    suspend fun clearStatus() {
        adDao.clearAdStatus()
        syncDao.clearAllSyncStates()
        MediaManager.cleanupOrphanedMedia(context, emptyList())
    }
}
