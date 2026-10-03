package com.group41.kotlinapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.viewmodel.ProfileUiState
import com.group41.kotlinapp.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    /** Compartido con EditProfileBottomSheet */
    private val viewModel: ProfileViewModel by viewModels()

    /** Último usuario dibujado: la foto solo se recarga si cambió */
    private var shownUser: UserDto? = null

    private lateinit var logoutButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sin sesión no hay perfil que mostrar
        if (!viewModel.hasSession()) {
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
            if (viewModel.state.value.user == null) return@setOnClickListener
            EditProfileBottomSheet().show(supportFragmentManager, EditProfileBottomSheet.TAG)
        }

        logoutButton = findViewById(R.id.btn_logout)
        logoutButton.setOnClickListener { viewModel.logout() }

        // Primer dibujo: quita el mockup, o muestra el usuario si ya estaba (tras rotar)
        shownUser = viewModel.state.value.user
        showUser(shownUser)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        // No repite la llamada si el ViewModel ya tiene el usuario (ej. tras rotar)
        viewModel.loadUser()
    }

    /** Recién concedido el permiso de red local: ahora sí se puede cargar el perfil */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadUser(force = true)
        }
    }

    override fun onResume() {
        super.onResume()
        Analytics.screenView("Profile")
    }

    private fun render(state: ProfileUiState) {
        when {
            state.loggedOut -> {
                openLogin()
                return
            }
            state.sessionExpired -> {
                openLogin("Tu sesión expiró. Inicia sesión de nuevo")
                return
            }
        }

        logoutButton.isEnabled = !state.loggingOut

        if (state.user != shownUser) {
            shownUser = state.user
            showUser(state.user)
        }
    }

    private fun showUser(user: UserDto?) {
        val name = findViewById<TextView>(R.id.tv_name)
        val bio = findViewById<TextView>(R.id.tv_bio)
        val initials = findViewById<TextView>(R.id.tv_avatar)
        if (user == null) {
            // Sin los datos de ejemplo del mockup mientras llega el usuario real
            name.text = ""
            bio.text = ""
            initials.text = ""
            return
        }
        name.text = user.name
        // Sin GONE: el avatar está anclado a tv_bio en el layout
        bio.text = user.bio.orEmpty()
        bindAvatar(findViewById(R.id.iv_avatar), initials, user)
    }
}
