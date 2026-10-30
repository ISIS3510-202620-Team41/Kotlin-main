package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.ui.theme.LlamallaBlack
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import com.group41.kotlinapp.viewmodel.RecommendationsViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recommendationsViewModel =
            ViewModelProvider(this)[RecommendationsViewModel::class.java]

        enableEdgeToEdge()

        setContent {
            KotlinAppTheme {
                RecommendationScreen(
                    viewModel = recommendationsViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationScreen(
    viewModel: RecommendationsViewModel
) {
    val context = LocalContext.current
    val isOffline by viewModel.isOffline.collectAsState()
    val userId = ApiClient.tokens.userId

    var selectedCategories by remember {
        mutableStateOf(setOf<String>())
    }

    var isPreferencesLoaded by remember {
        mutableStateOf(false)
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

    // Automatically restores saved preferences from Room when the screen opens
    LaunchedEffect(userId) {
        if (!userId.isNullOrBlank()) {
            viewModel.loadPreferences(userId) { restoredCategories ->
                selectedCategories = restoredCategories
                isPreferencesLoaded = true
            }
        } else {
            isPreferencesLoaded = true
        }
    }

    val categories = listOf(
        "Comida",
        "Juego",
        "Música",
        "Estudio",
        "Manualidades",
        "Deportes",
        "Cultura",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                    // Single source of truth for offline badge
                    if (isOffline) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFFF3CD), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Modo Offline",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF856404)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Single source of truth for offline explanation banner
                if (isOffline) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF3CD), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Estás en modo sin conexión. Las actividades y fotos se cargan desde la caché local.",
                            fontSize = 13.sp,
                            color = Color(0xFF856404),
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, ProfileActivity::class.java)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LlamallaBlue,
                            contentColor = LlamallaBlack
                        )
                    ) {
                        Text("Perfil")
                    }

                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, NearbyMeetingPointActivity::class.java)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LlamallaBlue,
                            contentColor = LlamallaBlack
                        )
                    ) {
                        Text("Cercanas")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

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
                        val currentUserId = ApiClient.tokens.userId
                        if (currentUserId.isNullOrBlank()) {
                            errorMessage = "Usuario no autenticado"
                            return@Button
                        }

                        isLoading = true
                        errorMessage = null

                        viewModel.updatePreferences(
                            userId = currentUserId,
                            categories = selectedCategories,
                            onSuccess = {
                                viewModel.loadRecommendedActivities(
                                    userId = currentUserId,
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
                            onError = { error ->
                                errorMessage = error
                                isLoading = false
                            }
                        )
                    },
                    enabled = !isLoading && isPreferencesLoaded,
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
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                errorMessage?.let { error ->
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
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

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val intent = Intent(
                            context,
                            CreateActivityStep1Activity::class.java
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LlamallaRose
                    )
                ) {
                    Text("Crear actividad")
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Recomendados para ti",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(recommendations) { activity ->
                RecommendationCard(
                    activity = activity,
                    viewModel = viewModel,
                    onClick = {
                        val intent = ActivityDetailActivity.createIntent(context, activity)
                        context.startActivity(intent)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendationCard(
    activity: Activity,
    viewModel: RecommendationsViewModel,
    onClick: () -> Unit
) {
    var isJoined by remember(activity.id) {
        mutableStateOf(activity.joined)
    }

    /**
     * La inscripcion esta guardada en el telefono pero el servidor aun no la conoce.
     *
     * Arranca con lo que trae la actividad y no en false, porque esa actividad pudo venir
     * de la base de datos local con una inscripcion que quedo pendiente la vez pasada.
     */
    var isPendingSync by remember(activity.id) {
        mutableStateOf(activity.pendingSync)
    }

    var isJoining by remember(activity.id) {
        mutableStateOf(false)
    }

    var joinError by remember(activity.id) {
        mutableStateOf<String?>(null)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFDDF4F8)
        )
    ) {
        Column {
            if (!activity.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = activity.imageUrl,
                    contentDescription = activity.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                )
            }

            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = activity.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LlamallaBlack
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(activity.category, fontWeight = FontWeight.SemiBold) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = LlamallaRose.copy(alpha = 0.15f),
                            labelColor = LlamallaRose
                        )
                    )

                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(activity.price, fontWeight = FontWeight.Bold) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFE8F5E9),
                            labelColor = Color(0xFF2E7D32)
                        )
                    )

                    val cupo = if (activity.capacity != null) {
                        "${activity.participants}/${activity.capacity} cupos"
                    } else {
                        "Cupo libre"
                    }
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(cupo) }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${activity.durationMinutes} min · ${activity.distanceMeters} m",
                    color = Color.DarkGray,
                    fontSize = 14.sp
                )

                if (!activity.locationName.isNullOrBlank()) {
                    Text(
                        text = activity.locationName,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        isJoining = true
                        joinError = null

                        viewModel.joinActivity(
                            activity = activity,
                            // queued = true significa que no habia internet y la inscripcion
                            // se guardo para mandarla despues.
                            onSuccess = { queued ->
                                isJoined = true
                                isJoining = false
                                isPendingSync = queued
                            },
                            onError = { error ->
                                joinError = error
                                isJoining = false
                            }
                        )
                    },
                    enabled = !isJoined && !isJoining,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LlamallaBlue,
                        contentColor = LlamallaBlack,
                        disabledContainerColor = Color(0xFFD0E1E5),
                        disabledContentColor = Color.Gray
                    )
                ) {
                    Text(
                        // "Pendiente" y no "Unido" mientras el servidor no confirme: el
                        // reloj dice que esta guardado pero todavia en camino.
                        text = when {
                            isJoining -> "Uniendo..."
                            isJoined && isPendingSync -> "Pendiente de enviar ⏱"
                            isJoined -> "Unido ✓"
                            else -> "Unirme"
                        }
                    )
                }

                joinError?.let { error ->
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}