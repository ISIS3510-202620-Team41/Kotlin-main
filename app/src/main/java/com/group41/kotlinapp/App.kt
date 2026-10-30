package com.group41.kotlinapp

import android.app.Application
import androidx.room.Room
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.MIGRATION_1_2
import com.group41.kotlinapp.network.ApiClient

class App : Application() {

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
        Analytics.init(this)
        // Con sesión guardada se usa el id de la última vez (paso 2 del contrato).
        ApiClient.tokens.userId?.let { Analytics.setUserId(it) }
    }
}