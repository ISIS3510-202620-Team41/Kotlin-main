package com.group41.kotlinapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones de la base de datos local.
 *
 * ¿Que es una migracion y por que hay que escribirla a mano?
 *
 * Room guarda dentro del archivo de la base de datos un numero de version y una huella de
 * como estan hechas las tablas. Cuando la app arranca, compara esa huella con la del codigo.
 * Si no coinciden, Room no adivina: lanza un error y la app se cierra al abrirse.
 *
 * Eso pasa porque, al agregar una columna nueva en el codigo, el telefono de alguien que ya
 * tenia la app instalada sigue teniendo la tabla vieja, sin esa columna. Room no puede saber
 * por su cuenta si la columna nueva deberia llenarse con ceros, con nulos o con algo calculado.
 *
 * Una migracion es exactamente eso: nosotros le decimos, en SQL, como pasar de la version
 * anterior a la nueva sin perder lo que el usuario ya tenia guardado.
 *
 * La alternativa facil seria `fallbackToDestructiveMigration()`, que le dice a Room
 * "si no cuadra, borra todo y empieza de cero". No se uso a proposito: en esta app la base
 * local es justamente lo que hace que funcione sin internet. Borrarla dejaria al usuario
 * con la pantalla vacia, y peor aun, borraria las inscripciones que todavia no se habian
 * mandado al servidor. Seria perder datos del usuario para ahorrarnos tres lineas.
 */

/**
 * Version 1 -> 2: agrega la columna `pendingSync` a la tabla de actividades.
 *
 * `pendingSync` marca las inscripciones hechas sin internet que el backend aun no conoce.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Tres detalles de esta linea, que son los que la hacen segura:
        //
        // - ALTER TABLE ADD COLUMN agrega la columna a la tabla que ya existe, sin tocar
        //   las filas guardadas. Nadie pierde sus actividades descargadas.
        //
        // - INTEGER y no BOOLEAN: SQLite no tiene tipo booleano. Room guarda los true/false
        //   como 1 y 0. Si escribieramos BOOLEAN aqui, la huella no coincidiria con la que
        //   Room espera y la app fallaria igual, que es justo lo que queriamos evitar.
        //
        // - DEFAULT 0 es obligatorio porque la columna es NOT NULL: las filas que ya estaban
        //   necesitan un valor. 0 significa false, o sea "no esta pendiente", que es lo
        //   correcto: todo lo que se habia guardado antes vino del servidor, asi que ya
        //   estaba sincronizado.
        db.execSQL("ALTER TABLE activities ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Version 2 -> 3: agrega las tablas `search_history` y `sync_log`.
 *
 * A diferencia de la migracion anterior, aqui no se toca ninguna tabla existente: solo se
 * crean dos nuevas. Eso la hace mas simple, pero no la hace innecesaria. Room compara la
 * forma completa de la base con la que espera el codigo, y una tabla que falta cuenta
 * igual que una columna que falta: la app no abriria.
 *
 * Por que el SQL esta escrito a mano y palabra por palabra:
 *
 * Room no ejecuta estas sentencias a ciegas. Despues de migrar, vuelve a leer el esquema
 * real del archivo y lo compara con el que deduce de las clases con @Entity. Si algo no
 * coincide (un tipo, un NOT NULL, el nombre de un indice) lanza un error en ese momento.
 * Por eso cada detalle de aqui esta copiado de lo que Room genera:
 *
 * - INTEGER PRIMARY KEY AUTOINCREMENT es lo que corresponde a @PrimaryKey(autoGenerate = true)
 *   sobre un Long. AUTOINCREMENT hace que SQLite nunca reutilice un id borrado, que es lo
 *   que se quiere en un historial: dos filas distintas no deben poder compartir id ni
 *   despues de una limpieza.
 *
 * - REAL para los Double y TEXT para los String, porque esos son los tipos que SQLite
 *   tiene de verdad. SQLite no tiene DOUBLE ni VARCHAR.
 *
 * - `httpCode` es la unica columna sin NOT NULL, porque en el codigo es `Int?`. Representa
 *   algo real: que no hubo respuesta del servidor.
 *
 * - `wasOffline` es INTEGER y no BOOLEAN por la misma razon que `pendingSync` en la
 *   migracion anterior: SQLite no tiene booleanos y Room guarda 1 y 0.
 *
 * - Los indices llevan el nombre `index_<tabla>_<columnas>`, que es el que Room les pone
 *   por defecto. Con otro nombre, el esquema no coincidiria aunque el indice funcionara.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // Historial de busquedas por cercania.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `userId` TEXT NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `radiusKm` REAL NOT NULL,
                `resultCount` INTEGER NOT NULL,
                `wasOffline` INTEGER NOT NULL,
                `searchedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // El indice es compuesto y en este orden porque las consultas filtran por usuario
        // y luego ordenan por fecha. Al reves no serviria: SQLite solo puede usar un
        // indice compuesto si la consulta empieza por su primera columna.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_search_history_userId_searchedAt` " +
                "ON `search_history` (`userId`, `searchedAt`)"
        )

        // Registro de intentos de sincronizacion.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `actionId` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `actionType` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `httpCode` INTEGER,
                `attemptedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Dos indices separados, y no uno compuesto, porque son dos consultas que no
        // comparten el primer campo: una busca el historial de una accion concreta y la
        // otra lista los intentos recientes por fecha.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_log_actionId` ON `sync_log` (`actionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_log_attemptedAt` ON `sync_log` (`attemptedAt`)"
        )
    }
}
