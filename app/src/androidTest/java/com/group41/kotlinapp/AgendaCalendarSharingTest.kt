package com.group41.kotlinapp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.storage.AgendaCalendarExporter
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendaCalendarSharingTest {
    @Test
    fun sharesTheLocalCalendarThroughAReadOnlyContentUri() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = ZonedDateTime.parse("2026-11-25T09:00:00-05:00")
        val activity = AgendaActivity(
            activityId = "workshop",
            title = "Taller",
            startsAt = start,
            endsAt = start.plusHours(1),
            enrollmentStatus = AgendaEnrollmentStatus.CONFIRMED,
            location = "Biblioteca"
        )
        val share = checkNotNull(AgendaCalendarExporter.createShareIntent(context, listOf(activity)))
        val uri = checkNotNull(share.clipData?.getItemAt(0)?.uri)
        try {
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/calendar", share.type)
            assertEquals("content", uri.scheme)
            assertTrue(uri.lastPathSegment.orEmpty().endsWith(".ics"))
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
            val calendar = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            assertTrue(calendar.orEmpty().contains("SUMMARY:Taller\r\nLOCATION:Biblioteca\r\n"))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
        Unit
    }
}
