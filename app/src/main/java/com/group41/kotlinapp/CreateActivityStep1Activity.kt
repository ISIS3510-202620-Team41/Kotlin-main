package com.group41.kotlinapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Activity managing Step 1 of the Activity Creation process (Details & Participants).
 *
 * Collects activity title, start/end dates and times via interactive date/time pickers,
 * and an optional description. Validates that required fields are non-blank, start time is
 * in the future, and end time occurs after start time before navigating to Step 2.
 * Preserves selected date timestamps across configuration changes via savedInstanceState.
 */
class CreateActivityStep1Activity : AppCompatActivity() {

    private var startTimeMillis: Long? = null
    private var endTimeMillis: Long? = null

    // Display format for human-readable input fields
    private val displayFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    // ISO-8601 formatter incorporating local timezone offset (e.g. 2026-11-15T18:00:00-05:00)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        setContentView(R.layout.activity_create_activity_step1)

        val root = findViewById<View>(R.id.main)
        val buttonsContainer = findViewById<View>(R.id.ll_buttons)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            buttonsContainer.setPadding(
                buttonsContainer.paddingLeft,
                buttonsContainer.paddingTop,
                buttonsContainer.paddingRight,
                systemBars.bottom
            )
            insets
        }

        // Restore selected date timestamps if recreated across configuration changes
        if (savedInstanceState != null) {
            val savedStart = savedInstanceState.getLong(KEY_START_TIME_MILLIS, -1L)
            if (savedStart != -1L) {
                startTimeMillis = savedStart
            }
            val savedEnd = savedInstanceState.getLong(KEY_END_TIME_MILLIS, -1L)
            if (savedEnd != -1L) {
                endTimeMillis = savedEnd
            }
        }

        setupDatePickers()
        setupNavigationButtons()
        restoreDisplayedDates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        startTimeMillis?.let { outState.putLong(KEY_START_TIME_MILLIS, it) }
        endTimeMillis?.let { outState.putLong(KEY_END_TIME_MILLIS, it) }
    }

    /**
     * Synchronizes display text fields with restored timestamp values.
     */
    private fun restoreDisplayedDates() {
        val etStartTime = findViewById<EditText>(R.id.et_start_time)
        val etEndTime = findViewById<EditText>(R.id.et_end_time)

        startTimeMillis?.let {
            etStartTime.setText(displayFormatter.format(Date(it)))
        }
        endTimeMillis?.let {
            etEndTime.setText(displayFormatter.format(Date(it)))
        }
    }

    /**
     * Attaches DatePickerDialog and TimePickerDialog flow to start and end date input fields.
     */
    private fun setupDatePickers() {
        val etStartTime = findViewById<EditText>(R.id.et_start_time)
        val etEndTime = findViewById<EditText>(R.id.et_end_time)

        etStartTime.setOnClickListener {
            showDateTimePicker(startTimeMillis) { calendar ->
                startTimeMillis = calendar.timeInMillis
                etStartTime.setText(displayFormatter.format(calendar.time))
            }
        }

        etEndTime.setOnClickListener {
            showDateTimePicker(endTimeMillis) { calendar ->
                endTimeMillis = calendar.timeInMillis
                etEndTime.setText(displayFormatter.format(calendar.time))
            }
        }
    }

    /**
     * Sequential DatePicker followed by TimePicker dialog launcher.
     */
    private fun showDateTimePicker(
        initialTimeMillis: Long?,
        onDateTimeSelected: (Calendar) -> Unit
    ) {
        val calendar = Calendar.getInstance()
        initialTimeMillis?.let {
            calendar.timeInMillis = it
        }

        val datePickerDialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                // Launch TimePicker immediately after DatePicker selection
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)

                        onDateTimeSelected(calendar)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    /**
     * Binds back, cancel, and next navigation controls with validation checks.
     */
    private fun setupNavigationButtons() {
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnCancel = findViewById<MaterialButton>(R.id.btn_cancel)
        val btnNext = findViewById<MaterialButton>(R.id.btn_next)

        val etTitle = findViewById<EditText>(R.id.et_activity_title)
        val etDescription = findViewById<EditText>(R.id.et_activity_description)

        val cancelListener = View.OnClickListener { finish() }
        btnBack.setOnClickListener(cancelListener)
        btnCancel.setOnClickListener(cancelListener)

        btnNext.setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isBlank()) {
                Toast.makeText(this, "Ingresa un título para la actividad", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val startMillis = startTimeMillis
            if (startMillis == null) {
                Toast.makeText(this, "Selecciona la fecha y hora de inicio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val endMillis = endTimeMillis
            if (endMillis == null) {
                Toast.makeText(this, "Selecciona la fecha y hora de fin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Reject start times in the past
            val now = System.currentTimeMillis()
            if (startMillis < now) {
                Toast.makeText(this, "La hora de inicio no puede estar en el pasado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Require end time strictly after start time
            if (endMillis <= startMillis) {
                Toast.makeText(this, "La hora de fin debe ser posterior a la hora de inicio", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Format date values to ISO-8601 with local timezone offset
            val startTimeIso = isoFormatter.format(Date(startMillis))
            val endTimeIso = isoFormatter.format(Date(endMillis))
            val description = etDescription.text.toString().trim()

            // Pass form values to Step 2 via Intent extras
            val intent = Intent(this, CreateActivityStep2Activity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_START_TIME, startTimeIso)
                putExtra(EXTRA_END_TIME, endTimeIso)
                if (description.isNotBlank()) {
                    putExtra(EXTRA_DESCRIPTION, description)
                }
            }
            startActivity(intent)
        }
    }

    companion object {
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_START_TIME = "EXTRA_START_TIME"
        const val EXTRA_END_TIME = "EXTRA_END_TIME"
        const val EXTRA_DESCRIPTION = "EXTRA_DESCRIPTION"

        private const val KEY_START_TIME_MILLIS = "KEY_START_TIME_MILLIS"
        private const val KEY_END_TIME_MILLIS = "KEY_END_TIME_MILLIS"
    }
}