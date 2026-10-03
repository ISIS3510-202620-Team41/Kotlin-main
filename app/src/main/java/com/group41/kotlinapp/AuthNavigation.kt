package com.group41.kotlinapp

import android.app.Activity
import android.content.Intent

/** Abre la app y borra el login del historial (el botón atrás no vuelve al login) */
fun Activity.openHome() {
    // Por ahora el perfil. Cuando entre #14, cambiar a MainActivity
    startActivity(
        Intent(this, ProfileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
}
