package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SspSlotLogDao {
    @Insert
    suspend fun insert(log: SspSlotLog): Long

    @Query("SELECT * FROM ssp_slot_log ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecent(): List<SspSlotLog>

    @Query("SELECT COUNT(*) FROM ssp_slot_log")
    suspend fun count(): Int
}