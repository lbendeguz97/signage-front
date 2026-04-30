package com.example.signage_front.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class AdRepository(context: Context) {
    private val adDao = AppDatabase.getDatabase(context).adDao()

    fun getAllAds(): Flow<List<AdStatus>> = adDao.getAllAds()

    suspend fun syncAds(ads: List<AdStatus>) {
        adDao.syncAds(ads)
    }

    suspend fun clearStatus() {
        adDao.clearAdStatus()
    }
}
