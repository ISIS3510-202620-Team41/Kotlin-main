package com.group41.kotlinapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.group41.kotlinapp.repository.ActivityChanges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.connectivity.ConnectivityObserver
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.JoinResult
import com.group41.kotlinapp.repository.PendingActionRepository
import kotlinx.coroutines.flow.map
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.ui.theme.LlamallaBlack
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vista Protegida 2 — Detalle de Actividad.
 *
 * Cumple con el criterio de Conectividad Eventual:
 * - Renderiza detalles instantáneamente desde la caché en memoria (Intent / ActivityMemoryCache).
 * - Carga de forma transparente desde la base de datos local Room si el proceso de la app fue reiniciado.
 * - Renderiza imágenes en disco previamente descaragas usando Coil.
 * - Calcula distancias de manera local vía Haversine.
 */
class ActivityDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: ""
        @Suppress("DEPRECATION")
        val initialActivity = intent.getSerializableExtra(EXTRA_ACTIVITY_OBJECT) as? Activity

        setContent {
            KotlinAppTheme {
                ActivityDetailScreen(
                    activityId = activityId,
                    initialActivity = initialActivity,
                    onBackClick = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_ACTIVITY_ID = "extra_activity_id"
        const val EXTRA_ACTIVITY_OBJECT = "extra_activity_object"

        fun createIntent(context: Context, activity: Activity): Intent {
            return Intent(context, ActivityDetailActivity::class.java).apply {
                putExtra(EXTRA_ACTIVITY_ID, activity.id)
                putExtra(EXTRA_ACTIVITY_OBJECT, activity)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityDetailScreen(
    activityId: String,
    initialActivity: Activity?,
    onBackClick: () -> Unit
) {
    // 1. In-Memory Fast Path: Fast initial state hydration from Intent extras or LRU memory cache
    var activity by remember { mutableStateOf(initialActivity ?: ActivityMemoryCache.get(activityId)) }
    var isLoading by remember { mutableStateOf(activity == null) }
    var isJoining by remember { mutableStateOf(false) }
    var isJoined by remember { mutableStateOf(activity?.joined == true) }

    /**
     * El usuario ya esta inscrito, pero el backend todavia no lo sabe.
     *
     * Arranca leyendo lo que haya guardado en la actividad, no en false. Eso es lo que hace
     * que el aviso siga ahi despues de cerrar y volver a abrir la app: el dato vive en la
     * base de datos local, no en la memoria de esta pantalla.
     */
    var isPendingSync by remember { mutableStateOf(activity?.pendingSync == true) }
    var joinMessage by remember { mutableStateOf<String?>(null) }

    // 2. Room DAO Access: Obtains Application context safely to pass ActivityDao to repository
    val context = LocalContext.current

    /**
     * Si hay internet o no, pero avisando en el momento en que cambia.
     *
     * Antes esto se calculaba una sola vez con `ApiClient.isOnline()`. El problema es que
     * esa es una foto del instante en que se abrio la pantalla: si el usuario activaba el
     * modo avion estando aqui, el banner de offline no aparecia hasta que saliera y volviera
     * a entrar. Y al reves: al volver el wifi, el banner se quedaba puesto mintiendo.
     *
     * ConnectivityObserver da un flujo de avisos, y collectAsState hace que Compose vuelva a
     * dibujar la pantalla sola cada vez que llega uno.
     */
    val connectivity = remember { ConnectivityObserver(context) }
    val isOffline by connectivity.isOnline
        .map { online -> !online }
        .collectAsState(initial = !connectivity.currentlyOnline())

    /**
     * El alcance donde corre la inscripcion.
     *
     * Se usa rememberCoroutineScope y no CoroutineScope(Dispatchers.IO) a mano, que era lo
     * que habia antes, porque este se cancela solo cuando el usuario sale de la pantalla.
     * Con el anterior, salir a mitad de una inscripcion dejaba la tarea corriendo e
     * intentando escribir en una pantalla que ya no existe.
     */
    val joinScope = rememberCoroutineScope()
    val repository = remember {
        val database = (context.applicationContext as App).database
        ActivityRepository(
            activityDao = database.activityDao(),
            pendingActions = PendingActionRepository(database)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activityId, lifecycleOwner, isOffline) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ActivityChanges.changes.collect {
                if (activityId.isNotBlank()) {
                    repository.getActivityById(activityId)?.let { loaded ->
                        activity = loaded
                        isJoined = loaded.joined
                        isPendingSync = loaded.pendingSync
                    }
                    isLoading = false
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = LlamallaBlue)
            }
        } else if (activity == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No se pudo cargar la actividad",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onBackClick) {
                        Text("Regresar")
                    }
                }
            }
        } else {
            val act = activity!!
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
            ) {
                // Cabecera con imagen y botón de regreso
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    if (!act.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = act.imageUrl,
                            contentDescription = act.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFDDF4F8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Llamalla",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = LlamallaBlue
                            )
                        }
                    }

                    Button(
                        onClick = onBackClick,
                        modifier = Modifier
                            .padding(16.dp)
                            .size(42.dp)
                            .background(Color.White.copy(alpha = 0.9f), CircleShape)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = LlamallaBlack
                        )
                    }

                    // Badge de modo offline si aplica
                    if (isOffline) {
                        Box(
                            modifier = Modifier
                                .padding(16.dp)
                                .background(Color(0xFFFFF3CD), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "Modo Offline",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF856404)
                            )
                        }
                    }
                }

                // Cuerpo del detalle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Título principal
                    Text(
                        text = act.name,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = LlamallaBlack
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Chips de características
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(act.category, fontWeight = FontWeight.SemiBold) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = LlamallaRose.copy(alpha = 0.15f),
                                labelColor = LlamallaRose
                            )
                        )

                        SuggestionChip(
                            onClick = {},
                            label = { Text(act.price, fontWeight = FontWeight.Bold) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFFE8F5E9),
                                labelColor = Color(0xFF2E7D32)
                            )
                        )

                        val cupoText = if (act.capacity != null) {
                            "${act.participants}/${act.capacity} cupos"
                        } else {
                            "Cupo libre"
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(cupoText) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFFEDE7F6),
                                labelColor = Color(0xFF512DA8)
                            )
                        )

                        SuggestionChip(
                            onClick = {},
                            label = { Text("${act.durationMinutes} min") }
                        )

                        if (act.distanceMeters > 0) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("${act.distanceMeters} m") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = Color(0xFFEEEEEE))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Ubicación
                    Text(
                        text = "Ubicación",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = act.locationName ?: "Punto de encuentro en campus",
                        fontSize = 15.sp,
                        color = Color.DarkGray
                    )

                    if (!act.aboutLocation.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FA))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Sobre el lugar:",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = act.aboutLocation,
                                    fontSize = 14.sp,
                                    color = Color.DarkGray,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }

                    if (!act.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Descripción",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = act.description,
                            fontSize = 15.sp,
                            color = Color.DarkGray,
                            lineHeight = 22.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Enrollment action
                    Button(
                        onClick = {
                            // Tap the button again to reverse a pending enrollment change.
                            val joining = !isJoined
                            isJoining = true
                            joinMessage = null
                            joinScope.launch {
                                try {
                                    val result = if (joining) {
                                        repository.joinActivity(act.id, act.recommendationId, act.freeTimeMinutes)
                                    } else {
                                        repository.cancelEnrollment(act.id)
                                    }
                                    isJoined = joining
                                    isPendingSync = result == JoinResult.QUEUED
                                    repository.getActivityById(act.id)?.let { loaded ->
                                        activity = loaded
                                        isJoined = loaded.joined
                                        isPendingSync = loaded.pendingSync
                                    }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    joinMessage = error.message ?: "No se pudo actualizar la inscripcion"
                                } finally {
                                    isJoining = false
                                }
                            }
                        },
                        enabled = !isJoining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LlamallaBlue,
                            contentColor = LlamallaBlack,
                            disabledContainerColor = Color(0xFFE0E0E0),
                            disabledContentColor = Color.Gray
                        )
                    ) {
                        Text(
                            text = when {
                                isJoining -> "Actualizando inscripcion..."
                                isJoined && isPendingSync -> "Inscripcion pendiente · Cancelar"
                                isPendingSync -> "Cancelacion pendiente · Volver a unirme"
                                isJoined -> "Cancelar inscripcion"
                                else -> "Unirme a esta actividad"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    joinMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = msg,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}