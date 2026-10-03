package com.group41.kotlinapp.network

import com.google.gson.Gson
import retrofit2.HttpException
import java.io.IOException

fun Throwable.isUnauthorized() = this is HttpException && code() == 401

/** Convierte un error de red en un mensaje para mostrar al usuario */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val error = response()?.errorBody()?.string()?.let {
            runCatching { Gson().fromJson(it, ApiError::class.java) }.getOrNull()
        }
        error?.fields?.values?.firstOrNull() ?: error?.message ?: "Error ${code()}"
    }
    is IOException -> "No se pudo conectar con el servidor"
    else -> message ?: "Error inesperado"
}
