package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AdDao {
    @Query("SELECT * FROM ad_status")
    fun getAllAds(): Flow<List<AdStatus>>

    @Query("SELECT * FROM ad_status WHERE adId = :adId")
    suspend fun getAdById(adId: String): AdStatus?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAds(ads: List<AdStatus>)

    @Query("UPDATE ad_status SET syncStatus = :status WHERE adId = :adId")
    suspend fun updateSyncStatus(adId: String, status: String)

    @Query("DELETE FROM ad_status WHERE adId NOT IN (:adIds)")
    suspend fun deleteAdsNotInList(adIds: List<String>)

    @Query("DELETE FROM ad_status")
    suspend fun clearAdStatus()

    @Transaction
    suspend fun syncAds(ads: List<AdStatus>) {
        val adIds = ads.map { it.adId }
        deleteAdsNotInList(adIds)
        insertAds(ads)
    }
}
