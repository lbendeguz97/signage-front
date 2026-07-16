package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AdDisplayLogDao {
    @Insert
    suspend fun insertLog(log: AdDisplayLog)

    @Query("SELECT * FROM ad_display_log WHERE syncStatus = 'PENDING'")
    suspend fun getPendingLogs(): List<AdDisplayLog>

    @Query("UPDATE ad_display_log SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markLogsSynced(ids: List<Long>)

    @Query("DELETE FROM ad_display_log WHERE syncStatus = 'SYNCED'")
    suspend fun deleteSyncedLogs()
}
