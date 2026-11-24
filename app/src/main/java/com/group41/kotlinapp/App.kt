package com.group41.kotlinapp

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.MIGRATION_1_2
import com.group41.kotlinapp.data.local.MIGRATION_2_3
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.storage.FastKeyValueStore
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
            // Sin estas lineas Room no sabria como pasar de una version a la siguiente y
            // cerraria la app al abrirla. Con ellas, el usuario que ya tenia la app
            // instalada conserva todo lo que habia descargado.
            //
            // Se pasan todas las migraciones, no solo la ultima: un telefono que tenga
            // instalada la version 1 tiene que poder llegar a la 3 pasando por la 2, y
            // Room encadena los saltos el solo si los tiene todos a mano.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // MMKV se prepara antes que nada porque otras partes del arranque ya leen de el.
        // Es barato: solo mapea el archivo en memoria, no lo recorre.
        FastKeyValueStore.initialize(this)

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
        // El registro de sincronizaciones se poda al arrancar. Un intento fallido de hace
        // tres meses no le sirve a nadie, y la tabla crece sola porque nada la vacia.
        applicationScope.launch {
            try {
                val thirtyDaysAgo = System.currentTimeMillis() - THIRTY_DAYS_MILLIS
                database.syncLogDao().deleteOlderThan(thirtyDaysAgo)
            } catch (error: Exception) {
                // Limpiar es mantenimiento, no una funcion de la app: si falla, se
                // intentara en el siguiente arranque y nadie se queda sin servicio.
                Log.e("App", "No se pudo podar el registro de sincronizacion", error)
            }
        }

        Analytics.init(this)
        // Con sesión guardada se usa el id de la última vez (paso 2 del contrato).
        ApiClient.tokens.userId?.let { Analytics.setUserId(it) }
    }

    private companion object {
        /** Cuanto tiempo se conserva el registro de intentos de sincronizacion. */
        const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
