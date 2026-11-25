package com.group41.kotlinapp

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.storage.AgendaPreferences
import com.group41.kotlinapp.storage.AgendaPreferencesStore
import java.io.File
import java.io.Closeable
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Verifies that agenda settings and saved dates survive reopening DataStore and
 * remain separate across accounts. The fixture gives each test its own file,
 * and reopening replaces the DataStore instance while keeping the saved bytes.
 */

@RunWith(AndroidJUnit4::class)
class AgendaPreferencesStoreTest {
    @Test
    fun settingsAndDateSurviveReopeningWithoutLeakingAcrossAccounts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val today = LocalDate.of(2026, 11, 25)
        val firstDate = today.minusMonths(2)
        val secondDate = today.plusDays(3)
        IsolatedAgendaPreferences(context).use { fixture ->
            val store = fixture.store
            assertEquals(AgendaPreferences(), store.observe("first").first())
            store.setShowPending("first", false)
            store.setRememberDate("first", true, today)
            store.saveSelectedDate("first", firstDate)
            assertEquals(AgendaPreferences(), store.observe("second").first())
            store.setRememberDate("second", true, secondDate)

            fixture.reopen()
            val reopened = fixture.store
            val first = reopened.observe("first").first()
            assertEquals(AgendaPreferences(false, true, firstDate.toEpochDay()), first)
            assertEquals(firstDate, first.openingDate(today))
            assertEquals(AgendaPreferences(true, true, secondDate.toEpochDay()), reopened.observe("second").first())

            reopened.setRememberDate("first", false, firstDate)
            reopened.saveSelectedDate("first", secondDate)
            fixture.reopen()
            val disabled = fixture.store.observe("first").first()
            assertEquals(false, disabled.rememberDate)
            assertNull(disabled.selectedEpochDay)
            assertEquals(today, disabled.openingDate(today))
            assertEquals(secondDate, fixture.store.observe("second").first().openingDate(today))
        }
    }
}

internal class IsolatedAgendaPreferences(context: Context) : Closeable {
    private val file = File.createTempFile("agenda-preferences-", ".preferences_pb", context.cacheDir)
    private var scope = newScope()
    var store = openStore()
        private set

    suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = newScope()
        store = openStore()
    }

    override fun close() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        file.delete()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun openStore() =
        AgendaPreferencesStore(PreferenceDataStoreFactory.create(scope = scope) { file })
}
