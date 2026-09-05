package com.example.signage_front.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AdStatus::class,
        SyncState::class,
        AdDisplayLog::class,
        GroupConfig::class,
        Playlist::class,
        PlaylistAd::class,
        Campaign::class,
        CampaignSchedule::class,
        Schedule::class,
        ScheduleInterval::class,
        ScheduleRule::class,
        CampaignTrigger::class,
        TabletMetadata::class,
        SspConnectivity::class,
        CachedSspAd::class,
        PendingBeacon::class,
        SspSlotLog::class
    ],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun adDao(): AdDao
    abstract fun syncDao(): SyncDao
    abstract fun adDisplayLogDao(): AdDisplayLogDao
    abstract fun configDao(): ConfigDao
    abstract fun sspSlotLogDao(): SspSlotLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "signage_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
