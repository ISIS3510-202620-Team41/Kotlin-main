package com.group41.kotlinapp.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Lo que simula es exactamente lo que le pasa a alguien que ya tenia la app instalada:
 * su telefono tiene la base en la version 1, sin la columna `pendingSync`, y al actualizar
 * se encuentra con un codigo que espera la version 2.
 *
 * Si la migracion estuviera mal, el sintoma seria el peor posible: la app se cerraria
 * nada mas abrirse, en los telefonos de quienes ya la usaban, y no en el del desarrollador
 * que instala de cero.
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
    fun migrarDeLaVersion1ALa2ConservaLasActividadesGuardadas() {
        // --- 1. Se fabrica a mano una base como la que tendria un usuario con la app vieja.
        crearBaseVersion1 { db ->
            db.execSQL(
                "INSERT INTO activities VALUES (" +
                    "'act-1', 'Futbol', 'Deportes', 60, 500, 4.6, -74.0, 0.9, 1, " +
                    "NULL, NULL, 'Gratis', NULL, 3, NULL, 'Cancha 2', NULL, NULL, NULL, NULL)"
            )
        }

        // --- 2. Se abre con el codigo actual, que espera la version 2.
        // Si la migracion no sirviera, esta linea lanzaria la excepcion de Room y el test
        // fallaria aqui mismo.
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
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
}
