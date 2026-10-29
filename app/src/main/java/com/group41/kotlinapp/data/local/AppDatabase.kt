package com.group41.kotlinapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.dao.PreferencesDao
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.data.local.dao.PendingActionDao
import com.group41.kotlinapp.data.local.entity.PendingActionEntity

/**
 * Main Room Database class for the application.
 * Serves as the database holder and main access point to the underlying persisted data.
 */
@Database(
    entities = [ActivityEntity::class, UserPreferenceEntity::class
    , PendingActionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Exposes the ActivityDao for performing database operations on activities.
     * Room automatically generates the implementation for this abstract method at compile time.
     * Why abstract? Room's annotation processor (KSP/KAPT) generates the concrete implementation
     * of this database class and its DAOs at compile time. Declaring this function as abstract
     * allows Room to hook into the database instance and return its generated DAO implementation
     * without requiring manual boilerplate code.
     */
    abstract fun activityDao(): ActivityDao

    /**
     * Exposes the PreferencesDao for performing database operations on user preferences.
     * Abstract tell Kotlin that the implementation will be provided by a subclass (which Room
     * generates automatically)
     */
    abstract fun preferencesDao(): PreferencesDao


    /**
     * Exposes the PendingActionDao for managing the offline action queue.
     *
     * Declared as abstract so Room's annotation processor (KSP/KAPT) automatically
     * generates the underlying DAO implementation at compile time and binds it to
     * the database instance.
     */
    abstract fun pendingActionDao(): PendingActionDao
}