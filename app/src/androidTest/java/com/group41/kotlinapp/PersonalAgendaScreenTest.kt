package com.group41.kotlinapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.cache.AgendaMemoryCache
import com.group41.kotlinapp.cache.AgendaMemoryCache.Event
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.storage.AgendaPreferences
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.AgendaViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Exercises the offline agenda with an isolated Room database and test settings.
 * The tests cover enrollment updates, reopening Room, the export button, and
 * whether hiding pending enrollments updates both calendar markers and cards.
 */

@RunWith(AndroidJUnit4::class)
class PersonalAgendaScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun navigatingBackToAnOfflineMonthReusesItsPreparedActivities() {
        val initialDate = LocalDate.of(2026, 11, 18)
        val startsAt = initialDate.atTime(9, 30).atZone(ZoneId.systemDefault())
        val activities = listOf(
            AgendaActivity("workshop", "Taller", startsAt, null, AgendaEnrollmentStatus.CONFIRMED)
        )
        val events = mutableListOf<Event>()
        val cache = AgendaMemoryCache { events.add(it) }
        compose.setContent {
            KotlinAppTheme {
                PersonalAgendaScreen(activities, false, {}, initialDate = initialDate, monthCache = cache)
            }
        }
        compose.onNodeWithContentDescription("Mes siguiente").performClick()
        compose.onNodeWithText("Diciembre de 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mes anterior").performClick()
        compose.onNodeWithText("Noviembre de 2026").assertIsDisplayed()
        assertAgendaText("Taller")
        compose.runOnIdle { assertEquals(listOf(Event.MISS, Event.MISS, Event.HIT), events) }
    }

    @Test
    fun hidingPendingOfflineRemovesTheirCalendarMarkerAndDailyCards() {
        val date = LocalDate.of(2026, 11, 18)
        val startsAt = date.atTime(9, 30).atZone(ZoneId.systemDefault())
        val preferences = mutableStateOf(AgendaPreferences())
        val activities = listOf(
            AgendaActivity("join", "Inscripción de prueba", startsAt, null, AgendaEnrollmentStatus.PENDING_JOIN),
            AgendaActivity("cancel", "Cancelación de prueba", startsAt, null, AgendaEnrollmentStatus.PENDING_CANCELLATION),
            AgendaActivity("confirmed", "Confirmada", startsAt.plusDays(1), null, AgendaEnrollmentStatus.CONFIRMED)
        )
        compose.setContent {
            KotlinAppTheme {
                PersonalAgendaScreen(
                    activities,
                    isOnline = false,
                    onBack = {},
                    onExportCalendar = {},
                    initialDate = date,
                    preferences = preferences.value,
                    onShowPendingChange = { preferences.value = preferences.value.copy(showPending = it) }
                )
            }
        }
        val marker = "miércoles 18 de noviembre, con actividades"
        val unmarked = "miércoles 18 de noviembre"
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(marker))
        compose.onNodeWithContentDescription(marker).assertIsDisplayed()
        assertAgendaText("Inscripción de prueba")
        assertAgendaText("Cancelación de prueba")

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Mostrar inscripciones pendientes"))
        compose.onNodeWithText("Mostrar inscripciones pendientes").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(unmarked))
        compose.onNodeWithContentDescription(unmarked).assertIsDisplayed()
        compose.onNodeWithContentDescription(marker).assertDoesNotExist()
        assertAgendaText("No tienes actividades para este día.")
        compose.onNodeWithText("Inscripción de prueba").assertDoesNotExist()
        compose.onNodeWithText("Cancelación de prueba").assertDoesNotExist()
        compose.onNodeWithText("Exportar calendario").assertIsEnabled()
    }

    @Test
    fun showsLocalScheduleUpdatesAndRestoresItFromDiskOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
        val databaseName = "agenda-${UUID.randomUUID()}.db"
        fun openDatabase() = Room.databaseBuilder(app, AppDatabase::class.java, databaseName).build()

        val date = LocalDate.of(2026, 11, 18)
        val start = date.atTime(9, 30).atZone(ZoneId.systemDefault())
        val title = "Taller de agenda"
        val canceledTitle = "Taller cancelado"
        val enrollment = UserEnrollmentEntity("agenda-user", "workshop", joined = true, enrolledAt = 1L)
        val canceled = UserEnrollmentEntity(
            enrollment.userId, "canceled-workshop", joined = false, enrolledAt = 2L, canceledAt = 3L
        )
        var database = openDatabase()
        val store = ViewModelStore()
        val preferences = IsolatedAgendaPreferences(app)
        val currentModel = mutableStateOf<AgendaViewModel?>(null)
        var exportClicks = 0
        fun openAgenda() {
            compose.runOnIdle {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AgendaViewModel(app, database.userEnrollmentDao(), flowOf(enrollment.userId), preferences.store) as T
                }
                currentModel.value = ViewModelProvider(store, factory)[AgendaViewModel::class.java]
            }
        }

        try {
            runBlocking {
                database.activityDao().insertActivity(
                    Activity(enrollment.activityId, title, "Arte", 60, 0).copy(
                        startTime = start.toOffsetDateTime().toString(),
                        endTime = start.plusHours(1).toOffsetDateTime().toString()
                    ).toEntity()
                )
                database.userEnrollmentDao().upsert(enrollment)
                database.activityDao().insertActivity(
                    Activity(canceled.activityId, canceledTitle, "Arte", 60, 0).copy(
                        startTime = start.toOffsetDateTime().toString()
                    ).toEntity()
                )
                database.userEnrollmentDao().upsert(canceled)
            }
            compose.setContent {
                KotlinAppTheme {
                    currentModel.value?.let { model ->
                        val state by model.uiState.collectAsStateWithLifecycle()
                        PersonalAgendaScreen(
                            state.activities,
                            isOnline = false,
                            onBack = {},
                            onExportCalendar = { exportClicks++ },
                            initialDate = date.minusDays(1)
                        )
                    }
                }
            }
            openAgenda()
            compose.waitUntil(5_000) { currentModel.value?.uiState?.value?.activities?.isNotEmpty() == true }
            compose.onNodeWithText("Exportar calendario").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1, exportClicks) }
            assertAgendaText("No tienes actividades para este día.")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("18"))
            compose.onNodeWithText("18").performClick()
            assertAgendaText("09:30 – 10:30")
            assertAgendaText("Inscripción confirmada")
            compose.onNodeWithText(canceledTitle).assertDoesNotExist()

            runBlocking {
                database.userEnrollmentDao().upsert(enrollment.copy(joined = false, cancellationPending = true))
            }
            compose.waitUntil(5_000) {
                currentModel.value?.uiState?.value?.activities?.singleOrNull()?.enrollmentStatus ==
                    AgendaEnrollmentStatus.PENDING_CANCELLATION
            }
            assertAgendaText("Cancelación pendiente")
            compose.onNodeWithText("Exportar calendario").assertIsNotEnabled()

            // Recreate the ViewModel and Room connection with only the on-disk data available.
            compose.runOnIdle {
                currentModel.value = null
                store.clear()
            }
            compose.waitForIdle()
            database.close()
            database = openDatabase()
            openAgenda()
            compose.waitUntil(5_000) { currentModel.value?.uiState?.value?.activities?.isNotEmpty() == true }
            compose.onNodeWithText("Sin conexión. Puedes consultar las actividades disponibles.").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("18"))
            compose.onNodeWithText("18").performClick()
            assertAgendaText(title)
            assertAgendaText("Cancelación pendiente")
            compose.onNodeWithText(canceledTitle).assertDoesNotExist()
            assertEquals(canceled, runBlocking { database.userEnrollmentDao().get(canceled.userId, canceled.activityId) })

            runBlocking {
                database.userEnrollmentDao().upsert(enrollment.copy(joined = false, canceledAt = 1L))
            }
            compose.waitUntil(5_000) { currentModel.value?.uiState?.value?.activities?.isEmpty() == true }
            assertAgendaText("No tienes actividades para este día.")
        } finally {
            compose.runOnIdle {
                currentModel.value = null
                store.clear()
            }
            database.close()
            preferences.close()
            app.deleteDatabase(databaseName)
        }
    }

    private fun assertAgendaText(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
