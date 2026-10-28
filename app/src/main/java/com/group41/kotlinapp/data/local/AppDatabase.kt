package com.group41.kotlinapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.entity.ActivityEntity

/**
 * Main Room Database class for the application.
 * Serves as the database holder and main access point to the underlying persisted data.
 */
@Database(
    entities = [ActivityEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Exposes the ActivityDao for performing database operations on activities.
     * Room automatically generates the implementation for this abstract method at compile time.
     */
    abstract fun activityDao(): ActivityDao
}