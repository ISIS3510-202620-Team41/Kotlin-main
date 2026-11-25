package com.group41.kotlinapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.network.ApiService
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MainTabNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val accountIds = MutableStateFlow<String?>("agenda-navigation-test")

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
                val preferences = IsolatedAgendaPreferences(context)
                val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
                    _, method, _ ->
                    check(method.name == "recommendations")
                    Response.success(JsonArray())
                } as ApiService
                MainActivity.dependenciesForTest =
                    MainActivityDependencies(database, accountIds, { accountIds.value }, { api }, { true }, preferences.store)
                try {
                    base.evaluate()
                } finally {
                    MainActivity.dependenciesForTest = null
                    preferences.close()
                    database.close()
                }
            }
        }
    }).around(compose)

    @Test
    fun highlightsTheCurrentTabForMenuClicksBackAndProfileIntents() {
        assertSelected(R.id.nav_home)
        onView(withId(R.id.nav_activities)).perform(click())
        compose.onNodeWithText("Mis actividades").assertIsDisplayed()
        assertSelected(R.id.nav_activities)

        pressBack()
        compose.onNodeWithText("Tus preferencias").assertIsDisplayed()
        assertSelected(R.id.nav_home)

        // Use the same intent that Profile sends when Actividades is selected.
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(MainActivity.tabIntent(activity, R.id.nav_activities))
        }
        compose.onNodeWithText("Mis actividades").assertIsDisplayed()
        assertSelected(R.id.nav_activities)

        onView(withId(R.id.nav_home)).perform(click())
        assertSelected(R.id.nav_home)
    }

    @Test
    fun remembersTheSelectedMonthPerAccountAndOpensTodayWhenRememberingIsDisabled() {
        val preferences = checkNotNull(MainActivity.dependenciesForTest).agendaPreferences
        val firstAccount = checkNotNull(accountIds.value)
        val today = LocalDate.now()
        val initialDate = today.minusMonths(3).withDayOfMonth(10)
        runBlocking { preferences.setRememberDate(firstAccount, true, initialDate) }

        compose.activityRule.scenario.onActivity { activity ->
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.nav_activities
        }
        compose.onNodeWithText("Ver agenda").performClick()
        compose.onNodeWithText(monthLabel(initialDate)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Mes siguiente").performClick()
        val nextDate = initialDate.plusMonths(1)
        compose.waitUntil(5_000) {
            runBlocking { preferences.observe(firstAccount).first().selectedEpochDay == nextDate.toEpochDay() }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(monthLabel(nextDate)).assertIsDisplayed()

        accountIds.value = "other-agenda-account"
        compose.onNodeWithText(monthLabel(today)).assertIsDisplayed()
        compose.onNodeWithText("Recordar la fecha seleccionada").assertIsOff()
        accountIds.value = firstAccount
        compose.onNodeWithText(monthLabel(nextDate)).assertIsDisplayed()
        compose.onNodeWithText("Recordar la fecha seleccionada").assertIsOn().performClick()
        compose.waitUntil(5_000) {
            runBlocking { !preferences.observe(firstAccount).first().rememberDate }
        }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Ver agenda").performClick()
        compose.onNodeWithText(monthLabel(today)).assertIsDisplayed()
    }

    private fun monthLabel(date: LocalDate): String {
        val locale = Locale.forLanguageTag("es-CO")
        val name = date.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
        return "$name de ${date.year}"
    }

    @Test
    fun opensAgendaAndReturnsToMyActivitiesWithEitherBackButton() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId =
                R.id.nav_activities
        }
        compose.onNodeWithText("Ver agenda").performClick()
        compose.onNodeWithText("Mi agenda").assertIsDisplayed()

        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Mis actividades").assertIsDisplayed()
        assertSelected(R.id.nav_activities)

        compose.onNodeWithText("Ver agenda").performClick()
        compose.onNodeWithText("Mi agenda").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Mis actividades").assertIsDisplayed()
        assertSelected(R.id.nav_activities)
    }

    private fun assertSelected(tab: Int) {
        compose.runOnIdle {
            val bar = compose.activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            assertEquals(tab, bar.selectedItemId)
        }
    }
}
