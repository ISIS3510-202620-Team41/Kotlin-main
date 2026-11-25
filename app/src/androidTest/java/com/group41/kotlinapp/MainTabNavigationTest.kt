package com.group41.kotlinapp

import androidx.compose.ui.test.assertIsDisplayed
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
import kotlinx.coroutines.flow.flowOf
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

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
                val userId = "agenda-navigation-test"
                val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
                    _, method, _ ->
                    check(method.name == "recommendations")
                    Response.success(JsonArray())
                } as ApiService
                MainActivity.dependenciesForTest =
                    MainActivityDependencies(database, flowOf(userId), { userId }, { api }, { true })
                try {
                    base.evaluate()
                } finally {
                    MainActivity.dependenciesForTest = null
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
