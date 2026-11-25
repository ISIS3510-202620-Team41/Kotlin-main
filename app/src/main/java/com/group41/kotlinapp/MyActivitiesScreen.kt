package com.group41.kotlinapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.group41.kotlinapp.cache.ACTIVITY_THUMBNAIL_SIZE_DP
import com.group41.kotlinapp.cache.activityThumbnailRequest
import androidx.compose.ui.platform.LocalContext
import com.group41.kotlinapp.data.local.dao.EnrolledActivity
import com.group41.kotlinapp.data.local.dao.UserEnrollmentDao
import com.group41.kotlinapp.data.local.toActivity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import com.group41.kotlinapp.ui.theme.LlamallaWhite
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

private sealed interface MyActivitiesState {
    data object Loading : MyActivitiesState
    data object Error : MyActivitiesState
    data object SignedOut : MyActivitiesState
    data class Ready(val enrollments: List<EnrolledActivity>) : MyActivitiesState
}

/**
 * Displays enrollments from Room even when the network is unavailable.
 * The existing repository owns cancellation and the worker updates the observed rows.
 *
 * @param cancelEnrollment Submits a cancellation through ActivityRepository.
 * @param loadActivity Fetches missing activity details so the repository can cache them in Room.
 */
@Composable
internal fun MyActivitiesScreen(
    userId: String?,
    enrollmentDao: UserEnrollmentDao,
    isOnline: Boolean,
    onOpenActivity: (Activity) -> Unit,
    cancelEnrollment: suspend (String) -> Unit,
    loadActivity: suspend (String) -> Unit
) {
    var loadAttempt by remember(userId) { mutableIntStateOf(0) }
    // Retrying starts a fresh Room observation after a read failure.
    val enrollmentStates: Flow<MyActivitiesState> = remember(userId, enrollmentDao, loadAttempt) {
        if (userId == null) {
            flowOf(MyActivitiesState.SignedOut)
        } else {
            enrollmentDao.observeEnrolledActivities(userId)
                .map<List<EnrolledActivity>, MyActivitiesState> { MyActivitiesState.Ready(it) }
                .onStart { emit(MyActivitiesState.Loading) }
                .catch { emit(MyActivitiesState.Error) }
        }
    }
    // Reset the displayed state when the user, data source, or read attempt changes.
    val state = key(userId, enrollmentDao, loadAttempt) {
        enrollmentStates.collectAsStateWithLifecycle(MyActivitiesState.Loading).value
    }
    val cancelling = remember(userId) { mutableStateMapOf<String, Boolean>() }
    val cancellationErrors = remember(userId) { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().background(colorResource(R.color.background_light)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(LlamallaBlue)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "Mis actividades",
                modifier = Modifier.semantics { heading() },
                color = colorResource(R.color.text_dark),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (!isOnline) {
            Text(
                "Sin conexión. Los cambios se enviarán al reconectar.",
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .background(colorResource(R.color.blue_soft), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = colorResource(R.color.text_dark),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        when (state) {
            MyActivitiesState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LlamallaRose)
            }
            MyActivitiesState.Error -> MyActivitiesMessage("No se pudieron cargar tus inscripciones.") {
                TextButton(onClick = { loadAttempt++ }) { Text("Reintentar") }
            }
            MyActivitiesState.SignedOut -> MyActivitiesMessage("Inicia sesión para ver tus actividades.")
            is MyActivitiesState.Ready -> {
                val (history, active) = state.enrollments.partition { it.isCanceled }
                val sections = listOf(
                    Triple("Mis inscripciones", active, "No tienes inscripciones activas."),
                    Triple("Historial", history.sortedByDescending { it.canceledAt }, "Todavía no hay cancelaciones.")
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sections.forEach { (title, enrollments, emptyMessage) ->
                        item(key = title) {
                            Text(
                                title,
                                modifier = Modifier.semantics { heading() },
                                color = colorResource(R.color.text_dark),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (enrollments.isEmpty()) {
                            item(key = emptyMessage) {
                                Text(
                                    emptyMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorResource(R.color.text_muted)
                                )
                            }
                        }
                        items(enrollments, key = { "enrollment:${it.enrollmentActivityId}" }) { enrollment ->
                            val id = enrollment.enrollmentActivityId
                            EnrollmentCard(
                                enrollment = enrollment,
                                isOnline = isOnline,
                                isCancelling = cancelling[id] == true,
                                cancellationFailed = cancellationErrors[id] == true,
                                onOpenActivity = onOpenActivity,
                                loadActivity = loadActivity,
                                onCancel = {
                                    // Prevent a second tap for this row while its request is processing.
                                    if (cancelling[id] != true) {
                                        cancelling[id] = true
                                        cancellationErrors.remove(id)
                                        scope.launch {
                                            try {
                                                // Room supplies the new status after the repository saves it.
                                                cancelEnrollment(id)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                cancellationErrors[id] = true
                                            } finally {
                                                cancelling.remove(id)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyActivitiesMessage(text: String, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colorResource(R.color.text_dark))
        action()
    }
}

@Composable
private fun EnrollmentCard(
    enrollment: EnrolledActivity,
    isOnline: Boolean,
    isCancelling: Boolean,
    cancellationFailed: Boolean,
    onOpenActivity: (Activity) -> Unit,
    loadActivity: suspend (String) -> Unit,
    onCancel: () -> Unit
) {
    val id = enrollment.enrollmentActivityId
    val activity = enrollment.activity
    var detailsAttempt by remember(id) { mutableIntStateOf(0) }
    var loadingDetails by remember(id) { mutableStateOf(false) }

    // Keep the enrollment visible and cancellable while details are missing.
    // Retry the fetch when connectivity returns or the user requests it.
    LaunchedEffect(id, activity == null, isOnline, detailsAttempt) {
        if (activity == null && isOnline) {
            loadingDetails = true
            try {
                loadActivity(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the fallback visible and allow a manual retry.
            } finally {
                loadingDetails = false
            }
        }
    }

    val ink = colorResource(R.color.text_dark)
    val muted = colorResource(R.color.text_muted)
    val date = remember(activity?.startTime) { formatEnrollmentDate(activity?.startTime) }
    val cancellationDate = remember(enrollment.canceledAt) {
        enrollment.canceledAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(enrollmentDateFormatter)
        }
    }
    val canCancel = !enrollment.cancellationPending && !enrollment.isCanceled
    val actionShape = RoundedCornerShape(12.dp)
    val actionPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = LlamallaWhite, contentColor = ink),
        border = BorderStroke(1.dp, colorResource(R.color.border_light))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                activity?.imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                    val context = LocalContext.current
                    val request = remember(context, url) { activityThumbnailRequest(context, url).build() }
                    // Use the same loader and cache keys as the background prefetch.
                    AsyncImage(
                        model = request,
                        imageLoader = (context.applicationContext as App).activityThumbnailLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(ACTIVITY_THUMBNAIL_SIZE_DP.dp).clip(RoundedCornerShape(12.dp))
                            .background(colorResource(R.color.blue_soft))
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        activity?.name ?: "Actividad",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (activity != null) {
                        Text(
                            "${activity.category} · ${activity.durationMinutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted
                        )
                    }
                }
            }
            if (activity != null) {
                val facts = listOfNotNull(date, activity.locationName?.takeIf { it.isNotBlank() })
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            } else {
                Text(
                    when {
                        loadingDetails -> "Cargando detalles…"
                        isOnline -> "No se pudieron cargar los detalles."
                        else -> "Detalles no disponibles sin conexión."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isOnline && !loadingDetails) {
                    TextButton(onClick = { detailsAttempt++ }) { Text("Reintentar detalles") }
                }
            }
            EnrollmentStatus(enrollment)
            if (enrollment.isCanceled && cancellationDate != null) {
                Text("Cancelada el $cancellationDate", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activity != null) {
                    Button(
                        onClick = {
                            onOpenActivity(activity.toActivity().copy(
                                joined = enrollment.enrollmentJoined,
                                pendingSync = enrollment.cancellationPending || enrollment.joinPending
                            ))
                        },
                        modifier = Modifier.weight(1f),
                        shape = actionShape,
                        contentPadding = actionPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = LlamallaBlue, contentColor = ink)
                    ) { Text("Ver detalles") }
                }
                if (canCancel) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !isCancelling,
                        modifier = Modifier.weight(1f),
                        shape = actionShape,
                        contentPadding = actionPadding,
                        border = BorderStroke(1.dp, if (isCancelling) muted else LlamallaRose),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LlamallaRose)
                    ) { Text(if (isCancelling) "Cancelando…" else "Cancelar inscripción") }
                }
            }
            if (cancellationFailed && canCancel) {
                Text("No se pudo cancelar la inscripción.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EnrollmentStatus(enrollment: EnrolledActivity) {
    val (label, accent) = when {
        enrollment.isCanceled -> "Inscripción cancelada" to colorResource(R.color.text_muted)
        enrollment.cancellationPending -> "Cancelación pendiente" to LlamallaRose
        enrollment.joinPending -> "Inscripción pendiente" to colorResource(R.color.text_muted)
        else -> "Inscripción confirmada" to colorResource(R.color.text_dark)
    }
    val background = if (enrollment.cancellationPending) LlamallaRose.copy(alpha = 0.08f)
        else colorResource(R.color.blue_soft)
    Row(
        modifier = Modifier.background(background, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dotColor = if (enrollment.isCanceled || enrollment.cancellationPending || enrollment.joinPending) accent else LlamallaBlue
        Box(Modifier.size(7.dp).background(dotColor, CircleShape))
        Text(label, color = accent, style = MaterialTheme.typography.labelLarge)
    }
}

private val enrollmentDateFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.forLanguageTag("es-CO"))

private fun formatEnrollmentDate(startTime: String?): String? {
    if (startTime.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(startTime).atZoneSameInstant(ZoneId.systemDefault())
            .format(enrollmentDateFormatter)
    } catch (_: DateTimeParseException) {
        null
    }
}
