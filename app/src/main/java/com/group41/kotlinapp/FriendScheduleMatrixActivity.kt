package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.FriendScheduleViewModel

class FriendScheduleMatrixActivity : ComponentActivity() {

    private val viewModel: FriendScheduleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KotlinAppTheme {
                FriendScheduleMatrixScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    onCreatePlanClick = { gap ->
                        val intent = Intent(this, CreateActivityStep1Activity::class.java).apply {
                            putExtra("EXTRA_START_TIME", gap.interval.formattedRange())
                            putExtra("EXTRA_SUGGESTED_DURATION", gap.durationMinutes)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}
