package com.group41.kotlinapp.viewmodel

/**
 * Mismas reglas que los DTOs del backend (AuthDtos), para avisar en el campo
 * antes de llamar al servidor. Devuelven el mensaje de error, o null si está bien.
 *
 * Kotlin puro (sin android.util.Patterns) para que los tests de los ViewModel
 * corran en la JVM sin emulador.
 */
internal object Validation {

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun email(value: String) =
        if (EMAIL.matches(value)) null else "Escribe un correo válido"

    fun name(value: String) = when {
        value.isBlank() -> "Escribe tu nombre"
        value.length > 80 -> "Máximo 80 caracteres"
        else -> null
    }

    fun newPassword(value: String) =
        if (value.length in 8..72) null else "Debe tener entre 8 y 72 caracteres"

    fun bio(value: String) =
        if (value.length <= 300) null else "Máximo 300 caracteres"
}
