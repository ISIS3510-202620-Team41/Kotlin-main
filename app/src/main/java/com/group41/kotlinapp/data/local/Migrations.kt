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

/** Adds the enrollment table and backfills pending joins without changing existing rows. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_enrollments (
                userId TEXT NOT NULL,
                activityId TEXT NOT NULL,
                joined INTEGER NOT NULL,
                cancellationPending INTEGER NOT NULL,
                confirmedJoined INTEGER NOT NULL,
                PRIMARY KEY (userId, activityId)
            )
            """.trimIndent()
        )
        // Sprint 3 stored pending joins under JOIN_ACTIVITY:userId:activityId.
        // The queue ID identifies the account. Cached activities did not store an account ID,
        // so existing confirmed memberships are recovered on the next server refresh.
        db.execSQL(
            """
            INSERT OR IGNORE INTO user_enrollments
                (userId, activityId, joined, cancellationPending, confirmedJoined)
            SELECT userId, substr(id, length('JOIN_ACTIVITY:' || userId || ':') + 1), 1, 0, 0
            FROM pending_actions
            WHERE actionType = 'JOIN_ACTIVITY'
              AND substr(id, 1, length('JOIN_ACTIVITY:' || userId || ':')) =
                  'JOIN_ACTIVITY:' || userId || ':'
              AND length(id) > length('JOIN_ACTIVITY:' || userId || ':')
            """.trimIndent()
        )
    }
}
/** Adds enrollment history timestamps. Version 3 did not record when changes took place. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_enrollments ADD COLUMN enrolledAt INTEGER")
        db.execSQL("ALTER TABLE user_enrollments ADD COLUMN canceledAt INTEGER")
    }
}
