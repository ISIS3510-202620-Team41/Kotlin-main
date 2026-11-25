package com.group41.kotlinapp.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.entity.SearchHistoryEntity
import com.group41.kotlinapp.data.local.entity.SyncLogEntity
import com.group41.kotlinapp.data.local.entity.SyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprueba que actualizar la app no le borra los datos al usuario.
 *
 * Este es el test mas importante de la migracion y, a la vez, el unico que no se puede
 * correr en el computador: necesita el SQLite de verdad de Android, asi que hay que
 * ejecutarlo en un emulador o en un telefono conectado.
 *
 * Lo que simula es exactamente lo que le pasa a alguien que ya tenia la app instalada: su
 * telefono tiene la base en una version vieja y al actualizar se encuentra con un codigo
 * que espera la version 3.
 * Opens a version 1 database that lacks pendingSync and verifies that the
 * current app can migrate it to version 4 without losing cached activities.
 *
 * Si la migracion estuviera mal, el sintoma seria el peor posible: la app se cerraria
 * nada mas abrirse, en los telefonos de quienes ya la usaban, y no en el del desarrollador
 * que instala de cero.
 *
 * Hay dos tests y hacen falta los dos:
 *
 * - Uno sube desde la version 1, que es el usuario mas viejo. Room tiene que encadenar
 *   las dos migraciones el solo, 1 -> 2 y 2 -> 3.
 * - El otro sube desde la version 2, que es quien instalo la app hace poco. Ese salta una
 *   sola migracion, y es el caso que se rompe si MIGRATION_2_3 tiene un error de SQL.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Se borra por si quedo de una ejecucion anterior: el test tiene que empezar
        // siempre desde una base recien creada en version 1.
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrarDeLaVersion1ALa5ConservaLasActividadesGuardadas() {
        // --- 1. Se fabrica a mano una base como la que tendria un usuario con la app vieja.
        crearBaseVersion1 { db ->
            db.execSQL(
                "INSERT INTO activities VALUES (" +
                    "'act-1', 'Futbol', 'Deportes', 60, 500, 4.6, -74.0, 0.9, 1, " +
                    "NULL, NULL, 'Gratis', NULL, 3, NULL, 'Cancha 2', NULL, NULL, NULL, NULL)"
            )
        }

        // 2. Open the database with Room and run the registered migrations.
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        try {
            val guardada = runBlocking { database.activityDao().getActivityById("act-1") }

            // --- 3. La actividad del usuario sigue ahi, con sus datos intactos.
            assertEquals("Futbol", guardada?.name)
            assertEquals("Cancha 2", guardada?.locationName)
            assertTrue(guardada?.joined == true)

            // --- 4. Y la columna nueva aparece con el valor correcto.
            // false es lo que corresponde: todo lo que estaba guardado antes de esta version
            // vino del servidor, asi que ya estaba sincronizado. Si saliera true, la app le
            // mostraria "pendiente de sincronizar" a inscripciones que hace rato se enviaron.
            assertFalse(guardada?.pendingSync == true)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrarDeLaVersion2ALa5CreaLasTablasNuevasSinTocarLasViejas() {
        // --- 1. Una base en version 2: ya tiene `pendingSync`, pero no las tablas nuevas.
        // Se guarda una inscripcion pendiente a proposito, porque es el dato que mas
        // dolerian perder: es una accion del usuario que el servidor todavia no conoce.
        crearBaseVersion2 { db ->
            db.execSQL(
                "INSERT INTO activities VALUES (" +
                    "'act-9', 'Yoga', 'Bienestar', 45, 800, 4.65, -74.05, 0.8, 1, " +
                    "NULL, NULL, 'Gratis', NULL, 5, NULL, 'Salon A', NULL, NULL, NULL, NULL, 1)"
            )
            db.execSQL(
                "INSERT INTO pending_actions VALUES (" +
                    "'JOIN_ACTIVITY:u1:act-9', 'u1', 'JOIN_ACTIVITY', '{}', 1700000000000, 0)"
            )
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        try {
            runBlocking {
                // --- 2. Lo que ya estaba sigue intacto, con su marca de pendiente.
                val guardada = database.activityDao().getActivityById("act-9")
                assertEquals("Yoga", guardada?.name)
                assertTrue(guardada?.pendingSync == true)

                // --- 3. Las tablas nuevas existen y se pueden usar.
                // Que una consulta sobre ellas no lance excepcion es la unica prueba real
                // de que el CREATE TABLE de la migracion quedo bien escrito: si faltara una
                // columna, o tuviera otro tipo, Room ya habria fallado al abrir la base.
                database.searchHistoryDao().recordSearch(
                    SearchHistoryEntity(
                        userId = "u1",
                        latitude = 4.65,
                        longitude = -74.05,
                        radiusKm = 2.0,
                        resultCount = 3,
                        wasOffline = true,
                        searchedAt = 1700000001000
                    )
                )

                database.syncLogDao().insert(
                    SyncLogEntity(
                        actionId = "JOIN_ACTIVITY:u1:act-9",
                        userId = "u1",
                        actionType = "JOIN_ACTIVITY",
                        outcome = SyncOutcome.RETRY,
                        // Nulo a proposito: es el caso de "no hubo respuesta del servidor",
                        // y comprueba que la columna admite nulos como dice la entidad.
                        httpCode = null,
                        attemptedAt = 1700000002000
                    )
                )

                val usage = database.searchHistoryDao().offlineUsage("u1")
                assertEquals(1, usage.offlineCount)
                assertEquals(0, usage.onlineCount)

                // --- 4. Y el LEFT JOIN entre las dos tablas funciona de verdad.
                // Aqui el payload NO es nulo porque la fila sigue en la cola, que es lo
                // correcto para una accion que se va a reintentar.
                val historial = database.syncLogDao().historyFor("JOIN_ACTIVITY:u1:act-9")
                assertEquals(1, historial.size)
                assertEquals(SyncOutcome.RETRY, historial.first().outcome)
            }
        } finally {
            database.close()
        }
    }

    /**
     * Crea la base de datos tal como la dejaba la version 1 de la app.
     *
     * Las tablas se escriben a mano porque esa version del codigo ya no existe: la entidad
     * `ActivityEntity` de hoy incluye la columna nueva. El SQL de abajo es el que Room
     * generaba entonces.
     *
     * Las tres tablas tienen que estar, no solo `activities`: cuando Room abre una base
     * revisa que TODAS coincidan con lo que el codigo espera, y si falta una se queja.
     *
     * `version = 1` al final es lo que le dice a Android "esta base es vieja", y es lo que
     * dispara la migracion al abrirla con el codigo nuevo.
     */
    private fun crearBaseVersion1(llenarDatos: (SupportSQLiteDatabase) -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activities` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`durationMinutes` INTEGER NOT NULL, `distanceMeters` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `score` REAL NOT NULL, " +
                        "`joined` INTEGER NOT NULL, `recommendationId` TEXT, `freeTimeMinutes` INTEGER, " +
                        "`price` TEXT NOT NULL, `capacity` INTEGER, `participants` INTEGER NOT NULL, " +
                        "`aboutLocation` TEXT, `locationName` TEXT, `imageUrl` TEXT, " +
                        "`description` TEXT, `startTime` TEXT, `endTime` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_preferences` (" +
                        "`userId` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "PRIMARY KEY(`userId`, `category`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_actions` (" +
                        "`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `actionType` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                llenarDatos(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No hace falta: esta base nace en la version 1 y la actualiza Room despues.
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        // Pedir la base en modo escritura es lo que dispara onCreate. Se cierra enseguida
        // para que Room la encuentre libre.
        helper.writableDatabase.close()
    }

    /**
     * Crea la base de datos tal como la dejaba la version 2 de la app.
     *
     * Es igual que la version 1 salvo en una cosa: `activities` ya trae la columna
     * `pendingSync` al final, que es justo lo que agrego MIGRATION_1_2. Las tablas
     * `search_history` y `sync_log` todavia no existen, porque las agrega la migracion que
     * este test comprueba.
     *
     * La columna va al final y no en el medio porque ALTER TABLE ADD COLUMN siempre
     * agrega al final. Ponerla en otro sitio aqui haria que el test probara un esquema
     * que ningun telefono real llego a tener.
     */
    private fun crearBaseVersion2(llenarDatos: (SupportSQLiteDatabase) -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activities` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`durationMinutes` INTEGER NOT NULL, `distanceMeters` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `score` REAL NOT NULL, " +
                        "`joined` INTEGER NOT NULL, `recommendationId` TEXT, `freeTimeMinutes` INTEGER, " +
                        "`price` TEXT NOT NULL, `capacity` INTEGER, `participants` INTEGER NOT NULL, " +
                        "`aboutLocation` TEXT, `locationName` TEXT, `imageUrl` TEXT, " +
                        "`description` TEXT, `startTime` TEXT, `endTime` TEXT, " +
                        "`pendingSync` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_preferences` (" +
                        "`userId` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "PRIMARY KEY(`userId`, `category`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_actions` (" +
                        "`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `actionType` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                llenarDatos(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No hace falta: esta base nace en la version 2 y la actualiza Room despues.
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.close()
    }
}
