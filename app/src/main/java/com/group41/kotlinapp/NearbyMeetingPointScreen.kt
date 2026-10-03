package com.group41.kotlinapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.viewmodel.NearbyActivitiesViewModel
import androidx.compose.material3.ButtonDefaults
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaBlack

@Composable
fun NearbyMeetingPointScreen(
    viewModel: NearbyActivitiesViewModel
) {
    var latitudeText by remember {
        mutableStateOf("4.6382")
    }

    var longitudeText by remember {
        mutableStateOf("-74.0840")
    }

    var radiusText by remember {
        mutableStateOf("1.0")
    }

    var activities by remember {
        mutableStateOf(emptyList<Activity>())
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Actividades cerca del punto",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ingresa un punto de encuentro para encontrar actividades cercanas.",
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = latitudeText,
                onValueChange = { latitudeText = it },
                label = { Text("Latitud") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = longitudeText,
                onValueChange = { longitudeText = it },
                label = { Text("Longitud") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = radiusText,
                onValueChange = { radiusText = it },
                label = { Text("Radio (km)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    val latitude = latitudeText.toDoubleOrNull()
                    val longitude = longitudeText.toDoubleOrNull()
                    val radius = radiusText.toDoubleOrNull()

                    if (
                        latitude == null ||
                        longitude == null ||
                        radius == null
                    ) {
                        errorMessage = "Ingresa coordenadas y radio válidos."
                    } else {
                        isLoading = true
                        errorMessage = null

                        viewModel.loadNearbyActivities(
                            latitude = latitude,
                            longitude = longitude,
                            radiusKm = radius,

                            onSuccess = { result ->
                                activities = result
                                isLoading = false
                            },

                            onError = { error ->
                                errorMessage = error
                                isLoading = false
                            }
                        )
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LlamallaBlue,
                    contentColor = LlamallaBlack
                )
            ) {
                Text(
                    if (isLoading) {
                        "Buscando..."
                    } else {
                        "Buscar actividades cercanas"
                    }
                )
            }

            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        items(activities) { activity ->
            NearbyActivityCard(activity)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun NearbyActivityCard(
    activity: Activity
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFDDF4F8)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = activity.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = activity.category,
                color = Color(0xFFD50057),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${activity.durationMinutes} min · ${activity.distanceMeters} m",
                color = Color.DarkGray
            )

            if (activity.joined) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Ya estás unido",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}