package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.CreateActivityRequest
import com.group41.kotlinapp.repository.PendingActionRepository
import com.group41.kotlinapp.sync.SyncScheduler
import kotlinx.coroutines.launch

/**
 * Activity managing Step 3 of the Activity Creation process (Location Selection & Offline Queueing).
 */
class CreateActivityStep3Activity : AppCompatActivity() {

    private var selectedLocationId: Int? = null

    private val pendingActionRepository by lazy {
        PendingActionRepository(
            pendingActionDao = (application as App).database.pendingActionDao()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        setContentView(R.layout.activity_create_activity_step3)

        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (savedInstanceState != null) {
            val savedId = savedInstanceState.getInt(KEY_SAVED_LOCATION_ID, -1)
            if (savedId != -1) {
                selectedLocationId = savedId
            }
        }

        setupLocationCards()
        setupNavigationButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedLocationId?.let { id ->
            outState.putInt(KEY_SAVED_LOCATION_ID, id)
        }
    }

    private fun setupLocationCards() {
        val cardBistro = findViewById<ConstraintLayout>(R.id.card_bistro)
        val rbBistro = findViewById<RadioButton>(R.id.rb_bistro)
        val cardPizzaHut = findViewById<ConstraintLayout>(R.id.card_pizza_hut)
        val rbPizzaHut = findViewById<RadioButton>(R.id.rb_pizza_hut)

        val locationCards = listOf(
            Triple(R.id.card_bistro, cardBistro, rbBistro),
            Triple(R.id.card_pizza_hut, cardPizzaHut, rbPizzaHut)
        )

        fun updateVisuals() {
            locationCards.forEach { (id, card, radioButton) ->
                if (id == selectedLocationId) {
                    card.setBackgroundResource(R.drawable.bg_card_selected_outline)
                    radioButton.isChecked = true
                } else {
                    card.setBackgroundResource(R.drawable.bg_card_white)
                    radioButton.isChecked = false
                }
            }
        }

        if (selectedLocationId != null) {
            updateVisuals()
        }

        locationCards.forEach { (id, card, radioButton) ->
            val selectClickListener = View.OnClickListener {
                selectedLocationId = id
                updateVisuals()
            }
            card.setOnClickListener(selectClickListener)
            radioButton.setOnClickListener(selectClickListener)
        }
    }

    private fun setupNavigationButtons() {
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnPrevious = findViewById<MaterialButton>(R.id.btn_previous)
        val btnConfirm = findViewById<MaterialButton>(R.id.btn_confirm)

        val backClickListener = View.OnClickListener { finish() }
        btnBack.setOnClickListener(backClickListener)
        btnPrevious.setOnClickListener(backClickListener)

        btnConfirm.setOnClickListener {
            if (selectedLocationId == null) {
                Toast.makeText(this, "Selecciona un lugar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val userId = ApiClient.tokens.userId
            if (userId.isNullOrBlank()) {
                Toast.makeText(this, "Usuario no autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val title = intent.getStringExtra("EXTRA_TITLE") ?: "Nueva Actividad"
            val category = intent.getStringExtra(CreateActivityStep2Activity.EXTRA_SELECTED_CATEGORY) ?: "ENTRETENIMIENTO"
            val startTime = intent.getStringExtra("EXTRA_START_TIME") ?: "2026-11-15T18:00:00Z"
            val endTime = intent.getStringExtra("EXTRA_END_TIME") ?: "2026-11-15T20:00:00Z"
            val description = intent.getStringExtra("EXTRA_DESCRIPTION")
            val price = intent.getStringExtra("EXTRA_PRICE") ?: "Gratis"
            val capacity = intent.getIntExtra("EXTRA_CAPACITY", 10)

            val (locationName, latitude, longitude) = when (selectedLocationId) {
                R.id.card_bistro -> Triple("Bistro", 4.6010, -74.0650)
                R.id.card_pizza_hut -> Triple("Pizza Hut", 4.6030, -74.0670)
                else -> Triple("Lugar Seleccionado", 4.6000, -74.0600)
            }

            val request = CreateActivityRequest(
                title = title,
                category = category,
                startTime = startTime,
                endTime = endTime,
                locationName = locationName,
                latitude = latitude,
                longitude = longitude,
                description = description,
                price = price,
                capacity = capacity
            )

            lifecycleScope.launch {
                try {
                    // Queue creation task in Room database
                    pendingActionRepository.queueActivityCreation(userId, request)

                    // Schedule WorkManager synchronization task
                    SyncScheduler.schedule(applicationContext)

                    // User feedback toast
                    Toast.makeText(
                        this@CreateActivityStep3Activity,
                        "Actividad guardada localmente. Se publicará automáticamente al conectarse",
                        Toast.LENGTH_LONG
                    ).show()

                    // Navigate back to main feed and finish step activities
                    val intent = Intent(this@CreateActivityStep3Activity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()

                } catch (e: Exception) {
                    Toast.makeText(
                        this@CreateActivityStep3Activity,
                        "Error al guardar la actividad: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val KEY_SAVED_LOCATION_ID = "SAVED_LOCATION_ID"
    }
}