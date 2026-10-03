package com.group41.kotlinapp

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Abre la app y borra el login del historial (el botón atrás no vuelve al login) */
fun Activity.openHome() {
    // Por ahora el perfil. Cuando entre #14, cambiar a MainActivity
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
}

/**
 * Android 17 (API 37) exige ACCESS_LOCAL_NETWORK para conectarse a IPs de la red
 * local, y el backend de desarrollo vive ahí (10.0.2.2 o la IP del PC). Sin él,
 * toda petición falla con "No se pudo conectar con el servidor".
 */
fun Activity.requestLocalNetworkIfNeeded() {
    if (Build.VERSION.SDK_INT < 37) return
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), 0)
    }
}

/** Vuelve al login borrando el historial (tras cerrar sesión o si la sesión venció) */
fun Activity.openLogin(message: String? = null) {
    message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    startActivity(
        Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
}
