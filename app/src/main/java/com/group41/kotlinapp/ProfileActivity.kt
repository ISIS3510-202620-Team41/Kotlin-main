package com.group41.kotlinapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.network.isUnauthorized
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    /** Usuario real cargado de /api/users/me; null mientras llega */
    private var user: UserDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sin sesión no hay perfil que mostrar
        if (ApiClient.tokens.refresh == null) {
            openLogin()
            return
        }

        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        setContentView(R.layout.activity_profile)
        requestLocalNetworkIfNeeded()

        val root = findViewById<View>(R.id.main)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        bottomNav.selectedItemId = R.id.nav_profile

        findViewById<View>(R.id.btn_edit_profile).setOnClickListener {
            // Hasta que llegue el usuario no hay datos reales que editar
            val current = user ?: return@setOnClickListener
            EditProfileBottomSheet.newInstance(current)
                .show(supportFragmentManager, EditProfileBottomSheet.TAG)
        }

        // Foto, nombre o descripción cambiados desde "Editar perfil"
        supportFragmentManager.setFragmentResultListener(
            EditProfileBottomSheet.RESULT_KEY, this
        ) { _, result -> showUser(EditProfileBottomSheet.userFrom(result)) }

        findViewById<View>(R.id.btn_logout).setOnClickListener { button ->
            button.isEnabled = false
            lifecycleScope.launch {
                ApiClient.logout()
                openLogin()
            }
        }

        // Sin los datos de ejemplo del mockup mientras llega el usuario real
        findViewById<TextView>(R.id.tv_name).text = ""
        findViewById<TextView>(R.id.tv_bio).text = ""
        findViewById<TextView>(R.id.tv_avatar).text = ""
        loadUser()
    }

    /** Recién concedido el permiso de red local: ahora sí se puede cargar el perfil */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadUser()
    }

    override fun onResume() {
        super.onResume()
        Analytics.screenView("Profile")
    }

    private fun loadUser() {
        lifecycleScope.launch {
            try {
                val user = ApiClient.api.me()
                // Sesiones guardadas antes de que existiera userId en TokenStore
                ApiClient.tokens.userId = user.id
                Analytics.setUserId(user.id)
                showUser(user)
            } catch (e: Exception) {
                // 401 aquí = el authenticator no pudo refrescar: la sesión venció.
                // Otros errores (sin red) dejan la pantalla como está.
                if (e.isUnauthorized() || ApiClient.tokens.refresh == null) {
                    ApiClient.tokens.clear()
                    openLogin("Tu sesión expiró. Inicia sesión de nuevo")
                }
            }
        }
    }

    private fun showUser(user: UserDto) {
        this.user = user
        findViewById<TextView>(R.id.tv_name).text = user.name
        // Sin GONE: el avatar está anclado a tv_bio en el layout
        findViewById<TextView>(R.id.tv_bio).text = user.bio.orEmpty()
        bindAvatar(findViewById(R.id.iv_avatar), findViewById(R.id.tv_avatar), user)
    }
}
