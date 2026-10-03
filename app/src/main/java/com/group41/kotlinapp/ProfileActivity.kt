package com.group41.kotlinapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.viewmodel.ProfileUiState
import com.group41.kotlinapp.viewmodel.ProfileViewModel
import com.group41.kotlinapp.viewmodel.ScheduleEvent
import com.group41.kotlinapp.viewmodel.ScheduleUiState
import com.group41.kotlinapp.viewmodel.ScheduleViewModel
import kotlinx.coroutines.launch
import android.content.Intent

class ProfileActivity : AppCompatActivity() {

    /** Compartido con EditProfileBottomSheet */
    private val viewModel: ProfileViewModel by viewModels()

    /** Último usuario dibujado: la foto solo se recarga si cambió */
    private var shownUser: UserDto? = null

    private lateinit var logoutButton: View

    private val scheduleViewModel: ScheduleViewModel by viewModels()

    private lateinit var googleCard: View

    /** Los dos scopes son obligatorios: con uno solo el backend responde 409 */
    private val googleScopes = listOf(
        Scope("https://www.googleapis.com/auth/calendar.events.readonly"),
        Scope("https://www.googleapis.com/auth/calendar.calendarlist.readonly")
    )

    /** Pantalla de consentimiento de Google, cuando hace falta mostrarla */
    private val authorizeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.d("GCal", "resultado del consentimiento: ${result.resultCode}")
        if (result.resultCode != RESULT_OK) {
            // TEMPORAL (depuración): si no lo cancelaste tú, la configuración en Google Cloud está mal
            toast("Google cerró el permiso (código ${result.resultCode})")
            return@registerForActivityResult
        }
        try {
            val authorization = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            sendAuthCode(authorization)
        } catch (e: ApiException) {
            toast("No se pudo conectar con Google")
        }
    }

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
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {

                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }

                R.id.nav_profile -> true

                else -> false
            }
        }

        findViewById<View>(R.id.btn_edit_profile).setOnClickListener {
            // Hasta que llegue el usuario no hay datos reales que editar
            if (viewModel.state.value.user == null) return@setOnClickListener
            EditProfileBottomSheet().show(supportFragmentManager, EditProfileBottomSheet.TAG)
        }

        logoutButton = findViewById(R.id.btn_logout)
        logoutButton.setOnClickListener { viewModel.logout() }

        googleCard = findViewById(R.id.card_google_calendar)
        googleCard.setOnClickListener { onGoogleCardClicked() }

        // Primer dibujo: quita el mockup, o muestra el usuario si ya estaba (tras rotar)
        shownUser = viewModel.state.value.user
        showUser(shownUser)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { scheduleViewModel.state.collect(::renderSchedule) }
                launch { scheduleViewModel.events.collect(::onScheduleEvent) }
            }
        }

        // No repite la llamada si el ViewModel ya tiene el usuario (ej. tras rotar)
        viewModel.loadUser()
        if (scheduleViewModel.state.value.googleConnected) scheduleViewModel.loadFreeSlots()
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

    // ------------------------------------------------------------------ Google Calendar

    private fun onGoogleCardClicked() {
        val state = scheduleViewModel.state.value
        Log.d("GCal", "tap en la tarjeta: $state")
        // TEMPORAL (depuración): confirma que el toque llega
        toast(if (state.googleConnected) "Re-sincronizando…" else "Abriendo Google…")
        if (state.syncing) return
        // Ya conectado: el backend re-sincroniza solo, sin pantallas de Google
        if (state.googleConnected) scheduleViewModel.resync() else requestGoogleCalendar()
    }

    /** Pide a Google permiso offline y un authCode que el backend canjea por tokens */
    private fun requestGoogleCalendar() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(googleScopes)
            // true: el código siempre sirve para obtener refresh token, también al reconectar
            .requestOfflineAccess(BuildConfig.GOOGLE_SERVER_CLIENT_ID, true)
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pending = authorization.pendingIntent ?: return@addOnSuccessListener
                    authorizeLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    sendAuthCode(authorization)
                }
            }
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                Log.e("GCal", "authorize falló (código $code)", e)
                toast("No se pudo conectar con Google (código $code)")
            }
    }

    private fun sendAuthCode(authorization: AuthorizationResult) {
        val code = authorization.serverAuthCode
        Log.d("GCal", "serverAuthCode recibido: ${code != null}")
        if (code == null) {
            toast("Google no entregó el permiso. Inténtalo de nuevo")
            return
        }
        scheduleViewModel.onAuthCode(code)
    }

    private fun renderSchedule(state: ScheduleUiState) {
        if (state.sessionExpired) {
            openLogin("Tu sesión expiró. Inicia sesión de nuevo")
            return
        }

        googleCard.isEnabled = !state.syncing
        findViewById<ProgressBar>(R.id.progress_google).visibility =
            if (state.syncing) View.VISIBLE else View.GONE
        // INVISIBLE y no GONE: el título está anclado a la flecha
        findViewById<View>(R.id.tv_google_chevron).visibility =
            if (state.syncing) View.INVISIBLE else View.VISIBLE

        showGoogleConnected(state.googleConnected)

        findViewById<TextView>(R.id.tv_google_title).text =
            if (state.googleConnected) "Google Calendar conectado" else "Sincronizar Google Calendar"
        findViewById<TextView>(R.id.tv_google_subtitle).text = when {
            state.syncing -> "Importando tus eventos…"
            state.googleConnected -> "Toca para sincronizar de nuevo"
            else -> "Importa tus eventos en modo solo lectura"
        }

        state.freeSlots?.let(::showFreeSlots)
    }

    /** Azul con "G" si falta conectar; verde con ✓ y ↻ cuando ya está conectado */
    private fun showGoogleConnected(connected: Boolean) {
        // setBackgroundResource puede reiniciar el padding: se guarda y se repone
        val l = googleCard.paddingLeft
        val t = googleCard.paddingTop
        val r = googleCard.paddingRight
        val b = googleCard.paddingBottom
        googleCard.setBackgroundResource(
            if (connected) R.drawable.bg_card_green else R.drawable.bg_card_blue
        )
        googleCard.setPadding(l, t, r, b)

        val accent = ContextCompat.getColor(this, if (connected) R.color.green else R.color.blue)
        findViewById<TextView>(R.id.tv_google_logo).apply {
            text = if (connected) "✓" else "G"
            setTextColor(accent)
        }
        findViewById<TextView>(R.id.tv_google_chevron).apply {
            text = if (connected) "↻" else "›"
            setTextColor(accent)
        }
    }

    private fun showFreeSlots(slots: List<FreeSlotDto>) {
        val count = slots.size
        findViewById<TextView>(R.id.tv_badge_free).text =
            "●  $count ${if (count == 1) "ventana libre" else "ventanas libres"}"

        findViewById<TextView>(R.id.tv_free_windows_time).text =
            if (slots.isEmpty()) "Sin ventanas libres hoy"
            else slots.joinToString("\n") { "${hhmm(it.start)} – ${hhmm(it.end)}" }

        val minutes = slots.sumOf { minutesOfDay(it.end) - minutesOfDay(it.start) }
        findViewById<TextView>(R.id.tv_free_windows_minutes).text = "$minutes min"
    }

    /**
     * "2026-10-05T08:15:00-05:00" -> "08:15". El backend ya formatea en la zona
     * que le mandamos en `tz`, así que no hace falta convertir nada.
     */
    private fun hhmm(iso: String) = iso.substring(11, 16)

    private fun minutesOfDay(iso: String) =
        iso.substring(11, 13).toInt() * 60 + iso.substring(14, 16).toInt()

    private fun onScheduleEvent(event: ScheduleEvent) {
        when (event) {
            is ScheduleEvent.Message -> toast(event.text)
            // El token guardado ya no sirve: se pide el permiso de nuevo
            ScheduleEvent.NeedsConsent -> requestGoogleCalendar()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}