package com.group41.kotlinapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.ui.theme.LlamallaBlue
import com.group41.kotlinapp.ui.theme.LlamallaRose
import com.group41.kotlinapp.ui.theme.LlamallaWhite
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Activity details with the scheduled timestamps in their original zone or offset. */
internal data class AgendaActivity(
    val activityId: String,
    val title: String,
    val startsAt: ZonedDateTime,
    val endsAt: ZonedDateTime?,
    val enrollmentStatus: AgendaEnrollmentStatus,
    val location: String? = null
)

internal enum class AgendaEnrollmentStatus {
    CONFIRMED,
    PENDING_JOIN,
    PENDING_CANCELLATION
}

private val agendaLocale = Locale.forLanguageTag("es-CO")
private val agendaDateFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", agendaLocale)
private val agendaTimeFormat = DateTimeFormatter.ofPattern("HH:mm", agendaLocale)
private val agendaEndDateTimeFormat = DateTimeFormatter.ofPattern("d MMM · HH:mm", agendaLocale)

/**
 * Displays a monthly agenda and the selected day's activities.
 * The caller supplies connectivity, activity data, and the back and export actions.
 */
@Composable
internal fun PersonalAgendaScreen(
    activities: List<AgendaActivity>,
    isOnline: Boolean,
    onBack: () -> Unit,
    onExportCalendar: (() -> Unit)? = null,
    initialDate: LocalDate = LocalDate.now()
) {
    // Save the epoch day so Android can restore the selection and its month together.
    var selectedEpochDay by rememberSaveable(initialDate) {
        mutableLongStateOf(initialDate.toEpochDay())
    }
    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)
    val month = YearMonth.from(selectedDate)
    // Group and display events in the device's time zone so they match the calendar day.
    val displayZone = ZoneId.systemDefault()
    val activitiesByDate = activities.groupBy {
        it.startsAt.withZoneSameInstant(displayZone).toLocalDate()
    }
    val dayActivities = activitiesByDate[selectedDate].orEmpty().sortedBy { it.startsAt.toInstant() }
    val ink = colorResource(R.color.text_dark)

    Column(
        Modifier.fillMaxSize()
            .background(colorResource(R.color.background_light))
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(LlamallaBlue)
                .statusBarsPadding()
                .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgendaArrow("Volver", onBack)
            Text(
                "Mi agenda",
                modifier = Modifier.semantics { heading() },
                color = ink,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (!isOnline) {
            Text(
                "Sin conexión. Puedes consultar las actividades disponibles.",
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp)
                    .background(colorResource(R.color.blue_soft), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = ink,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AgendaMonth(
                    month = month,
                    selectedDate = selectedDate,
                    activityDates = activitiesByDate.keys,
                    onSelectDate = { selectedEpochDay = it.toEpochDay() },
                    onChangeMonth = { offset ->
                        val target = month.plusMonths(offset)
                        // Keep the same day number where possible, including across shorter months.
                        selectedEpochDay = target.atDay(
                            selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())
                        ).toEpochDay()
                    }
                )
            }
            item {
                Text(
                    selectedDate.format(agendaDateFormat).replaceFirstChar { it.titlecase(agendaLocale) },
                    modifier = Modifier.semantics { heading() },
                    color = ink,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (dayActivities.isEmpty()) {
                item {
                    Text(
                        "No tienes actividades para este día.",
                        color = colorResource(R.color.text_muted),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(dayActivities, key = { it.activityId }) { activity ->
                AgendaCard {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            activity.formatSchedule(displayZone),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            activity.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        activity.location?.takeIf { it.isNotBlank() }?.let { location ->
                            Text(
                                location,
                                color = colorResource(R.color.text_muted),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        EnrollmentStatusBadge(
                            joinPending = activity.enrollmentStatus == AgendaEnrollmentStatus.PENDING_JOIN,
                            cancellationPending =
                                activity.enrollmentStatus == AgendaEnrollmentStatus.PENDING_CANCELLATION
                        )
                    }
                }
            }
        }
        Button(
            onClick = { onExportCalendar?.invoke() },
            enabled = onExportCalendar != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LlamallaBlue, contentColor = ink)
        ) {
            Text("Exportar calendario")
        }
    }
}

/** Formats both endpoints in the calendar's zone and shows the end date for overnight activities. */
private fun AgendaActivity.formatSchedule(displayZone: ZoneId): String {
    val localStart = startsAt.withZoneSameInstant(displayZone)
    val startLabel = localStart.format(agendaTimeFormat)
    val localEnd = endsAt?.withZoneSameInstant(displayZone) ?: return startLabel
    val endFormat = if (localEnd.toLocalDate() == localStart.toLocalDate()) agendaTimeFormat
        else agendaEndDateTimeFormat
    return "$startLabel – ${localEnd.format(endFormat)}"
}

/** Lays out the month in Monday-first weeks and marks dates that have activities. */
@Composable
private fun AgendaMonth(
    month: YearMonth,
    selectedDate: LocalDate,
    activityDates: Set<LocalDate>,
    onSelectDate: (LocalDate) -> Unit,
    onChangeMonth: (Long) -> Unit
) {
    val leadingDays = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val weekCount = (leadingDays + month.lengthOfMonth() + 6) / 7
    val monthName = month.month.getDisplayName(TextStyle.FULL, agendaLocale)
        .replaceFirstChar { it.titlecase(agendaLocale) }

    AgendaCard {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgendaArrow("Mes anterior", { onChangeMonth(-1) })
                Text(
                    "$monthName de ${month.year}",
                    modifier = Modifier.weight(1f).semantics { heading() },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                AgendaArrow("Mes siguiente", { onChangeMonth(1) }, rotation = 180f)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    Text(
                        day.getDisplayName(TextStyle.SHORT, agendaLocale)
                            .removeSuffix(".").replaceFirstChar { it.titlecase(agendaLocale) },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = colorResource(R.color.text_muted),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            repeat(weekCount) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = week * 7 + column - leadingDays + 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                AgendaDay(date, date == selectedDate, date in activityDates) {
                                    onSelectDate(date)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaDay(date: LocalDate, selected: Boolean, hasActivities: Boolean, onClick: () -> Unit) {
    val label = date.format(agendaDateFormat) + if (hasActivities) ", con actividades" else ""
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(if (selected) LlamallaBlue else LlamallaWhite),
            contentAlignment = Alignment.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || hasActivities) FontWeight.Bold else FontWeight.Normal
            )
            if (hasActivities) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                        .size(5.dp).background(LlamallaRose, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun AgendaArrow(label: String, onClick: () -> Unit, rotation: Float = 0f) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            contentDescription = label,
            modifier = Modifier.rotate(rotation),
            tint = colorResource(R.color.text_dark)
        )
    }
}

@Composable
private fun AgendaCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = LlamallaWhite,
            contentColor = colorResource(R.color.text_dark)
        ),
        border = BorderStroke(1.dp, colorResource(R.color.border_light))
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun PersonalAgendaPreview() {
    val date = LocalDate.of(2026, 11, 25)
    val zone = ZoneId.systemDefault()
    val morning = date.atTime(9, 0).atZone(zone)
    val afternoon = date.atTime(16, 30).atZone(zone)
    KotlinAppTheme {
        PersonalAgendaScreen(
            activities = listOf(
                AgendaActivity(
                    "photo", "Taller de fotografía", afternoon, afternoon.plusHours(1),
                    AgendaEnrollmentStatus.CONFIRMED, "Salón 204"
                ),
                AgendaActivity(
                    "walk", "Caminata por el campus", morning, morning.plusHours(1),
                    AgendaEnrollmentStatus.PENDING_JOIN, "Entrada principal"
                ),
                AgendaActivity(
                    "reading", "Club de lectura", afternoon.plusDays(2), null,
                    AgendaEnrollmentStatus.CONFIRMED, "Biblioteca"
                ),
                AgendaActivity(
                    "yoga", "Yoga", morning.plusMonths(1), null, AgendaEnrollmentStatus.CONFIRMED
                )
            ),
            isOnline = false,
            onBack = {},
            onExportCalendar = {},
            initialDate = date
        )
    }
}
