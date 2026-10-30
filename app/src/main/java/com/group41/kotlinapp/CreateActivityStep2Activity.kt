package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class CreateActivityStep2Activity : AppCompatActivity() {

    private var selectedCategory: String? = null
    private var selectedCardId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        setContentView(R.layout.activity_create_activity_step2)

        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Restore selections from savedInstanceState first, falling back to Intent extras
        if (savedInstanceState != null) {
            selectedCategory = savedInstanceState.getString(EXTRA_SELECTED_CATEGORY)
            val savedCardId = savedInstanceState.getInt(KEY_SAVED_CARD_ID, -1)
            if (savedCardId != -1) {
                selectedCardId = savedCardId
            }
        } else {
            selectedCategory = intent.getStringExtra(EXTRA_SELECTED_CATEGORY)
            val restoredCardId = intent.getIntExtra(EXTRA_SELECTED_CARD_ID, -1)
            if (restoredCardId != -1) {
                selectedCardId = restoredCardId
            }
        }

        setupCategoryCards()
        setupNavigationButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedCategory?.let { outState.putString(EXTRA_SELECTED_CATEGORY, it) }
        selectedCardId?.let { outState.putInt(KEY_SAVED_CARD_ID, it) }
    }

    private fun setupCategoryCards() {
        // Map card IDs to layouts and canonical backend category values
        val categoryCards = listOf(
            Triple(R.id.card_comida, findViewById<LinearLayout>(R.id.card_comida), "ENTRETENIMIENTO"),
            Triple(R.id.card_juego, findViewById<LinearLayout>(R.id.card_juego), "ENTRETENIMIENTO"),
            Triple(R.id.card_musica, findViewById<LinearLayout>(R.id.card_musica), "ENTRETENIMIENTO"),
            Triple(R.id.card_estudio, findViewById<LinearLayout>(R.id.card_estudio), "ESTUDIO"),
            Triple(R.id.card_manualidades, findViewById<LinearLayout>(R.id.card_manualidades), "CULTURA"),
            Triple(R.id.card_deporte, findViewById<LinearLayout>(R.id.card_deporte), "DEPORTES"),
            Triple(R.id.card_cultural, findViewById<LinearLayout>(R.id.card_cultural), "CULTURA"),
            Triple(R.id.card_descanso, findViewById<LinearLayout>(R.id.card_descanso), "ENTRETENIMIENTO")
        )

        // Fallback: if selectedCardId wasn't restored, find the first matching category
        if (selectedCategory != null && selectedCardId == null) {
            selectedCardId = categoryCards.firstOrNull { it.third == selectedCategory }?.first
        }

        fun updateCardVisuals() {
            categoryCards.forEach { (id, card, categoryValue) ->
                val textView = card.getChildAt(1) as? TextView
                val isSelected = if (selectedCardId != null) id == selectedCardId else categoryValue == selectedCategory

                if (isSelected) {
                    card.setBackgroundResource(R.drawable.bg_card_selected)
                    textView?.setTextColor(ContextCompat.getColor(this, R.color.white))
                } else {
                    card.setBackgroundResource(R.drawable.bg_card_white)
                    textView?.setTextColor(ContextCompat.getColor(this, R.color.black))
                }
            }
        }

        if (selectedCategory != null || selectedCardId != null) {
            updateCardVisuals()
        }

        categoryCards.forEach { (id, cardLayout, categoryValue) ->
            cardLayout.setOnClickListener {
                selectedCategory = categoryValue
                selectedCardId = id
                updateCardVisuals()
            }
        }
    }

    private fun setupNavigationButtons() {
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnPrevious = findViewById<MaterialButton>(R.id.btn_previous)
        val btnNext = findViewById<MaterialButton>(R.id.btn_next)

        // Return to Step 1 preserving existing creation data
        val backClickListener = View.OnClickListener { finish() }
        btnBack.setOnClickListener(backClickListener)
        btnPrevious.setOnClickListener(backClickListener)

        btnNext.setOnClickListener {
            if (selectedCategory.isNullOrBlank()) {
                Toast.makeText(this, "Selecciona una categoría", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Navigate to Step 3, explicitly referencing incoming activity intent extras
            val intent = Intent(this, CreateActivityStep3Activity::class.java).apply {
                this@CreateActivityStep2Activity.intent.extras?.let { putExtras(it) }
                putExtra(EXTRA_SELECTED_CATEGORY, selectedCategory)
                selectedCardId?.let { putExtra(EXTRA_SELECTED_CARD_ID, it) }
            }
            startActivity(intent)
        }
    }

    companion object {
        const val EXTRA_SELECTED_CATEGORY = "SELECTED_CATEGORY"
        const val EXTRA_SELECTED_CARD_ID = "SELECTED_CARD_ID"
        private const val KEY_SAVED_CARD_ID = "SAVED_CARD_ID"
    }
}