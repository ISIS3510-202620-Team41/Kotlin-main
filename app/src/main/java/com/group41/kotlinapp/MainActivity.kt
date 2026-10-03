package com.group41.kotlinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.RecommendationsViewModel
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import com.group41.kotlinapp.ui.theme.LlamallaBlack

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recommendationsViewModel =
            ViewModelProvider(this)[RecommendationsViewModel::class.java]
        val testAccessToken = ""

        enableEdgeToEdge()

        setContent {
            KotlinAppTheme {
                RecommendationScreen(
                    viewModel = recommendationsViewModel,
                    accessToken = testAccessToken
                )
            }
        }
    }
}

@Composable
fun RecommendationScreen(
    viewModel: RecommendationsViewModel,
    accessToken: String
) {

    val context = LocalContext.current

    var selectedCategories by remember {
        mutableStateOf(setOf<String>())
    }

    var recommendations by remember {
        mutableStateOf(emptyList<Activity>())
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    val categories = listOf(
        "Comida",
        "Juego",
        "Música",
        "Estudio",
        "Manualidades",
        "Deporte",
        "Cultural",
        "Descanso"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Llamalla",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(
                            color = LlamallaBlue,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Tus preferencias",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Selecciona las actividades que más te interesan",
                    fontSize = 16.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))
            }

            item {
                categories.chunked(2).forEach { rowCategories ->

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        rowCategories.forEach { category ->

                            FilterChip(
                                selected = category in selectedCategories,

                                onClick = {
                                    selectedCategories =
                                        if (category in selectedCategories) {
                                            selectedCategories - category
                                        } else {
                                            selectedCategories + category
                                        }
                                },

                                label = {
                                    Text(category)
                                },

                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (rowCategories.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            item {
                Button(
                    onClick = {
                        viewModel.updatePreferences(selectedCategories)

                        isLoading = true
                        errorMessage = null

                        viewModel.loadRecommendedActivities(
                            accessToken = accessToken,
                            latitude = 4.6382,
                            longitude = -74.0840,
                            radiusKm = 10.0,

                            onSuccess = { activities ->
                                recommendations = activities
                                isLoading = false
                            },

                            onError = { error ->
                                errorMessage = error
                                isLoading = false
                            }
                        )
                    },

                    enabled = !isLoading,

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),

                    shape = RoundedCornerShape(18.dp),

                    colors = ButtonDefaults.buttonColors(
                        containerColor = LlamallaRose
                    )
                ) {
                    Text(
                        text = "Ver recomendaciones",
                        fontSize = 16.sp
                    )
                }

                if (isLoading) {
                    Text(
                        text = "Cargando recomendaciones...",
                        color = Color.Gray
                    )
                }

                errorMessage?.let { error ->
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error
                    )
                }


                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val intent = Intent(
                            context,
                            NearbyMeetingPointActivity::class.java
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LlamallaBlue,
                        contentColor = LlamallaBlack
                    )
                ) {
                    Text("Buscar por punto de encuentro")
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Recomendados para ti",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(recommendations) { activity ->
                RecommendationCard(activity)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun RecommendationCard(activity: Activity) {

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
                color = LlamallaRose,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${activity.durationMinutes} min · ${activity.distanceMeters} m",
                color = Color.DarkGray
            )
        }
    }
}