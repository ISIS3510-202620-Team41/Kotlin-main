package com.group41.kotlinapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.group41.kotlinapp.data.local.dao.UserEnrollmentDao
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.dao.SearchHistoryDao
import com.group41.kotlinapp.data.local.dao.SyncLogDao
import com.group41.kotlinapp.data.local.entity.SearchHistoryEntity
import com.group41.kotlinapp.data.local.entity.SyncLogEntity
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
    entities = [
        ActivityEntity::class,
        UserPreferenceEntity::class,
        PendingActionEntity::class,
        UserEnrollmentEntity::class,
        SearchHistoryEntity::class,
        SyncLogEntity::class
    ],
    // La version sube cada vez que cambia la forma de alguna tabla, y por cada salto tiene
    // que existir una migracion en Migrations.kt, o la app se cerrara al abrirse en un
    // telefono que ya tenia la version anterior instalada.
    //
    // Pasa a 5 porque se agregaron dos tablas nuevas: `search_history`, el historial de
    // busquedas por cercania, y `sync_log`, el registro de intentos de sincronizacion.
    // Agregar una tabla cambia la forma de la base igual que agregar una columna.
    version = 5,
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

    /** Provides the Room DAO for account-scoped enrollment records. */
    abstract fun userEnrollmentDao(): UserEnrollmentDao

    /**
     * Expone las consultas del historial de busquedas por cercania.
     */
    abstract fun searchHistoryDao(): SearchHistoryDao

    /**
     * Expone las consultas del registro de intentos de sincronizacion.
     */
    abstract fun syncLogDao(): SyncLogDao
}
