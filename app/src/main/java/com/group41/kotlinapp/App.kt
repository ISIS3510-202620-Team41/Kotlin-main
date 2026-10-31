package com.group41.kotlinapp

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.MIGRATION_1_2
import com.group41.kotlinapp.network.ApiClient
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    // Startup work belongs to the application, so rotating or closing a screen will
    // not cancel the migration. Disk access runs away from the main thread.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Persistent Room database instance shared across the entire application lifecycle.
     *
     * Initialized lazily via 'by lazy' to ensure the database build process only runs
     * upon first access and remains thread-safe.
     * Lazy delegates creation until the property is accessed for the first time, preventing
     * unnecessary startup delay during onCreate().
     */
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "llamalla.db"
        )
            // Sin esta linea Room no sabria como pasar de la version 1 a la 2 y cerraria
            // la app al abrirla. Con ella, el usuario que ya tenia la app instalada
            // conserva todo lo que habia descargado.
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        applicationScope.launch {
            try {
                // Opening DataStore runs its migration for existing signed-in users.
                // This also covers users who skip login because their session is saved.
                // DataStore makes other readers/writers wait for that same migration,
                // so a worker cannot record its timestamp before the UUID is available.
                AppPreferences(this@App).userId.first()
            } catch (error: IOException) {
                // Leave the original session intact if storage is temporarily unavailable.
                // DataStore will attempt migration again on the next access.
                Log.e("App", "Unable to initialize account preferences", error)
            }
        }
        Analytics.init(this)
        // Con sesión guardada se usa el id de la última vez (paso 2 del contrato).
        ApiClient.tokens.userId?.let { Analytics.setUserId(it) }
    }
}
