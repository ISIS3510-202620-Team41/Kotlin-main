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
