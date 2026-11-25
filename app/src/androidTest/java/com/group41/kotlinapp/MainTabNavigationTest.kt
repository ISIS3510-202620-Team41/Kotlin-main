package com.group41.kotlinapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.group41.kotlinapp.network.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainTabNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        val tokens = ApiClient.tokens
        val previousUser = tokens.userId
        try {
            tokens.userId = "agenda-navigation-test"
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
        } finally {
            tokens.userId = previousUser
        }
    }

    private fun assertSelected(tab: Int) {
        compose.runOnIdle {
            val bar = compose.activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            assertEquals(tab, bar.selectedItemId)
        }
    }
}
