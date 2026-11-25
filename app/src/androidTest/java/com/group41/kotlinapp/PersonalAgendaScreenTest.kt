package com.group41.kotlinapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.ui.theme.KotlinAppTheme
import com.group41.kotlinapp.viewmodel.AgendaViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalAgendaScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsLocalScheduleUpdatesAndRestoresItFromDiskOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
        val databaseName = "agenda-${UUID.randomUUID()}.db"
        fun openDatabase() = Room.databaseBuilder(app, AppDatabase::class.java, databaseName).build()

        val date = LocalDate.of(2026, 11, 18)
        val start = date.atTime(9, 30).atZone(ZoneId.systemDefault())
        val title = "Taller de agenda"
        val enrollment = UserEnrollmentEntity("agenda-user", "workshop", joined = true)
        var database = openDatabase()
        val store = ViewModelStore()
        val currentModel = mutableStateOf<AgendaViewModel?>(null)
        fun openAgenda() {
            compose.runOnIdle {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AgendaViewModel(app, database.userEnrollmentDao(), flowOf(enrollment.userId)) as T
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
            }
            compose.setContent {
                KotlinAppTheme {
                    currentModel.value?.let { model ->
                        val activities by model.activities.collectAsStateWithLifecycle()
                        PersonalAgendaScreen(activities, isOnline = false, onBack = {}, initialDate = date.minusDays(1))
                    }
                }
            }
            openAgenda()
            compose.waitUntil(5_000) { currentModel.value?.activities?.value?.isNotEmpty() == true }
            assertAgendaText("No tienes actividades para este día.")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("18"))
            compose.onNodeWithText("18").performClick()
            assertAgendaText("09:30 – 10:30")
            assertAgendaText("Inscripción confirmada")

            runBlocking {
                database.userEnrollmentDao().upsert(enrollment.copy(joined = false, cancellationPending = true))
            }
            compose.waitUntil(5_000) {
                currentModel.value?.activities?.value?.singleOrNull()?.enrollmentStatus ==
                    AgendaEnrollmentStatus.PENDING_CANCELLATION
            }
            assertAgendaText("Cancelación pendiente")

            // Recreate the ViewModel and Room connection with only the on-disk data available.
            compose.runOnIdle {
                currentModel.value = null
                store.clear()
            }
            compose.waitForIdle()
            database.close()
            database = openDatabase()
            openAgenda()
            compose.waitUntil(5_000) { currentModel.value?.activities?.value?.isNotEmpty() == true }
            compose.onNodeWithText("Sin conexión. Puedes consultar las actividades disponibles.").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("18"))
            compose.onNodeWithText("18").performClick()
            assertAgendaText(title)
            assertAgendaText("Cancelación pendiente")

            runBlocking {
                database.userEnrollmentDao().upsert(enrollment.copy(joined = false, canceledAt = 1L))
            }
            compose.waitUntil(5_000) { currentModel.value?.activities?.value?.isEmpty() == true }
            assertAgendaText("No tienes actividades para este día.")
        } finally {
            compose.runOnIdle {
                currentModel.value = null
                store.clear()
            }
            database.close()
            app.deleteDatabase(databaseName)
        }
    }

    private fun assertAgendaText(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
