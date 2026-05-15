package com.example.signage_front.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state")
    suspend fun getAllSyncStates(): List<SyncState>

    @Query("SELECT * FROM sync_state WHERE tableName = :tableName")
    suspend fun getSyncStateByTable(tableName: String): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncState(syncState: SyncState)

    @Query("DELETE FROM sync_state")
    suspend fun clearAllSyncStates()
}
