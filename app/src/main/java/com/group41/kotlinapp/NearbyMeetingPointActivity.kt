package com.group41.kotlinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.NearbyActivitiesViewModel

class NearbyMeetingPointActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nearbyActivitiesViewModel =
            ViewModelProvider(this)[NearbyActivitiesViewModel::class.java]

        // Temporary until Sebas's authentication flow is integrated.
        val testAccessToken = ""

        enableEdgeToEdge()

        setContent {
            KotlinAppTheme {
                NearbyMeetingPointScreen(
                    viewModel = nearbyActivitiesViewModel,
                    accessToken = testAccessToken
                )
            }
        }
    }
}