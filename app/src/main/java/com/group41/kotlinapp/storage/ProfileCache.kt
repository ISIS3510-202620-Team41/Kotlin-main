package com.group41.kotlinapp.storage

import android.content.Context
import com.group41.kotlinapp.network.UserDto

/**
 * El ultimo perfil que el servidor devolvio, guardado en el telefono.
 *
 * Para que sirve: sin esto, abrir el perfil sin internet deja la pantalla en blanco. El
 * nombre, el correo y la descripcion del usuario no cambian casi nunca, asi que pedirlos
 * al servidor cada vez para no poder mostrarlos cuando no hay red es lo peor de las dos
 * opciones. Guardando una copia, el perfil se ve siempre.
 *
 * Por que SharedPreferences y no la base de datos: son cinco campos de un solo usuario.
 * No hay nada que consultar, filtrar ni relacionar, que es para lo que sirve una tabla.
 */
object ProfileCache {

    private const val PREFS_NAME = "profile_cache"

    private const val KEY_ID = "id"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_BIO = "bio"
    private const val KEY_AVATAR_URL = "avatarUrl"

    /** Guarda el perfil que acaba de llegar del servidor. */
    fun save(context: Context, user: UserDto) {
        prefs(context).edit()
            .putString(KEY_ID, user.id)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_NAME, user.name)
            .putString(KEY_BIO, user.bio)
            .putString(KEY_AVATAR_URL, user.avatarUrl)
            // apply() y no commit(): escribe en memoria ya y el disco va en segundo plano.
            // Nadie espera por esto, y la siguiente lectura ve el valor nuevo igual.
            .apply()
    }

    /**
     * El perfil guardado, o null si nunca se guardo ninguno.
     *
     * Se exige que el id este presente para devolver algo. Sin id no es un perfil: seria
     * un objeto a medias que la pantalla mostraria como si fuera real.
     */
    fun load(context: Context): UserDto? {
        val stored = prefs(context)
        val id = stored.getString(KEY_ID, null) ?: return null

        return UserDto(
            id = id,
            email = stored.getString(KEY_EMAIL, "").orEmpty(),
            name = stored.getString(KEY_NAME, "").orEmpty(),
            bio = stored.getString(KEY_BIO, null),
            avatarUrl = stored.getString(KEY_AVATAR_URL, null)
        )
    }

    /**
     * Borra la copia. Hay que llamarlo al cerrar sesion.
     *
     * Si no se borrara, el siguiente usuario que entrara en este telefono veria por un
     * instante el nombre del anterior.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
