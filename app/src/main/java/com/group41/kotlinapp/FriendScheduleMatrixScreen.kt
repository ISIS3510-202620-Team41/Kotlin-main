package com.group41.kotlinapp

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.group41.kotlinapp.model.FriendAvailabilityItem
import com.group41.kotlinapp.model.SharedGap
import com.group41.kotlinapp.model.TimeInterval
import com.group41.kotlinapp.ui.theme.LlamallaBlack
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import com.group41.kotlinapp.viewmodel.FriendScheduleUiState
import com.group41.kotlinapp.viewmodel.FriendScheduleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendScheduleMatrixScreen(
    viewModel: FriendScheduleViewModel,
    onBackClick: () -> Unit,
    onCreatePlanClick: (SharedGap) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Micro-optimización: derivedStateOf para filtrar huecos evitando recomposiciones innecesarias
    val filteredGaps by remember(state.matrixResult, state.filterMinMinutes) {
        derivedStateOf {
            state.matrixResult?.fullOverlapGaps?.filter {
                it.durationMinutes >= state.filterMinMinutes
            } ?: emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Disponibilidad de Amigos",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = LlamallaBlack
                        )
                        Text(
                            text = "Cálculo concurrente de huecos compartidos",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = LlamallaBlack
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadData(state.selectedDate) }) {
                        Text(
                            text = "🔄",
                            fontSize = 18.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Selector de fecha
            item {
                Spacer(modifier = Modifier.height(4.dp))
                DateSelectionRow(
                    selectedDate = state.selectedDate,
                    onDateSelected = { viewModel.setDate(it) }
                )
            }

            // 2. Banner de rendimiento multi-threading (evidencia de sustentación Viva Voce)
            item {
                state.matrixResult?.let { result ->
                    ConcurrencyMetricBadge(
                        executionTimeMs = result.executionTimeMs,
                        activeThreads = result.activeThreads,
                        totalFreeMinutes = result.totalCommonFreeMinutes
                    )
                }
            }

            // 3. Barra horizontal de selección de amigos
            item {
                FriendSelectionBar(
                    friends = state.friends,
                    onToggleFriend = { viewModel.toggleFriendSelection(it) },
                    onSelectAll = { viewModel.selectAllFriends(true) },
                    onDeselectAll = { viewModel.selectAllFriends(false) }
                )
            }

            // 4. Filtro por duración mínima de hueco
            item {
                DurationFilterRow(
                    currentFilter = state.filterMinMinutes,
                    onSelectFilter = { viewModel.setMinDurationFilter(it) }
                )
            }

            // Estado de carga
            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = LlamallaBlue)
                    }
                }
            }

            // Mensaje de error / offline
            if (state.errorMessage != null) {
                item {
                    Text(
                        text = state.errorMessage ?: "",
                        color = Color(0xFFC62828),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // 5. Sección: Huecos Libres en Común (Solape Total)
            item {
                Text(
                    text = "Huecos libres compartidos (${filteredGaps.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LlamallaBlack
                )
            }

            if (filteredGaps.isEmpty() && !state.isLoading) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No hay huecos simultáneos con los amigos seleccionados.",
                                fontSize = 14.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Intenta seleccionar menos amigos o cambiar de fecha.",
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(
                    items = filteredGaps,
                    key = { "${it.interval.startMinutes}_${it.interval.endMinutes}" }
                ) { gap ->
                    SharedGapCard(
                        gap = gap,
                        onCreatePlan = {
                            viewModel.onPlanFromGapClicked(gap)
                            onCreatePlanClick(gap)
                        }
                    )
                }
            }

            // 6. Sección: Solapes Parciales (si hay 3 o más amigos)
            val partialGaps = state.matrixResult?.partialOverlapGaps ?: emptyList()
            if (partialGaps.isNotEmpty()) {
                item {
                    Text(
                        text = "Casi todos libres (Solape parcial)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF555555),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(
                    items = partialGaps,
                    key = { "partial_${it.interval.startMinutes}_${it.interval.endMinutes}" }
                ) { gap ->
                    PartialGapCard(gap = gap)
                }
            }

            // 7. Visual Timeline Matrix (Visualización gráfica tipo Heatmap/Matriz)
            item {
                Text(
                    text = "Matriz visual de la jornada (08:00 - 20:00)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LlamallaBlack,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            item {
                VisualTimelineMatrix(
                    friends = state.friends,
                    fullGaps = filteredGaps
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun DateSelectionRow(
    selectedDate: String,
    onDateSelected: (String) -> Unit
) {
    val today = remember { FriendScheduleViewModel.currentDateFormatted() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "📅",
            fontSize = 18.sp
        )
        Text(
            text = "Fecha:",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        FilterChip(
            selected = selectedDate == today,
            onClick = { onDateSelected(today) },
            label = { Text("Hoy") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = LlamallaBlue,
                selectedLabelColor = LlamallaBlack
            )
        )
    }
}

@Composable
fun ConcurrencyMetricBadge(
    executionTimeMs: Long,
    activeThreads: Int,
    totalFreeMinutes: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "⚡ Concurrencia activa (Dispatchers.Default)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    text = "Matriz calculada en $executionTimeMs ms con $activeThreads núcleos de CPU",
                    fontSize = 11.sp,
                    color = Color(0xFF1B5E20)
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF81C784), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${totalFreeMinutes}m libres",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun FriendSelectionBar(
    friends: List<FriendAvailabilityItem>,
    onToggleFriend: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Amigos incluidos (${friends.count { it.isSelected }}/${friends.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Todos",
                    fontSize = 12.sp,
                    color = LlamallaBlue,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onSelectAll() }
                )
                Text(
                    text = "Ninguno",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onDeselectAll() }
                )
            }
        }

        if (friends.isEmpty()) {
            Text(
                text = "No tienes amigos registrados en la aplicación.",
                fontSize = 12.sp,
                color = Color.Gray
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(friends, key = { it.user.id }) { item ->
                    FilterChip(
                        selected = item.isSelected,
                        onClick = { onToggleFriend(item.user.id) },
                        label = {
                            Text(
                                text = item.user.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (item.isSelected) LlamallaBlue else Color.LightGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.user.name.take(1).uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LlamallaBlack
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LlamallaBlue.copy(alpha = 0.35f),
                            selectedLabelColor = LlamallaBlack
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DurationFilterRow(
    currentFilter: Int,
    onSelectFilter: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Mínimo:", fontSize = 12.sp, color = Color.Gray)
        listOf(0 to "Todos", 30 to "≥ 30 min", 60 to "≥ 1 hora", 90 to "≥ 1.5 h").forEach { (mins, label) ->
            val isSelected = currentFilter == mins
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) LlamallaBlack else Color(0xFFEEEEEE))
                    .clickable { onSelectFilter(mins) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White else Color.Black,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun SharedGapCard(
    gap: SharedGap,
    onCreatePlan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = gap.interval.formattedRange(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LlamallaBlack
                )
                Box(
                    modifier = Modifier
                        .background(LlamallaRose, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${gap.durationMinutes} min",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Text(
                text = "✓ ¡Todos los ${gap.totalParticipants} amigos coinciden!",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2E7D32)
            )

            Button(
                onClick = onCreatePlan,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LlamallaBlue,
                    contentColor = LlamallaBlack
                )
            ) {
                Text("Crear plan en este horario", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PartialGapCard(gap: SharedGap) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gap.interval.formattedRange(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${gap.totalParticipants} amigos disponibles: ${gap.participantNames.joinToString(", ")}",
                    fontSize = 11.sp,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${gap.durationMinutes} min",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF57F17)
            )
        }
    }
}

@Composable
fun VisualTimelineMatrix(
    friends: List<FriendAvailabilityItem>,
    fullGaps: List<SharedGap>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Regla de horas (08:00 a 20:00)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("08:00", "11:00", "14:00", "17:00", "20:00").forEach { h ->
                    Text(text = h, fontSize = 9.sp, color = Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Fila por cada amigo activo
            val activeFriends = friends.filter { it.isSelected }
            activeFriends.forEach { friend ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = friend.user.name.take(6),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(50.dp),
                        maxLines = 1
                    )
                    // Barra de tiempo de 08:00 (480m) a 20:00 (1200m) = 720 minutos totales
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEEEEEE))
                    ) {
                        friend.freeIntervals.forEach { interval ->
                            val startNorm = ((interval.startMinutes - 480).coerceAtLeast(0) / 720f).coerceIn(0f, 1f)
                            val endNorm = ((interval.endMinutes - 480).coerceAtMost(720) / 720f).coerceIn(0f, 1f)
                            val widthNorm = (endNorm - startNorm).coerceAtLeast(0f)

                            if (widthNorm > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(endNorm)
                                        .padding(start = (startNorm * 200).dp) // approximate visual scale
                                        .height(14.dp)
                                        .background(LlamallaBlue.copy(alpha = 0.8f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
